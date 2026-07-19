(ns lg-lawfirm-intake.nodes
  "Intake triage + bengoshi matching nodes — faithful clj port of
  `lg/lg_lawfirm_intake/nodes.py` (ADR-2606280030).

  Flow: triage-node → summarize-node → search-node → match-node

    triage-node    — LLM classify domain / urgency / specialization (guarded;
                     falls back when no LLM key, exactly like the Python).
    summarize-node — encrypt summary with `signal:v1:` prefix (ADR-0018 Stage 1).
    search-node    — GET bengoshi.etzhayyim.com searchLawyers by jurisdiction +
                     specialization.
    match-node     — POST inviteExternalCounsel for the top-N matched lawyers.

  PORT NOTES / DEVIATIONS:
    * `urllib` → `babashka.http-client`; JSON → `cheshire`. The two HTTP edges
      (`*http-get*` / `*http-post*`) and the triage LLM edge (`*call-triage-llm*`)
      are INJECTABLE dynamic vars (the actor swap pattern) so the graph verifies
      offline under bb — mirroring the Python tests' monkeypatch of `_http_get` /
      `_http_post`.
    * The Python triage LLM points at a RunPod gemma URL. Per ADR-2605215000
      (Murakumo DEFAULT-PREFERRED, no-server-key/read-only loopback) the default
      LLM edge here targets the Murakumo loopback gateway (127.0.0.1:4000) and is
      key-gated identically — absent a key it returns nil and the deterministic
      fallback triage is used.
    * langgraph-clj is synchronous: the Python `async def` nodes become plain
      fns (no RetryPolicy / no asyncio). Node return = partial state map, merged
      by the StateGraph runtime (same contract as the Python dict updates)."
  (:require [clojure.string :as str]
            [cheshire.core :as json]))

;; ---------------------------------------------------------------------------
;; Portable defaults; environment and secrets belong to the host adapter.
;; ---------------------------------------------------------------------------

(def default-config
  {:llm-url "http://127.0.0.1:4000/v1/chat/completions"
   :llm-key ""
   :llm-model "gemma-4-E4B-it"
   :llm-timeout-sec 20.0
   :bengoshi-url "https://bengoshi.etzhayyim.com"
   :dispatcher-url "https://dispatcher.etzhayyim.com"
   :internal-secret ""
   :invite-limit 3
   :invite-expires-days 90})

(def ^:dynamic *config* default-config)

(def known-domains
  #{"ni138" "land" "family" "consumer" "labour" "corporate"
    "tax" "criminal" "rera" "fema" "pil-rti" "visa"})

;; ---------------------------------------------------------------------------
;; HTTP edges: nil denies ambient network authority.
;; ---------------------------------------------------------------------------

(def ^:dynamic *http-get* nil)
(def ^:dynamic *http-post* nil)

(defn http-get [url params]
  (when-not (fn? *http-get*)
    (throw (ex-info "Lawfirm search requires an explicit HTTP GET capability"
                    {:capability :lawfirm/http-get})))
  (*http-get* url params))

(defn http-post [url body opts]
  (when-not (fn? *http-post*)
    (throw (ex-info "Lawfirm invite requires an explicit HTTP POST capability"
                    {:capability :lawfirm/http-post})))
  (*http-post* url body opts))

(defn- internal-headers []
  (let [s (:internal-secret *config*)]
    (if (seq s) {"x-internal-trust" s} {})))

;; ---------------------------------------------------------------------------
;; Triage LLM edge (injectable; guarded — nil when no key or call fails)
;; ---------------------------------------------------------------------------

