(ns lawfirm.store
  "SSoT for the lawfirm practice OS — the record layer a licensed 弁護士
  actually runs a practice on (itonami actor pattern, ADR-2607011000 /
  CLAUDE.md Actors section). Modeled on cloud-itonami-isco-2611's
  `legalpractice.store`, widened from a single-operation demo to the
  entities a real 受任→終結 lifecycle needs.

  The software never practises law. It records, gates and audits what a
  named, bar-verified 弁護士 decides. Every entity below therefore carries
  the identity of the human who is accountable for it.

  Domain:

    bengoshi     — a natural person admitted to practise. Identity for the
                   弁護士法72条 boundary: only a `:status :active` bengoshi
                   with a verified registration may be the acting counsel on
                   any operation.
                   {:bengoshi-id :name :registration-number :bar-association
                    :admitted-on :status #{:active :suspended :withdrawn}
                    :verified-on :verification-source :specializations
                    :jurisdictions}

    partner-firm — an external firm this practice co-counsels with.
                   `:referral-fee-policy` must be `:none` — see
                   `lawfirm.partner`; 弁護士法72条 + 職務基本規程13条
                   (非弁提携の禁止) make a referral fee structurally
                   unrepresentable, not merely discouraged.
                   {:firm-id :name :jurisdiction :lead-bengoshi-id :status
                    :agreement-uri :referral-fee-policy}

    client       — 依頼者. `:conflict-parties` are the names this client is
                   adverse to or affiliated with, screened by
                   `lawfirm.conflict`.
                   {:client-id :name :kind #{:individual :corporate}
                    :conflict-parties}

    matter       — 事件. `:engagement` is the registered 受任範囲;
                   `:max-billable-hours` its arithmetic ceiling. A matter
                   without `:fee-agreement` (委任契約書, 職務基本規程30条)
                   cannot carry billable work.
                   {:matter-id :client-id :bengoshi-id :name :domain
                    :jurisdiction :engagement :max-billable-hours
                    :fee-agreement :status :adverse-parties :related-parties
                    :opened-on :court :case-number}

    conflict-check — 利益相反チェック (職務基本規程27・28条). A stored
                   clearance is a *human* decision with a name and a date;
                   `lawfirm.conflict/screen` re-runs the scan at proposal
                   time so a clearance cannot outlive the facts it was made
                   on.
                   {:check-id :matter-id :cleared? :screened-parties :hits
                    :decided-by :decided-on}

    deadline     — 期限 / 期日. `:critical?` marks the ones whose lapse is
                   弁護過誤 (出訴期限, 時効, 上訴期間).
                   {:deadline-id :matter-id :kind :due-on :critical?
                    :satisfied?}

    time-entry   — 時間記録. Sums against the matter's engagement ceiling.
                   {:entry-id :matter-id :bengoshi-id :hours :date
                    :narrative :billable?}

    trust-entry  — 預り金 (日弁連 預り金等の取扱いに関する規程). `:account`
                   is `:trust` or `:operating` and is never optional —
                   分別管理 is an invariant of the record shape, not a
                   convention.
                   {:entry-id :matter-id :client-id :direction #{:in :out}
                    :amount :currency :account #{:trust :operating}
                    :purpose :approved-by :invoice-id}

    invoice      — 報酬請求. Required before any 預り金→報酬 appropriation.
                   {:invoice-id :matter-id :client-id :amount :currency
                    :issued-on :status}

    work-product — 書面. `:status` advances draft → lawyer-reviewed →
                   issued. Only a verified bengoshi may review, and only a
                   reviewed document may be issued (法務省 2023 guideline:
                   弁護士が自ら精査し、必要に応じ自ら修正する).
                   {:doc-id :matter-id :kind :status #{:draft
                    :lawyer-reviewed :issued} :reviewed-by :reviewed-on}

    counsel-grant — 共同受任 / 外部カウンセル. Carries the capability set a
                   partner may exercise on this matter and the fee split for
                   work actually performed. `:referral-fee` must be absent
                   or zero.
                   {:grant-id :matter-id :grantee-bengoshi-id :role
                    :capabilities :expires-on :conflict-cleared? :fee-split
                    :referral-fee}

    record       — a committed operating record, written ONLY via
                   `commit-record!` (i.e. only through the governor gate).

    ledger       — append-only audit trail; every commit AND every hold."
  (:refer-clojure :exclude [remove]))

