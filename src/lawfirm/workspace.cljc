(ns lawfirm.workspace
  "The seam between the practice record and a host's workspace — drive,
  calendar, inbox, fax gateway.

  The protocols live here rather than in each host for the reason
  `drive.object` gives for owning its permission boundary: the rules only work
  if there is one of them. Two hosts that each decided for themselves what an
  'arrival' is would give two answers to *did this fax reach the practice*.

  ## Direction

  - **In**: arrivals become `lawfirm.actor` **requests**. This namespace never
    writes to the store. An inbox message is an assertion by a mail server;
    it becomes a fact about the practice only by going through the gate like
    everything else.
  - **Out**: a host publishes a `lawfirm.projection` value. Projections are
    read-only by construction (see that namespace), so publishing cannot move
    a 期限.

  ## Why `dispatch-plan` re-reads the recipient

  `lawfirm.transmission` makes the destination a registered record so it
  cannot be typed at send time. That property is worth nothing if the number
  handed to the fax gateway comes from the request instead of the record — the
  gate would be checking one number while the modem dialled another. So the
  plan a host executes is built here, from the store, after the commit, and
  the caller supplies only the transmission id."
  (:require [lawfirm.intake :as intake]
            [lawfirm.projection :as projection]
            [lawfirm.store :as store]
            [lawfirm.transmission :as transmission]))

;; ---------------------------------------------------------------------------
;; Ports — implemented by the host, never by this repository
;; ---------------------------------------------------------------------------

(defprotocol InboundPort
  (-arrivals [port since]
    "Items that arrived at or after `since`, as maps carrying at least
     `:id`, `:channel`, `:received-on`, `:origin`, `:digest` and optionally
     `:page-count` and `:matter-id`. A port that cannot supply a digest
     supplies nil — never the body."))

(defprotocol DrivePort
  (-publish-tree [port matter-id tree]
    "Reconcile the host drive with a `drive.model` tree."))

(defprotocol CalendarPort
  (-publish-calendar [port calendar]
    "Reconcile the host calendar with a `calendar.model` calendar. Events
     carry `:calendar/all-day? true` and date strings — a host that converts
     them to instants moves deadlines across a date boundary."))

(defprotocol DispatchPort
  (-dispatch [port plan]
    "Execute a `dispatch-plan`. Called by the host **after** the practice
     committed the 送達 through the governor; this repository never calls it."))

;; ---------------------------------------------------------------------------
;; In — arrivals become requests
;; ---------------------------------------------------------------------------

(defn arrival->request
  "One arrival as a `lawfirm.actor` request for `:record-inbound-transmission`.

  A request, not a record: an arrival is what a mail server or a fax gateway
  says happened, and the practice's record of it is created by the gate. The
  distinction matters the first time a gateway replays its queue."
  [{:keys [id channel received-on origin page-count digest matter-id]}
   {:keys [bengoshi-id client-id]}]
  {:op :record-inbound-transmission
   :bengoshi-id bengoshi-id
   :client-id client-id
   :matter-id matter-id
   :transmission (transmission/inbound-record
                  {:transmission-id (str "IN-" id)
                   :matter-id matter-id
                   :channel channel
                   :received-on received-on
                   :origin origin
                   :page-count page-count
                   :digest digest})})

(defn inbound-requests
  "Every arrival since `since`, as requests ready to run through the actor."
  [port since opts]
  (mapv #(arrival->request % opts) (-arrivals port since)))

(defn arrival->consult
  "An arrival that is not about an existing matter, triaged into a
  `lawfirm.intake` consult record. `classify-opts` is passed straight to
  `intake/classify`, so a host with no model configured gets the
  deterministic keyword classifier and an honest low confidence.

  `text` is read here and **not returned**: the classification and the
  caller's digest are what survive the call, which is the same promise
  `lawfirm.intake` makes and the only version of it that is true."
  [{:keys [id channel received-on digest]} text classify-opts]
  (let [triage (intake/classify text classify-opts)]
    (intake/->consult-record
     {:consult-id (str "CONSULT-" id)
      :received-on received-on
      :channel channel
      :digest digest
      :triage triage})))

;; ---------------------------------------------------------------------------
;; Out — the host publishes a projection
;; ---------------------------------------------------------------------------

(defn publish-docket!
  "Push the whole docket to a calendar port. Thin on purpose: the value is
  that the calendar id and the event id scheme are `lawfirm.projection`'s,
  so two hosts publishing the same practice converge instead of each creating
  its own duplicate of every 期限."
  [port store today]
  (-publish-calendar port (projection/deadline-calendar store today)))

(defn publish-matter-drive!
  "Push a matter's 一件記録 tree to a drive port."
  [port store matter-id]
  (-publish-tree port matter-id (projection/matter-drive store matter-id)))

(defn dispatch-plan
  "What a host needs in order to actually send a committed 送達.

  Returns `{:ok? false :reason ...}` rather than a partial plan when the
  record does not support one — a host that received a plan with a nil
  coordinate would either fail confusingly or, worse, fall back to something
  it had lying around."
  [store transmission-id]
  (let [t (->> (mapcat #(store/transmissions-of store (:matter-id %))
                       (store/matters store))
               (filter #(= transmission-id (:transmission-id %)))
               first)
        m (when t (store/matter store (:matter-id t)))
        r (when t (store/recipient store (:recipient-id t)))
        wp (when t (store/work-product store (:doc-id t)))
        coord (when r (transmission/coordinate r (:channel t)))]
    (cond
      (nil? t) {:ok? false :reason :unknown-transmission}
      (not= :outbound (:direction t)) {:ok? false :reason :not-outbound}
      (nil? r) {:ok? false :reason :recipient-not-registered}
      (nil? coord) {:ok? false :reason :channel-unregistered}
      (not= :issued (:status wp)) {:ok? false :reason :work-product-not-issued}
      :else
      {:ok? true
       :transmission-id transmission-id
       :matter-id (:matter-id t)
       ;; Provenance travels with the plan.
       ;;
       ;; A host has to run the outcome back through the gate as
       ;; `:confirm-transmission`, and every op needs a registered client and
       ;; an acting 弁護士 — so without these a host either re-derives them
       ;; (and two hosts derive them differently) or omits them and every
       ;; confirmation is held for `:no-client`. The acting counsel is the
       ;; matter's, because the outcome belongs to the operation the same
       ;; 弁護士 approved.
       :client-id (:client-id m)
       :bengoshi-id (:bengoshi-id m)
       :channel (:channel t)
       ;; From the record, never from the caller. This is the whole point.
       :recipient {:recipient-id (:recipient-id r)
                   :name (:name r)
                   :role (:role r)
                   :coordinate coord}
       :document {:doc-id (:doc-id wp)
                  :kind (:kind wp)
                  :object-ref (:object-ref wp)}})))