(def triage-system
  (str "You are a legal intake triage assistant for lawfirm.etzhayyim.com.\n"
       "Given a client complaint in any language, classify it and return JSON:\n"
       "{\"domain\": \"<one of: ni138|land|family|consumer|labour|corporate|tax|criminal|rera|fema|pil-rti|visa|other>\",\n"
       " \"urgency\": \"<routine|urgent|ex-parte>\",\n"
       " \"specializations\": [\"<csv tokens from: labor,contract,family,ip,criminal,tax,land,corporate,consumer,immigration,other>\"],\n"
       " \"jurisdiction\": \"<ISO 3166-1 alpha-3 or ISO 3166-2, infer from state/lang/context>\",\n"
       " \"summary_en\": \"<1-2 sentence English summary, no PII>\"}\n"
       "Reply ONLY with the JSON object. Default urgency=routine when unclear."))

(defn- clip [s n] (let [s (str s)] (subs s 0 (min n (count s)))))

(defn assert-murakumo
  "Reject malformed, non-http and off-fleet inference endpoints before I/O."
  [endpoint]
  (let [[_ scheme host] (or (re-find #"^([A-Za-z][A-Za-z0-9+.\-]*)://([^/?#]*)" (str endpoint))
                            [nil nil nil])]
    (when-not (and (= "http" (some-> scheme str/lower-case))
                   (contains? #{"127.0.0.1:4000" "localhost:4000" "[::1]:4000"}
                              (some-> host str/lower-case)))
      (throw (ex-info "off-fleet LLM endpoint refused (Murakumo loopback only)"
                      {:endpoint endpoint :murakumo-only-violation true})))))

(defn call-triage-llm-with
  "Default `*call-triage-llm*`: POST a chat-completions request to the Murakumo
  loopback gateway. Returns the parsed JSON map, or nil when no key / on failure
  (parity with Python `_call_triage_llm`)."
  [http-post {:keys [llm-url llm-key llm-model llm-timeout-sec]} summary lang domain-hint]
  (when-not (fn? http-post)
    (throw (ex-info "Lawfirm triage requires an explicit HTTP POST capability"
                    {:capability :lawfirm/triage-http-post})))
  (when (seq llm-key)
    (assert-murakumo llm-url)
    (let [prompt (str "Client language: " lang "\n"
                      "Domain hint: " (if (seq domain-hint) domain-hint "unknown") "\n"
                      "Complaint: " (clip summary 800) "\n\nReturn ONLY the JSON object.")]
      (try
        (let [resp  (http-post llm-url
                           {:headers {"Authorization" (str "Bearer " llm-key)
                                      "Content-Type" "application/json"}
                            :timeout (long (* 1000 llm-timeout-sec))
                            :body (json/generate-string
                                    {:model llm-model
                                     :messages [{:role "system" :content triage-system}
                                                {:role "user" :content prompt}]
                                     :temperature 0.1
                                     :max_tokens 256
                                     :response_format {:type "json_object"}})})
              body    (json/parse-string (:body resp) true)
              content (or (get-in body [:choices 0 :message :content]) "")
              parsed  (json/parse-string content true)]
          (when (map? parsed) parsed))
        (catch Exception e
          (binding [*out* *err*]
            (println "[triage-node] llm call failed:" (.getMessage e)))
          nil)))))

(def ^:dynamic *call-triage-llm* nil)

(defn call-triage-llm [summary lang domain-hint]
  (when (fn? *call-triage-llm*)
    (*call-triage-llm* summary lang domain-hint)))

(defn fallback-triage
  "Deterministic triage when the LLM edge is unavailable (parity with
  `_fallback_triage`)."
  [domain-hint]
  {:domain (if (contains? known-domains domain-hint) domain-hint "other")
   :urgency "routine"
   :specializations ["contract"]
   :jurisdiction "IND"
   :summary_en "(triage unavailable — LLM key not configured)"})

;; ---------------------------------------------------------------------------
;; signal:v1: encryption (ADR-0018 Stage 1 — base64 envelope)
;; ---------------------------------------------------------------------------

(defn signal-v1-encrypt [plaintext]
  (let [encoded (.encodeToString (java.util.Base64/getEncoder)
                                 (.getBytes (str plaintext) "UTF-8"))]
    (str "signal:v1:" encoded)))

;; ---------------------------------------------------------------------------
;; Nodes
;; ---------------------------------------------------------------------------

