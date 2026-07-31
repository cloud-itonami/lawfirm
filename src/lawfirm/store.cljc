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

    recipient    — 送達先. The addresses a matter's documents may travel to,
                   registered ahead of time so a destination is *chosen from
                   the record* rather than typed at send time. `:channels`
                   maps a channel to its coordinate and the 弁護士 who
                   verified it. See `lawfirm.transmission`.
                   {:recipient-id :matter-id :name :role :channels
                    {:fax {:number :verified-by :verified-on}
                     :post {:address :verified-by :verified-on}
                     :email {:address :verified-by :verified-on}}}

    transmission — 送達記録. One document leaving (or arriving at) the
                   practice by one channel, on one date, to one registered
                   recipient. Outbound rows are written only through the
                   governor gate; inbound rows carry no prose, only a
                   caller-supplied digest.
                   {:transmission-id :matter-id :direction #{:outbound
                    :inbound} :channel :doc-id :recipient-id :sent-on
                    :page-count :result :confirmation :digest}

    qa-question  — 依頼者・相談者からの質問. Like an intake description, the
                   prose never lands here — only the classification and a
                   caller-supplied digest. `lawfirm.qa`.
                   {:question-id :matter-id :client-id :asked-on :channel
                    :digest :domain :kind #{:legal-advice
                    :general-information} :answered?}

    qa-answer    — its answer. Advances draft → lawyer-reviewed → sent on
                   the same ladder as a 書面, for the same reason: an answer
                   to a legal question *is* 法律事務.
                   {:answer-id :question-id :matter-id :drafted-by :status
                    #{:draft :lawyer-reviewed :sent} :reviewed-by
                    :reviewed-on :sent-on :digest}

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
  (recipient [s recipient-id])
  (recipients-of [s matter-id])
  (transmissions-of [s matter-id])
  (qa-question [s question-id])
  (qa-questions-of [s matter-id])
  (qa-answer [s answer-id])
  (qa-answers-for [s question-id])
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
  (register-recipient! [s r])
  (register-transmission! [s t])
  (register-qa-question! [s q])
  (register-qa-answer! [s a])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(def empty-db
  "The shape a store starts from. Public because a durable implementation has
  to be able to rebuild it from a snapshot, and a snapshot missing a key must
  come back as an empty collection rather than `nil` — a `nil` where a vector
  belongs turns a missing key into a crash three namespaces away."
  {:bengoshi {} :partner-firms {} :clients {} :matters {}
   :conflict-checks {} :deadlines [] :time-entries [] :trust-entries []
   :invoices {} :work-products {} :counsel-grants {}
   :recipients {} :transmissions [] :qa-questions {} :qa-answers {}
   :records [] :ledger []})

;; ---------------------------------------------------------------------------
;; Pure db operations
;;
;; Both implementations below delegate to these. Two records that each carried
;; their own copy of `conflict-check-of`'s "latest by decided-on" rule is the
;; same defect class the fleet hit with the governor verdict layer
;; (ADR-2607309100): identical logic in two places, one of which eventually
;; drifts. Here the duplication would be worse than in the fleet, because the
;; two copies would be the in-memory store the tests run on and the durable
;; store a practice runs on.
;; ---------------------------------------------------------------------------

