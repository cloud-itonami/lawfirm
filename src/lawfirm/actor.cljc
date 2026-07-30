(ns lawfirm.actor
  "LawFirmActor — one 業務操作 as a `langgraph.graph/state-graph`.

  ```text
  :intake -> :advise -> :govern -> :decide -+-> :commit           (:ok?)
                                            +-> :request-approval (:escalate?, interrupt-before)
                                            +-> :hold             (:hard?)
  ```

  One run = one operation. No internal loop, checkpointed per superstep, so an
  escalated proposal parks as an `:interrupted` checkpoint until a named
  弁護士 resumes it — which is what `approve!` is: **the act of approval is
  the act of resuming the thread**, and the identity of the person who
  resumed is written to the ledger before the effect lands.

  Every path writes to the append-only ledger, including `:hold`. A practice
  that only records what it did cannot show a 弁護士会 what it refused to do."
  (:require [langgraph.checkpoint :as cp]
            [langgraph.graph :as g]
            [lawfirm.advisor :as advisor]
            [lawfirm.governor :as governor]
            [lawfirm.store :as store]))

;; ---------------------------------------------------------------------------
;; Effects — the only place the record changes, reached only through :commit
;; ---------------------------------------------------------------------------

(defn apply-effect!
  "Apply the committed operation to the record. Called only from the `:commit`
  node, i.e. only after the governor cleared the proposal (and, for escalated
  ops, only after a named 弁護士 resumed the thread).

  Returns the applied record. Unknown ops are recorded but change nothing —
  an op this function does not understand must not guess at a mutation."
  [store request proposal approval]
  (let [{:keys [op matter-id]} proposal
        base {:client-id (:client-id request)
              :bengoshi-id (:bengoshi-id request)
              :op op
              :matter-id matter-id
              :approved-by (:by approval)
              :approved-on (:on approval)
              :payload proposal}]
    (case op
      :run-conflict-check
      (store/register-conflict-check! store (:conflict-check proposal))

      :record-time-entry
      (store/register-time-entry! store (:time-entry proposal))

      :prepare-work-product
      (store/register-work-product!
       store (assoc (:work-product proposal) :status :draft))

      :review-work-product
      (when-let [wp (store/work-product store (:doc-id proposal))]
        (store/register-work-product!
         store (assoc wp :status :lawyer-reviewed
                      :reviewed-by (or (:reviewed-by proposal) (:bengoshi-id request))
                      :reviewed-on (:reviewed-on proposal))))

      :issue-work-product
      (when-let [wp (store/work-product store (:doc-id proposal))]
        (store/register-work-product! store (assoc wp :status :issued)))

      (:receive-trust :disburse-trust)
      (store/register-trust-entry!
       store (assoc (:trust-entry proposal) :approved-by (:by approval)))

      :issue-invoice
      (store/register-invoice! store (assoc (:invoice proposal) :status :issued))

      :accept-representation
      (when-let [m (store/matter store matter-id)]
        (store/register-matter! store (assoc m :status :open)))

      :withdraw-representation
      (when-let [m (store/matter store matter-id)]
        (store/register-matter! store (assoc m :status :withdrawn)))

      :invite-partner-counsel
      (store/register-counsel-grant! store (:grant proposal))

      :remediate-deadline
      (when-let [d (first (filter #(= (:deadline-id proposal) (:deadline-id %))
                                  (store/deadlines-of store matter-id)))]
        (store/register-deadline! store (assoc d :satisfied? true)))

      nil)
    (store/commit-record! store base)
    base))

;; ---------------------------------------------------------------------------
;; Graph
;; ---------------------------------------------------------------------------

