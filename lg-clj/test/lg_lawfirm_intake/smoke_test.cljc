(ns lg-lawfirm-intake.smoke-test
  "Smoke tests for the lg-lawfirm-intake clj port — clojure.test analogue of the
  Python `tests/test_smoke.py`, plus graph-topology + dispatch-surface tests the
  original could not run offline (the LLM + bengoshi/dispatcher HTTP edges are
  injectable dynamic vars here, so node behavior verifies under bb with stubs)."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [lg-lawfirm-intake.nodes :as nodes]
            [lg-lawfirm-intake.graph :as graph]
            [lg-lawfirm-intake.server :as server]))

;; ── triage-node (parity with test_triage_node_*) ────────────────────────────

(deftest triage-node-fallback-no-key
  ;; no LLM key → *call-triage-llm* default returns nil → fallback triage.
  (let [result (nodes/triage-node {:summary_plain "मेरा चेक बाउंस हो गया"
                                   :lang "hi" :domain "ni138"})
        tr (:triage_result result)]
    (is (some? tr))
    (is (= "ni138" (:domain tr)))
    (is (= "routine" (:urgency tr)))
    (is (vector? (:specializations tr)))))

(deftest triage-node-unknown-domain
  (let [tr (:triage_result (nodes/triage-node {:summary_plain "some complaint"
                                               :lang "en" :domain ""}))]
    (is (= "other" (:domain tr)))))

;; ── summarize-node (parity with test_summarize_node_*) ──────────────────────