(defn- by-matter [xs matter-id] (filterv #(= matter-id (:matter-id %)) (or xs [])))

(defn- upsert-by
  "Replace any element with the same `k`, then append. For records whose state
  changes rather than events that accumulate."
  [xs k x]
  (conj (filterv #(not= (get x k) (get % k)) (or xs [])) x))

(defn db-read [db k id] (get-in db [k id]))

(defn db-conflict-check-of [db matter-id]
  (->> (vals (:conflict-checks db))
       (filter #(= matter-id (:matter-id %)))
       (sort-by :decided-on)
       last))

(defn db-work-products-of [db matter-id]
  (vec (sort-by :doc-id (by-matter (vals (:work-products db)) matter-id))))

(defn db-recipients-of [db matter-id]
  (vec (sort-by :recipient-id (by-matter (vals (:recipients db)) matter-id))))

(defn db-transmissions-of [db matter-id]
  (->> (by-matter (:transmissions db) matter-id)
       (sort-by (juxt :sent-on :transmission-id))
       vec))

(defn db-qa-questions-of [db matter-id]
  (vec (sort-by :question-id (by-matter (vals (:qa-questions db)) matter-id))))

(defn db-qa-answers-for [db question-id]
  (->> (vals (:qa-answers db))
       (filterv #(= question-id (:question-id %)))
       (sort-by :answer-id)
       vec))

(def ^:private keyed-writes
  "`register-*!` targets that upsert into a map, as {op [db-key id-key]}."
  {:bengoshi [:bengoshi :bengoshi-id]
   :partner-firm [:partner-firms :firm-id]
   :client [:clients :client-id]
   :matter [:matters :matter-id]
   :conflict-check [:conflict-checks :check-id]
   :invoice [:invoices :invoice-id]
   :work-product [:work-products :doc-id]
   :counsel-grant [:counsel-grants :grant-id]
   :recipient [:recipients :recipient-id]
   :qa-question [:qa-questions :question-id]
   :qa-answer [:qa-answers :answer-id]})

(defn db-put
  "Upsert `x` as `kind`. The id key comes from `keyed-writes`, so a new entity
  is one line there rather than a new method body in every implementation."
  [db kind x]
  (let [[dbk idk] (get keyed-writes kind)]
    (assoc-in db [dbk (get x idk)] x)))

(defn db-append [db dbk x] (update db dbk (fnil conj []) x))

;; A 期限 is a record whose state changes (satisfied? flips when the filing
;; goes in), not an event. Appending a satisfied duplicate would leave the
;; unsatisfied original in place and `deadline/breached-critical` would keep
;; reporting a breach that was already cured.
(defn db-put-deadline [db d] (update db :deadlines upsert-by :deadline-id d))

;; A 送達 is an event and accumulates — the same document may lawfully go to
;; the court, the opposing counsel and the client, and each of those is a
;; separate fact with its own date and result.
(defn db-append-transmission [db t] (db-append db :transmissions t))

;; ---------------------------------------------------------------------------
;; Implementations
;; ---------------------------------------------------------------------------

(defrecord MemStore [a]
  Store
  (bengoshi [_ id] (db-read @a :bengoshi id))
  (all-bengoshi [_] (vals (:bengoshi @a)))
  (partner-firm [_ id] (db-read @a :partner-firms id))
  (client [_ id] (db-read @a :clients id))
  (matter [_ id] (db-read @a :matters id))
  (matters [_] (vals (:matters @a)))
  (conflict-check [_ id] (db-read @a :conflict-checks id))
  (conflict-check-of [_ matter-id] (db-conflict-check-of @a matter-id))
  (deadlines-of [_ matter-id] (by-matter (:deadlines @a) matter-id))
  (time-entries-of [_ matter-id] (by-matter (:time-entries @a) matter-id))
  (trust-entries-of [_ matter-id] (by-matter (:trust-entries @a) matter-id))
  (invoice [_ id] (db-read @a :invoices id))
  (work-product [_ id] (db-read @a :work-products id))
  (work-products-of [_ matter-id] (db-work-products-of @a matter-id))
  (counsel-grant [_ id] (db-read @a :counsel-grants id))
  (grants-of [_ matter-id] (by-matter (vals (:counsel-grants @a)) matter-id))
  (recipient [_ id] (db-read @a :recipients id))
  (recipients-of [_ matter-id] (db-recipients-of @a matter-id))
  (transmissions-of [_ matter-id] (db-transmissions-of @a matter-id))
  (qa-question [_ id] (db-read @a :qa-questions id))
  (qa-questions-of [_ matter-id] (db-qa-questions-of @a matter-id))
  (qa-answer [_ id] (db-read @a :qa-answers id))
  (qa-answers-for [_ question-id] (db-qa-answers-for @a question-id))
  (records-of [_ client-id]
    (filterv #(= client-id (:client-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-bengoshi! [s b] (swap! a db-put :bengoshi b) s)
  (register-partner-firm! [s f] (swap! a db-put :partner-firm f) s)
  (register-client! [s c] (swap! a db-put :client c) s)
  (register-matter! [s m] (swap! a db-put :matter m) s)
  (register-conflict-check! [s c] (swap! a db-put :conflict-check c) s)
  (register-deadline! [s d] (swap! a db-put-deadline d) s)
  (register-time-entry! [s e] (swap! a db-append :time-entries e) s)
  (register-trust-entry! [s e] (swap! a db-append :trust-entries e) s)
  (register-invoice! [s i] (swap! a db-put :invoice i) s)
  (register-work-product! [s w] (swap! a db-put :work-product w) s)
  (register-counsel-grant! [s g] (swap! a db-put :counsel-grant g) s)
  (register-recipient! [s r] (swap! a db-put :recipient r) s)
  (register-transmission! [s t] (swap! a db-append-transmission t) s)
  (register-qa-question! [s q] (swap! a db-put :qa-question q) s)
  (register-qa-answer! [s ans] (swap! a db-put :qa-answer ans) s)
  (commit-record! [s record] (swap! a db-append :records record) s)
  (append-ledger! [s fact] (swap! a db-append :ledger fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge empty-db seed)))))

;; ---------------------------------------------------------------------------
;; Durable store
;; ---------------------------------------------------------------------------

(def version-key
  "Monotonic counter incremented on every accepted write. It exists so a host
  can reject an out-of-order snapshot; see `durable-store`."
  ::version)

(defn snapshot->db
  "Rebuild a db from a persisted snapshot. Missing keys come back as the empty
  collection they should be — a snapshot written by an older version of this
  namespace must not turn into `nil`s that crash a reader elsewhere."
  [snapshot]
  (merge empty-db (or snapshot {})))

(defn- commit!
  "Apply pure `f`, publish, then persist — in that order, once per accepted
  transition.

  Persisting *before* the CAS would be worse, not better: two writers would
  each persist their own next-state and whichever CAS lost would already have
  written a state the process never adopted. Publishing first means the atom
  is the in-process authority and `persist!` is told exactly what won.

  Two accepted transitions can still reach a slow host out of order, so every
  db carries `version-key` and the host is expected to drop a snapshot whose
  version it has already passed. That check belongs to the host because only
  the host knows what it already wrote."
  [a persist! f]
  (loop []
    (let [cur @a
          next (update (f cur) version-key (fnil inc 0))]
      (if (compare-and-set! a cur next)
        (do (when persist! (persist! next)) next)
        (recur)))))

(defrecord DurableStore [a persist!]
  Store
  (bengoshi [_ id] (db-read @a :bengoshi id))
  (all-bengoshi [_] (vals (:bengoshi @a)))
  (partner-firm [_ id] (db-read @a :partner-firms id))
  (client [_ id] (db-read @a :clients id))
  (matter [_ id] (db-read @a :matters id))
  (matters [_] (vals (:matters @a)))
  (conflict-check [_ id] (db-read @a :conflict-checks id))
  (conflict-check-of [_ matter-id] (db-conflict-check-of @a matter-id))
  (deadlines-of [_ matter-id] (by-matter (:deadlines @a) matter-id))
  (time-entries-of [_ matter-id] (by-matter (:time-entries @a) matter-id))
  (trust-entries-of [_ matter-id] (by-matter (:trust-entries @a) matter-id))
  (invoice [_ id] (db-read @a :invoices id))
  (work-product [_ id] (db-read @a :work-products id))
  (work-products-of [_ matter-id] (db-work-products-of @a matter-id))
  (counsel-grant [_ id] (db-read @a :counsel-grants id))
  (grants-of [_ matter-id] (by-matter (vals (:counsel-grants @a)) matter-id))
  (recipient [_ id] (db-read @a :recipients id))
  (recipients-of [_ matter-id] (db-recipients-of @a matter-id))
  (transmissions-of [_ matter-id] (db-transmissions-of @a matter-id))
  (qa-question [_ id] (db-read @a :qa-questions id))
  (qa-questions-of [_ matter-id] (db-qa-questions-of @a matter-id))
  (qa-answer [_ id] (db-read @a :qa-answers id))
  (qa-answers-for [_ question-id] (db-qa-answers-for @a question-id))
  (records-of [_ client-id]
    (filterv #(= client-id (:client-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-bengoshi! [s b] (commit! a persist! #(db-put % :bengoshi b)) s)
  (register-partner-firm! [s f] (commit! a persist! #(db-put % :partner-firm f)) s)
  (register-client! [s c] (commit! a persist! #(db-put % :client c)) s)
  (register-matter! [s m] (commit! a persist! #(db-put % :matter m)) s)
  (register-conflict-check! [s c] (commit! a persist! #(db-put % :conflict-check c)) s)
  (register-deadline! [s d] (commit! a persist! #(db-put-deadline % d)) s)
  (register-time-entry! [s e] (commit! a persist! #(db-append % :time-entries e)) s)
  (register-trust-entry! [s e] (commit! a persist! #(db-append % :trust-entries e)) s)
  (register-invoice! [s i] (commit! a persist! #(db-put % :invoice i)) s)
  (register-work-product! [s w] (commit! a persist! #(db-put % :work-product w)) s)
  (register-counsel-grant! [s g] (commit! a persist! #(db-put % :counsel-grant g)) s)
  (register-recipient! [s r] (commit! a persist! #(db-put % :recipient r)) s)
  (register-transmission! [s t] (commit! a persist! #(db-append-transmission % t)) s)
  (register-qa-question! [s q] (commit! a persist! #(db-put % :qa-question q)) s)
  (register-qa-answer! [s ans] (commit! a persist! #(db-put % :qa-answer ans)) s)
  (commit-record! [s record] (commit! a persist! #(db-append % :records record)) s)
  (append-ledger! [s fact] (commit! a persist! #(db-append % :ledger fact)) s))

(defn durable-store
  "A `Store` that hands every accepted write to `:persist!`.

  `:snapshot` is a previously persisted db (see `snapshot->db`); `:persist!`
  is `(fn [db] ...)` and is the **only** place this namespace touches a host.
  There is no `slurp`/`spit` here and there will not be: this file is `.cljc`
  and a record layer that reached for a filesystem would stop running in the
  runtimes above the JVM in the workspace's priority order (CLAUDE.md).

  What this does *not* do is pretend to be a database. `persist!` receives the
  whole db, so a practice with a large 一件記録 pays a full write per
  operation. That is a deliberate first implementation: it is correct, it has
  no partial-write state, and the protocol boundary means replacing it with a
  real entity store later changes nothing above `lawfirm.store`."
  [{:keys [snapshot persist!]}]
  (->DurableStore (atom (snapshot->db snapshot)) persist!))

(defn snapshot
  "The db behind a store, ready to persist. Works for either implementation —
  a caller that wants to checkpoint a `mem-store` should not have to know it
  is not the durable one."
  [store]
  (some-> store :a deref))

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