(defprotocol Store
  (bengoshi [s bengoshi-id])
  (all-bengoshi [s] "Every registered 弁護士 — partner matching scans the roster.")
  (partner-firm [s firm-id])
  (client [s client-id])
  (matter [s matter-id])
  (matters [s] "All registered matters — the conflict scan needs the whole book.")
  (conflict-check [s check-id])
  (conflict-check-of [s matter-id])
  (deadlines-of [s matter-id])
  (time-entries-of [s matter-id])
  (trust-entries-of [s matter-id])
  (invoice [s invoice-id])
  (work-product [s doc-id])
  (work-products-of [s matter-id])
  (counsel-grant [s grant-id])
  (grants-of [s matter-id])
  (records-of [s client-id])
  (ledger [s])
  (register-bengoshi! [s b])
  (register-partner-firm! [s f])
  (register-client! [s c])
  (register-matter! [s m])
  (register-conflict-check! [s c])
  (register-deadline! [s d])
  (register-time-entry! [s e])
  (register-trust-entry! [s e])
  (register-invoice! [s i])
  (register-work-product! [s w])
  (register-counsel-grant! [s g])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(def ^:private empty-db
  {:bengoshi {} :partner-firms {} :clients {} :matters {}
   :conflict-checks {} :deadlines [] :time-entries [] :trust-entries []
   :invoices {} :work-products {} :counsel-grants {}
   :records [] :ledger []})

(defrecord MemStore [a]
  Store
  (bengoshi [_ id] (get-in @a [:bengoshi id]))
  (all-bengoshi [_] (vals (:bengoshi @a)))
  (partner-firm [_ id] (get-in @a [:partner-firms id]))
  (client [_ id] (get-in @a [:clients id]))
  (matter [_ id] (get-in @a [:matters id]))
  (matters [_] (vals (:matters @a)))
  (conflict-check [_ id] (get-in @a [:conflict-checks id]))
  (conflict-check-of [_ matter-id]
    (->> (vals (:conflict-checks @a))
         (filter #(= matter-id (:matter-id %)))
         (sort-by :decided-on)
         last))
  (deadlines-of [_ matter-id]
    (filterv #(= matter-id (:matter-id %)) (:deadlines @a)))
  (time-entries-of [_ matter-id]
    (filterv #(= matter-id (:matter-id %)) (:time-entries @a)))
  (trust-entries-of [_ matter-id]
    (filterv #(= matter-id (:matter-id %)) (:trust-entries @a)))
  (invoice [_ id] (get-in @a [:invoices id]))
  (work-product [_ id] (get-in @a [:work-products id]))
  (work-products-of [_ matter-id]
    (->> (vals (:work-products @a))
         (filterv #(= matter-id (:matter-id %)))
         (sort-by :doc-id)
         vec))
  (counsel-grant [_ id] (get-in @a [:counsel-grants id]))
  (grants-of [_ matter-id]
    (filterv #(= matter-id (:matter-id %)) (vals (:counsel-grants @a))))
  (records-of [_ client-id]
    (filterv #(= client-id (:client-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-bengoshi! [s b] (swap! a assoc-in [:bengoshi (:bengoshi-id b)] b) s)
  (register-partner-firm! [s f] (swap! a assoc-in [:partner-firms (:firm-id f)] f) s)
  (register-client! [s c] (swap! a assoc-in [:clients (:client-id c)] c) s)
  (register-matter! [s m] (swap! a assoc-in [:matters (:matter-id m)] m) s)
  (register-conflict-check! [s c] (swap! a assoc-in [:conflict-checks (:check-id c)] c) s)
  ;; Upsert by id, unlike the time/trust logs below: a 期限 is a *record whose
  ;; state changes* (satisfied? flips when the filing goes in), not an event.
  ;; Appending a satisfied duplicate would leave the unsatisfied original in
  ;; place and `deadline/breached-critical` would keep reporting a breach that
  ;; was already cured.
  (register-deadline! [s d]
    (swap! a update :deadlines
           (fn [ds] (conj (filterv #(not= (:deadline-id d) (:deadline-id %)) (or ds []))
                          d)))
    s)
  (register-time-entry! [s e] (swap! a update :time-entries (fnil conj []) e) s)
  (register-trust-entry! [s e] (swap! a update :trust-entries (fnil conj []) e) s)
  (register-invoice! [s i] (swap! a assoc-in [:invoices (:invoice-id i)] i) s)
  (register-work-product! [s w] (swap! a assoc-in [:work-products (:doc-id w)] w) s)
  (register-counsel-grant! [s g] (swap! a assoc-in [:counsel-grants (:grant-id g)] g) s)
  (commit-record! [s record] (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact] (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge empty-db seed)))))

;; ---------------------------------------------------------------------------
;; Derived reads — pure over the Store protocol, shared by governor and console
;; so the number a lawyer sees on screen is the number the gate enforced.
;; ---------------------------------------------------------------------------

(defn billed-hours
  "Hours already recorded as billable against a matter."
  [store matter-id]
  (->> (time-entries-of store matter-id)
       (filter :billable?)
       (map (comp #(or % 0) :hours))
       (reduce + 0)))

(defn active-grant?
  "True when `grant` is unexpired as of `today` and carries `capability`."
  [grant capability today]
  (boolean
   (and grant
        (:conflict-cleared? grant)
        (contains? (set (:capabilities grant)) capability)
        (or (nil? (:expires-on grant))
            (neg? (compare today (:expires-on grant)))
            (zero? (compare today (:expires-on grant)))))))
