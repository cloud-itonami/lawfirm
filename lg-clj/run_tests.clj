;; lg-lawfirm-intake clj-port test runner (repo rule: run_tests.clj, NOT .sh).
;;
;;   bb run_tests.clj      (from 60-apps/etzhayyim-project-lawfirm/lg-clj/)
;;   bb test               (bb.edn task alias)
;;
;; Exits non-zero if any test fails or errors.
(ns lg-lawfirm-intake.host
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.test :as t]
            [org.httpkit.server :as httpkit]
            [lg-lawfirm-intake.nodes :as nodes]
            [lg-lawfirm-intake.server :as server]
            [lg-lawfirm-intake.smoke-test]))

(defn- env [name default] (or (System/getenv name) default))
(defn- parse-long [value] (Long/parseLong value))
(defn- parse-double [value] (Double/parseDouble value))

(def config
  {:llm-url (env "etzhayyim_LLM_URL" (:llm-url nodes/default-config))
   :llm-key (env "etzhayyim_LLM_API_KEY" "")
   :llm-model (env "LAWFIRM_LLM_MODEL"
                   (env "etzhayyim_LLM_MODEL" (:llm-model nodes/default-config)))
   :llm-timeout-sec (parse-double (env "LAWFIRM_LLM_TIMEOUT_SEC" "20"))
   :bengoshi-url (env "BENGOSHI_URL" (:bengoshi-url nodes/default-config))
   :dispatcher-url (env "DISPATCHER_URL" (:dispatcher-url nodes/default-config))
   :internal-secret (env "DISPATCHER_INTERNAL_SECRET" "")
   :invite-limit (parse-long (env "LAWFIRM_INVITE_LIMIT" "3"))
   :invite-expires-days (parse-long (env "LAWFIRM_INVITE_EXPIRES_DAYS" "90"))})

(defn http-get [url params]
  (-> (http/get url {:query-params (or params {}) :timeout 10000})
      :body (json/parse-string true)))

(defn http-post [url body {:keys [headers timeout]}]
  (-> (http/post url {:headers (merge {"Content-Type" "application/json"} (or headers {}))
                     :body (json/generate-string body)
                     :timeout (long (* 1000 (or timeout 15)))})
      :body (json/parse-string true)))

(defn triage-llm [summary lang domain-hint]
  (nodes/call-triage-llm-with http/post config summary lang domain-hint))

(defn with-capabilities [f]
  (binding [nodes/*config* config
            nodes/*http-get* http-get
            nodes/*http-post* http-post
            nodes/*call-triage-llm* triage-llm
            server/*internal-secret* (:internal-secret config)]
    (f)))

(defn handler [request] (with-capabilities #(server/ring-handler request)))

(defn start-server! [port]
  (server/start-server! (fn [_ options] (httpkit/run-server handler options)) port))

(let [{:keys [fail error]} (t/run-tests 'lg-lawfirm-intake.smoke-test)]
  (when (pos? (+ (or fail 0) (or error 0)))
    (System/exit 1)))
