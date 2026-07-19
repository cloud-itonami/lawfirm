(ns lg-lawfirm-intake.server
  "lg-lawfirm-intake dispatch surface — clj port of `lg/lg_lawfirm_intake/server.py`
  (ADR-2606280030).

  The Python file is a FastAPI app exposing:
    GET  /health /ok                                   → liveness
    POST /runs                                         → invoke graph synchronously
    POST /xrpc/com.etzhayyim.apps.lawfirm.triageIntake → main XRPC entry

  This namespace ports the ROUTING + auth + invoke/serialize logic as plain clj
  functions (`health`, `dispatch-run`, `dispatch-xrpc`, `enforce-auth`). The
  concrete HTTP transport is `org.httpkit.server` (FastAPI/uvicorn → http-kit):
  `ring-handler` is portable and `start-server!` accepts a host capability.

  The Python FastAPI server (`lg/`) remains the deployed runtime and COEXISTS —
  this twin is additive (ADR-2606280030)."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [langgraph.graph :as g]
            [lg-lawfirm-intake.graph :as graph]))

(def nsid-triage "com.etzhayyim.apps.lawfirm.triageIntake")

(def ^:dynamic *internal-secret* "")

(defn expected-secret
  "The configured DISPATCHER_INTERNAL_SECRET (empty/nil when unset). Extracted as
  a fn so tests can rebind it via with-redefs."
  []
  *internal-secret*)

(defn enforce-auth
  "Mirrors server._enforce_auth: if DISPATCHER_INTERNAL_SECRET is set, the
  x-internal-trust header must match. `exempt` (e.g. x-cron=1) skips the check.
  Returns nil when authorized, or a {:status 401 :body ..} map when not."
  ([x-internal-trust] (enforce-auth x-internal-trust false))
  ([x-internal-trust exempt]
   (when-not exempt
     (let [expected (expected-secret)]
       (when (and (seq expected) (not= x-internal-trust expected))
         {:status 401 :body {:detail "x-internal-trust mismatch"}})))))

(defn health
  "GET /ok | /health → {:ok true ...} (parity with server._health)."
  []
  {:status 200
   :body {:ok true
          :app "lg-lawfirm-intake"
          :ts (System/currentTimeMillis)
          :graph "lawfirm_intake"}})

(defn- run-graph [input]
  (let [t0 (System/currentTimeMillis)]
    (try
      (let [result (g/invoke graph/GRAPH (or input {}))]
        {:ok true :result result :duration-ms (- (System/currentTimeMillis) t0)})
      (catch Exception e
        {:error (let [m (str (.getMessage e))] (subs m 0 (min 300 (count m))))}))))

(defn dispatch-run
  "POST /runs body → {:status :body}. Enforces x-internal-trust unless x-cron=1."
  ([body] (dispatch-run body {}))
  ([body {:keys [x-internal-trust x-cron]}]
   (or (enforce-auth x-internal-trust (= x-cron "1"))
       (let [{:keys [ok result duration-ms error]} (run-graph (or (:input body) {}))]
         (if ok
           {:status 200 :body {:ok true :graph "lawfirm_intake"
                               :duration_ms duration-ms :result result}}
           {:status 500 :body {:detail error}})))))

(defn dispatch-triage-intake
  "POST /xrpc/com.etzhayyim.apps.lawfirm.triageIntake body → {:status :body}.
  Validates summary_plain (required), builds the graph input, invokes, and
  projects the response shape (parity with server._xrpc_triage_intake)."
  ([body] (dispatch-triage-intake body {}))
  ([body {:keys [x-internal-trust]}]
   (or (enforce-auth x-internal-trust)
       (let [case-did (str/trim (str (or (:case_did body) "")))
             summary  (str/trim (str (or (:summary_plain body) "")))]
         (if-not (seq summary)
           {:status 400 :body {:detail "summary_plain required"}}
           (let [inp {:case_id (str (or (:case_id body) ""))
                      :case_did case-did
                      :lang (str (or (:lang body) "en"))
                      :domain (str (or (:domain body) ""))
                      :state (str (or (:state body) ""))
                      :urgency (str (or (:urgency body) ""))
                      :jurisdiction (str (or (:jurisdiction body) ""))
                      :owner_did (str (or (:owner_did body) ""))
                      :actor_did (str (or (:actor_did body) ""))
                      :summary_plain summary}
                 {:keys [ok result duration-ms error]} (run-graph inp)]
             (if-not ok
               {:status 500 :body {:detail error}}
               {:status 200
                :body {:ok true
                       :duration_ms duration-ms
                       :case_id (:case_id result)
                       :case_did (:case_did result)
                       :domain (:domain result)
                       :urgency (:urgency result)
                       :jurisdiction (:jurisdiction result)
                       :summary_cipher (:summary_cipher result)
                       :triage_result (:triage_result result)
                       :lawyers_found (count (or (:lawyers result) []))
                       :grants (or (:grants result) [])}})))))))

;; ── HTTP transport (org.httpkit.server) ─────────────────────────────────────

(defn- json-response [{:keys [status body]}]
  {:status (or status 200)
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string body)})

(defn ring-handler
  "Ring handler routing the four surfaces onto the dispatch fns above."
  [{:keys [request-method uri headers body]}]
  (let [hdr     (fn [k] (get headers k))
        read-body (fn [] (when body (json/parse-string (slurp body) true)))]
    (json-response
      (cond
        (and (= request-method :get) (#{"/health" "/ok"} uri))
        (health)

        (and (= request-method :post) (= uri "/runs"))
        (dispatch-run (or (read-body) {})
                      {:x-internal-trust (hdr "x-internal-trust") :x-cron (hdr "x-cron")})

        (and (= request-method :post) (= uri (str "/xrpc/" nsid-triage)))
        (dispatch-triage-intake (or (read-body) {})
                                {:x-internal-trust (hdr "x-internal-trust")})

        :else {:status 404 :body {:detail "not found"}}))))

(defn start-server!
  "Boot through an explicit host-provided server capability."
  [run-server port]
  (when-not (fn? run-server)
    (throw (ex-info "Lawfirm server requires an explicit run-server capability"
                    {:capability :lawfirm/run-server})))
  (run-server ring-handler {:port port}))