(defn triage-node
  "Classify intake by domain, urgency, and specialization using the LLM edge."
  [state]
  (let [summary     (or (:summary_plain state) "")
        lang        (or (:lang state) "en")
        domain-hint (or (:domain state) "")
        result      (or (call-triage-llm summary lang domain-hint)
                        (fallback-triage domain-hint))]
    (cond-> {:triage_result result}
      (and (not (seq (:domain state)))       (:domain result))       (assoc :domain (:domain result))
      (and (not (seq (:urgency state)))      (:urgency result))      (assoc :urgency (:urgency result))
      (and (not (seq (:jurisdiction state))) (:jurisdiction result)) (assoc :jurisdiction (:jurisdiction result)))))

(defn summarize-node
  "Encrypt the plaintext summary with the signal:v1: prefix (ADR-0018 Stage 1)."
  [state]
  (let [plain (or (:summary_plain state) "")]
    (if-not (seq plain)
      {}
      (let [triage     (or (:triage_result state) {})
            summary-en (or (:summary_en triage) (clip plain 200))]
        {:summary_cipher (signal-v1-encrypt summary-en)}))))

(defn search-node
  "Search bengoshi.etzhayyim.com for verified lawyers matching jurisdiction +
  specialization."
  [state]
  (let [jurisdiction    (or (:jurisdiction state) "IND")
        triage          (or (:triage_result state) {})
        specializations (or (:specializations triage) [])
        specialization  (first specializations)
        params          (cond-> {"jurisdiction" jurisdiction
                                 "limit" "10"
                                 "offset" "0"}
                          (seq specialization) (assoc "specialization" specialization))]
    (try
      (let [resp    (http-get (str (:bengoshi-url *config*) "/xrpc/com.etzhayyim.apps.bengoshi.searchLawyers")
                                params)
            lawyers (or (:lawyers resp) [])]
        {:lawyers (vec (take (:invite-limit *config*) lawyers))})
      (catch Exception e
        (binding [*out* *err*]
          (println "[search-node] bengoshi search failed:" (.getMessage e)))
        {:lawyers []}))))

(defn- expires-at []
  (let [inst (.plus (java.time.Instant/now)
                    (java.time.Duration/ofDays (:invite-expires-days *config*)))]
    (-> (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss'Z'")
        (.withZone (java.time.ZoneOffset/UTC))
        (.format inst))))

(defn match-node
  "Send inviteExternalCounsel for the top matched lawyers."
  [state]
  (let [lawyers  (or (:lawyers state) [])
        case-did (or (:case_did state) "")]
    (if (or (empty? lawyers) (not (seq case-did)))
      (do (binding [*out* *err*]
            (println (str "[match-node] skip — no lawyers (" (count lawyers) ") or no case_did")))
          {:grants []})
      (let [exp    (expires-at)
            grants (reduce
                     (fn [acc lawyer]
                       (let [grantee-did (or (:did lawyer) "")]
                         (if-not (seq grantee-did)
                           acc
                           (try
                             (let [resp (http-post
                                          (str (:dispatcher-url *config*) "/xrpc/com.etzhayyim.apps.lawfirm.inviteExternalCounsel")
                                          {:matterDid case-did
                                           :granteeDid grantee-did
                                           :granteeHandle (or (:fullName lawyer) "")
                                           :role "advisory"
                                           :capabilities ["read" "comment" "propose"]
                                           :expiresAt exp
                                           :message "Intake case requires legal consultation. Please review."}
                                          {:headers (internal-headers)})]
                               (conj acc {:granteeDid grantee-did
                                          :grantDid (:grantDid resp)
                                          :grantUri (:grantUri resp)
                                          :conflictCheckPassed (get resp :conflictCheckPassed true)}))
                             (catch Exception e
                               (binding [*out* *err*]
                                 (println (str "[match-node] invite failed for " grantee-did ": " (.getMessage e))))
                               acc)))))
                     []
                     (take (:invite-limit *config*) lawyers))]
        {:grants grants}))))
