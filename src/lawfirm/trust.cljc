(ns lawfirm.trust
  "預り金の分別管理 — client trust accounting (日弁連「預り金等の取扱いに
  関する規程」).

  Money a client hands the practice is the client's money until a specific,
  documented event makes it the firm's. The three failures this namespace
  exists to make unrepresentable, in the order they actually destroy
  practices:

    1. **混同** — trust money sitting in the same pot as fee income. Every
       entry names an `:account` (`:trust` | `:operating`); there is no
       default and no unaccounted entry shape.
    2. **流用** — paying one client's obligation out of another client's
       balance. Balances are computed *per matter*, so a firm-wide surplus
       can never mask a per-matter overdraft.
    3. **無断充当** — appropriating trust money to fees without an issued
       invoice. `appropriation-basis` returns the invoice or a reason it is
       refused; `lawfirm.governor` turns a refusal into a HARD hold.

  Amounts are integers in the currency's minor unit (JPY has none, so 円 =
  1). Floating point is never used for money."
  (:require [lawfirm.store :as store]))

(defn- amount-of [e]
  (let [a (:amount e)]
    (if (number? a) a 0)))

(defn- signed [e]
  (case (:direction e)
    :in (amount-of e)
    :out (- (amount-of e))
    0))

(defn balance
  "Balance held on `account` for `matter-id`, in minor units.
  `account` defaults to `:trust` — the only balance with a duty attached."
  ([store matter-id] (balance store matter-id :trust))
  ([store matter-id account]
   (->> (store/trust-entries-of store matter-id)
        (filter #(= account (:account %)))
        (map signed)
        (reduce + 0))))

(defn ledger-lines
  "Per-matter trust ledger with a running balance, oldest first — the shape a
  弁護士 has to be able to hand a client or a 弁護士会 on request."
  [store matter-id]
  (->> (store/trust-entries-of store matter-id)
       (filter #(= :trust (:account %)))
       (sort-by (juxt :date :entry-id))
       (reduce (fn [{:keys [running lines]} e]
                 (let [running (+ running (signed e))]
                   {:running running
                    :lines (conj lines (assoc e :running-balance running))}))
               {:running 0 :lines []})
       :lines))

(defn overdraft?
  "True when withdrawing `amount` from `matter-id`'s trust balance would take
  it below zero. A negative trust balance is not an accounting artefact; it
  means another client's money left the account."
  [store matter-id amount]
  (let [amount (if (number? amount) amount 0)]
    (> amount (balance store matter-id :trust))))

(defn commingled?
  "True when the entry does not name a valid account. Guards the record shape
  itself — an unaccounted movement is the precondition for 混同."
  [entry]
  (not (contains? #{:trust :operating} (:account entry))))

(defn appropriation-basis
  "Basis for moving `amount` out of trust to satisfy fees on `matter-id`.

  Returns `{:ok? true :invoice inv}` when an issued invoice for this matter
  covers the amount, otherwise `{:ok? false :reason kw :detail str}`. The
  practice may hold a client's money; it may not decide unilaterally that
  some of it has become income."
  [store matter-id invoice-id amount]
  (let [inv (when invoice-id (store/invoice store invoice-id))
        amount (if (number? amount) amount 0)]
    (cond
      (nil? inv)
      {:ok? false :reason :no-invoice
       :detail "預り金からの報酬充当には発行済みの請求書が必要（無断充当の禁止）"}

      (not= matter-id (:matter-id inv))
      {:ok? false :reason :invoice-wrong-matter
       :detail (str "請求書 " invoice-id " は事件 " (:matter-id inv) " のもの")}

      (not= :issued (:status inv))
      {:ok? false :reason :invoice-not-issued
       :detail (str "請求書 " invoice-id " が未発行（status " (pr-str (:status inv)) "）")}

      (> amount (or (:amount inv) 0))
      {:ok? false :reason :exceeds-invoice
       :detail (str "充当額 " amount " が請求額 " (:amount inv) " を超過")}

      :else {:ok? true :invoice inv})))

(defn disbursement-violations
  "Every trust rule a proposed movement breaks, as governor-shaped violation
  maps. Empty vector means the movement is representable — it does **not**
  mean it may happen: 出金 is always a human 弁護士 decision
  (`lawfirm.governor` escalates `:disburse-trust` unconditionally)."
  [store {:keys [matter-id amount account direction purpose invoice-id] :as entry}]
  (cond-> []
    (commingled? entry)
    (conj {:rule :trust-commingling
           :detail "預り金の入出金には :account (:trust | :operating) の明示が必須（分別管理）"})

    (and (= :out direction) (= :trust account) (overdraft? store matter-id amount))
    (conj {:rule :trust-overdraft
           :detail (str "出金額 " amount " が事件 " matter-id " の預り金残高 "
                        (balance store matter-id :trust)
                        " を超過（他依頼者の預り金の流用にあたる）")})

    (and (= :out direction) (= :trust account) (= :fee-appropriation purpose)
         (not (:ok? (appropriation-basis store matter-id invoice-id amount))))
    (conj {:rule :trust-appropriation-without-invoice
           :detail (:detail (appropriation-basis store matter-id invoice-id amount))})))