(defn build-graph
  "Compile a LawFirmActor. `store` implements `lawfirm.store/Store`;
  `advisor` implements `lawfirm.advisor/Advisor` (defaults to the
  deterministic mock)."
  [{:keys [store advisor checkpointer]
    :or {advisor (advisor/mock-advisor)
         checkpointer (cp/mem-checkpointer)}}]
  (-> (g/state-graph
       {:channels {:request {:default nil}
                   :context {:default nil}
                   :approval {:default nil}
                   :proposal {:default nil}
                   :verdict {:default nil}
                   :disposition {:default nil}
                   :record {:default nil}
                   :audit {:reducer into :default []}}})
      (g/add-node :intake (fn [s] s))

      (g/add-node :advise
                  (fn [{:keys [request]}]
                    (let [p (advisor/-advise advisor store request)]
                      {:proposal p
                       :audit [{:node :advise :request request :proposal p}]})))

      (g/add-node :govern
                  (fn [{:keys [request context proposal]}]
                    (let [v (governor/check request context proposal store)]
                      {:verdict v
                       :audit [{:node :govern :verdict v}]})))

      (g/add-node :decide
                  (fn [{:keys [verdict]}]
                    {:disposition (cond
                                    (:hard? verdict) :hold
                                    (:escalate? verdict) :request-approval
                                    :else :commit)}))

      ;; interrupt-before parks the run here. Resuming IS the approval.
      (g/add-node :request-approval
                  (fn [{:keys [verdict] :as s}]
                    (store/append-ledger!
                     store {:disposition :approved
                            :escalation-reason (:escalation-reason verdict)
                            :approval (:approval s)})
                    {:audit [{:node :request-approval
                              :escalation-reason (:escalation-reason verdict)
                              :approval (:approval s)}]}))

      (g/add-node :commit
                  (fn [{:keys [request proposal approval]}]
                    (let [record (apply-effect! store request proposal approval)]
                      (store/append-ledger! store {:disposition :commit :record record})
                      {:record record
                       :audit [{:node :commit :record record}]})))

      (g/add-node :hold
                  (fn [{:keys [request proposal verdict]}]
                    (let [fact {:disposition :hold
                                :op (:op proposal)
                                :matter-id (:matter-id proposal)
                                :bengoshi-id (:bengoshi-id request)
                                :verdict verdict}]
                      (store/append-ledger! store fact)
                      {:audit [{:node :hold :verdict verdict}]})))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)
      (g/add-conditional-edges
       :decide
       (fn [{:keys [disposition]}]
         (case disposition
           :commit :commit
           :request-approval :request-approval
           :hold)))
      (g/add-edge :request-approval :commit)
      (g/set-finish-point :commit)
      (g/set-finish-point :hold)
      (g/compile-graph {:checkpointer checkpointer
                        :interrupt-before #{:request-approval}})))

(defn run-request!
  "Run one operation to completion or to the approval interrupt. `thread-id`
  scopes the checkpoint so an escalated run can be resumed later."
  [graph request context thread-id]
  (g/run* graph {:request request :context context} {:thread-id thread-id}))

(defn approve!
  "Human-in-the-loop resume. `approval` must name the 弁護士 who approved and
  the date — an anonymous approval is not one, for the same reason an unsigned
  利益相反 clearance is not one.

  Throws when the approver is missing, so the caller cannot resume a parked
  decision by accident."
  [graph thread-id {:keys [by on] :as approval}]
  (when-not (and by on)
    (throw (ex-info "承認には承認者（弁護士）と日付が必要"
                    {:thread-id thread-id :approval approval})))
  (g/run* graph {:approval approval} {:thread-id thread-id :resume? true}))

(defn escalated?
  "True when a run parked for 弁護士 sign-off rather than finishing."
  [result]
  (= :interrupted (:status result)))

(defn held?
  "True when the governor refused the proposal outright."
  [result]
  (= :hold (get-in result [:state :disposition])))

(defn committed?
  "True when the operation landed on the record.

  Distinct from `(= :commit (:disposition state))`: `:disposition` holds the
  *routing decision* made at `:decide`, and for an escalated op that decision
  stays `:request-approval` even after a 弁護士 resumes and the commit runs.
  The presence of `:record` is the fact that a write happened."
  [result]
  (some? (get-in result [:state :record])))