(deftest summarize-node-encrypts
  (let [result (nodes/summarize-node {:summary_plain "Cheque bounced"
                                      :triage_result {:summary_en "Cheque bounce NI138"}})
        cipher (:summary_cipher result)]
    (is (.startsWith cipher "signal:v1:"))
    (let [payload (subs cipher (count "signal:v1:"))
          decoded (String. (.decode (java.util.Base64/getDecoder) payload) "UTF-8")]
      (is (re-find #"Cheque bounce" decoded)))))

(deftest summarize-node-empty-summary
  (is (= {} (nodes/summarize-node {:summary_plain ""}))))

;; ── search-node (network injected — parity with test_search_node_*) ─────────

(deftest search-node-returns-empty-on-failure
  (binding [nodes/*http-get* (fn [& _] (throw (ex-info "mock failure" {})))]
    (let [result (nodes/search-node {:jurisdiction "IND"
                                     :triage_result {:specializations ["criminal"]}})]
      (is (= [] (:lawyers result))))))

(deftest search-node-returns-lawyers
  (let [fake [{:did "did:web:lawyer1.etzhayyim.com" :fullName "Test Lawyer"}]]
    (binding [nodes/*http-get* (fn [_url _params] {:lawyers fake :total 1 :offset 0 :limit 10})]
      (let [result (nodes/search-node {:jurisdiction "IND"
                                       :triage_result {:specializations ["labor"]}})]
        (is (= 1 (count (:lawyers result))))
        (is (= "did:web:lawyer1.etzhayyim.com" (:did (first (:lawyers result)))))))))

;; ── match-node (network injected — parity with test_match_node_*) ───────────

(deftest match-node-skips-when-no-case-did
  (let [result (nodes/match-node {:lawyers [{:did "did:web:x.etzhayyim.com"}] :case_did ""})]
    (is (= [] (:grants result)))))

(deftest match-node-skips-when-no-lawyers
  (let [result (nodes/match-node {:lawyers [] :case_did "did:web:lawfirm.etzhayyim.com:case:x"})]
    (is (= [] (:grants result)))))

(deftest match-node-sends-invites
  (binding [nodes/*http-post* (fn [_url body _opts]
                                {:grantDid (str "did:web:lawfirm.etzhayyim.com:grant:" (:granteeDid body))
                                 :grantUri (str "at://lawfirm.etzhayyim.com/grant/" (:granteeDid body))
                                 :conflictCheckPassed true})]
    (let [result (nodes/match-node
                   {:lawyers [{:did "did:web:l1.etzhayyim.com" :fullName "Lawyer One"}
                              {:did "did:web:l2.etzhayyim.com" :fullName "Lawyer Two"}]
                    :case_did "did:web:lawfirm.etzhayyim.com:case:abc"})]
      (is (= 2 (count (:grants result))))
      (is (true? (:conflictCheckPassed (first (:grants result))))))))

;; ── graph topology end-to-end (triage → summarize → search → match) ─────────

(deftest graph-end-to-end-stubbed
  (binding [nodes/*http-get* (fn [_url _params]
                               {:lawyers [{:did "did:web:l1.etzhayyim.com" :fullName "L1"}]})
            nodes/*http-post* (fn [_url body _opts]
                                {:grantDid "g1" :grantUri "at://g1"
                                 :conflictCheckPassed true})]
    (let [out (g/invoke graph/GRAPH {:case_did "did:web:lawfirm.etzhayyim.com:case:z"
                                     :summary_plain "Cheque bounced" :domain "ni138"})]
      ;; triage filled, summary encrypted, lawyers found, grant issued
      (is (= "ni138" (:domain out)))
      (is (.startsWith (:summary_cipher out) "signal:v1:"))
      (is (= 1 (count (:lawyers out))))
      (is (= 1 (count (:grants out)))))))

;; ── dispatch surface (/ok, /runs, /xrpc) ────────────────────────────────────

(deftest health-endpoint
  (let [r (server/health)]
    (is (= 200 (:status r)))
    (is (true? (get-in r [:body :ok])))
    (is (= "lawfirm_intake" (get-in r [:body :graph])))))

(deftest triage-intake-requires-summary
  (let [r (server/dispatch-triage-intake {:case_did "did:web:x"})]
    (is (= 400 (:status r)))))

(deftest triage-intake-happy-path
  (binding [nodes/*http-get*  (fn [_ _] {:lawyers []})
            nodes/*http-post* (fn [_ _ _] {})]
    (let [r (server/dispatch-triage-intake {:summary_plain "Cheque bounced" :domain "ni138"})]
      (is (= 200 (:status r)))
      (is (= "ni138" (get-in r [:body :domain])))
      (is (= 0 (get-in r [:body :lawyers_found])))
      (is (.startsWith (get-in r [:body :summary_cipher]) "signal:v1:")))))

(deftest auth-guard
  (testing "no secret configured → pass (nil)"
    (is (nil? (server/enforce-auth nil))))
  (testing "x-cron exemption → pass"
    (is (nil? (server/enforce-auth "wrong" true))))
  (testing "configured secret mismatch → 401"
    (with-redefs [server/expected-secret (constantly "secret")]
      (is (= 401 (:status (server/enforce-auth "wrong"))))
      (is (nil? (server/enforce-auth "secret")))
      (is (nil? (server/enforce-auth "wrong" true))))))

(deftest live-authority-requires-explicit-capabilities
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"explicit HTTP GET capability"
                        (nodes/http-get "https://example.invalid" {})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"explicit HTTP POST capability"
                        (nodes/http-post "https://example.invalid" {} {})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"explicit HTTP POST capability"
                        (nodes/call-triage-llm-with nil
                         (assoc nodes/default-config :llm-key "configured")
                         "summary" "en" "")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"explicit run-server capability"
                        (server/start-server! nil 0))))

(deftest murakumo-endpoint-guard
  (is (nil? (nodes/assert-murakumo "http://127.0.0.1:4000/v1/chat/completions")))
  (doseq [endpoint ["not-a-url"
                    "https://127.0.0.1:4000/v1"
                    "http://127.0.0.1.attacker.example:4000/v1"]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (nodes/assert-murakumo endpoint)) endpoint)))

(deftest injected-triage-wire-contract
  (let [seen (atom nil)
        result (nodes/call-triage-llm-with
                (fn [url opts]
                  (reset! seen [url opts])
                  {:status 200
                   :body "{\"choices\":[{\"message\":{\"content\":\"{\\\"domain\\\":\\\"tax\\\"}\"}}]}"})
                (assoc nodes/default-config :llm-key "secret")
                "summary" "en" "tax")]
    (is (= "tax" (:domain result)))
    (is (= (:llm-url nodes/default-config) (first @seen)))
    (is (= "Bearer secret" (get-in @seen [1 :headers "Authorization"])))
    (is (re-find #"response_format" (get-in @seen [1 :body])))))
