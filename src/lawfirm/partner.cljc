(ns lawfirm.partner
  "提携弁護士ネットワーク — bar verification, co-counsel grants, and the
  recruitment funnel that keeps the network supplied.

  Two hard boundaries define everything here.

  **弁護士法72条** — only a 弁護士 may handle 法律事務 for reward. So the
  network's membership test is not 'signed up', it is `verified-active?`: a
  registration number, a named 弁護士会, an affirmative status, and a
  verification that is not stale. An unverified entry is a candidate, never a
  partner, and `lawfirm.governor` refuses to let one act.

  **弁護士職務基本規程13条** — 弁護士 may neither pay for a client referral
  (1項) nor accept payment for making one (2項). Both directions. This is why
  the network cannot be a marketplace: a marketplace's revenue *is* the
  referral fee. So `:referral-fee` is not a field with a policy attached — a
  non-zero value is a HARD violation that holds the operation, in either
  direction, in every jurisdiction, regardless of whether local law would
  permit it. `cloud-itonami-isic-6910-legalsupport` reached the same
  conclusion independently; this namespace is where it becomes enforceable
  code rather than a promise in a README.

  What the network *can* do is 共同受任: two 弁護士 who both actually work a
  matter split the fee in proportion to that work, recorded per participant
  with a role. The distinction between a fee split and a referral fee is
  whether the recipient did legal work. `fee-split-violations` enforces
  exactly that."
  (:require [clojure.set :as set]
            [lawfirm.conflict :as conflict]
            [lawfirm.date :as date]
            [lawfirm.store :as store]))

;; ---------------------------------------------------------------------------
;; Bar verification
;; ---------------------------------------------------------------------------

(def verification-validity-days
  "How long a bar-registration check stays good. 登録取消・業務停止 are
  published by 日弁連 but not pushed to us, so a year-old verification is an
  assertion about last year. Re-checking annually mirrors the 弁護士名簿
  update cadence."
  365)

(def verification-sources
  "Sources this practice accepts as evidence of admission. All are the
  registry itself or a 弁護士会 — never the candidate's own word."
  #{:nichibenren-directory   ; 日弁連 弁護士情報・法人情報検索
    :bar-association-direct  ; 所属弁護士会への直接照会
    :registration-certificate})

(defn verification-stale?
  "True when `bengoshi`'s verification is missing or older than the validity
  window as of `today`."
  [bengoshi today]
  (let [v (:verified-on bengoshi)]
    (or (not (date/valid? v))
        (not (date/valid? today))
        (> (or (date/days-between v today) 0) verification-validity-days))))

(defn verification-violations
  "Why `bengoshi` may not act, as governor-shaped violation maps. Empty means
  the person is on the 弁護士名簿, actively, on evidence we hold today."
  [bengoshi today]
  (cond-> []
    (nil? bengoshi)
    (conj {:rule :unregistered-counsel
           :detail "担当者が弁護士として登録されていない（弁護士法72条：非弁行為）"})

    (and bengoshi (not (seq (str (:registration-number bengoshi)))))
    (conj {:rule :no-registration-number
           :detail "登録番号が記録されていない"})

    (and bengoshi (not (seq (str (:bar-association bengoshi)))))
    (conj {:rule :no-bar-association
           :detail "所属弁護士会が記録されていない"})

    (and bengoshi (not= :active (:status bengoshi)))
    (conj {:rule :counsel-not-active
           :detail (str "弁護士の登録状態が " (pr-str (:status bengoshi))
                        "（active 以外は職務を行えない）")})

    (and bengoshi (not (contains? verification-sources (:verification-source bengoshi))))
    (conj {:rule :unaccepted-verification-source
           :detail (str "資格確認の出典 " (pr-str (:verification-source bengoshi))
                        " は受け入れ対象外（日弁連名簿・弁護士会照会・登録証明のみ）")})

    (and bengoshi (verification-stale? bengoshi today))
    (conj {:rule :verification-stale
           :detail (str "資格確認 " (pr-str (:verified-on bengoshi)) " が "
                        verification-validity-days "日を超えて未更新")})))

(defn verified-active?
  "True when nothing in `verification-violations` fires."
  [bengoshi today]
  (empty? (verification-violations bengoshi today)))

;; ---------------------------------------------------------------------------
;; Matching — who can take this matter
;; ---------------------------------------------------------------------------

(defn- relevance
  "Signal that this candidate is right for *this* matter: subject-matter
  expertise and jurisdiction. Zero means no reason to involve them."
  [{:keys [specializations jurisdictions]} {:keys [domain jurisdiction]}]
  (+ (if (contains? (set specializations) domain) 3 0)
     (if (contains? (set jurisdictions) jurisdiction) 2 0)))

(defn- score
  "Rank a candidate for a matter. Deliberately transparent arithmetic rather
  than a model: a 弁護士 asked why they were or were not matched deserves an
  answer they can check.

  Capacity is a **tiebreaker only** — it is added on top of relevance and can
  never create a match on its own (see `candidates`). Free calendar is not a
  qualification: sending a 労働 matter to an idle 刑事 specialist in another
  高裁管内 serves the practice's scheduling, not the client."
  [{:keys [capacity-hours] :as candidate} matter-spec]
  (+ (relevance candidate matter-spec)
     (min 2 (quot (or capacity-hours 0) 20))))

(defn candidates
  "Verified, active, non-conflicted partners who can take `matter-spec`,
  best-ranked first.

  Conflict screening runs for *each candidate* — a partner has their own book
  and can be conflicted where we are not. Candidates that screen dirty are
  dropped here rather than surfaced with a warning, because a 弁護士 cannot
  see a conflicted candidate's name without that itself being a disclosure
  problem."
  [store matter-spec today]
  (->> (store/all-bengoshi store)
       (filter #(verified-active? % today))
       (remove #(= (:bengoshi-id %) (:bengoshi-id matter-spec)))
       (filter (fn [b]
                 (:cleared? (conflict/screen store (assoc matter-spec :bengoshi-id (:bengoshi-id b))))))
       (filter #(pos? (relevance % matter-spec)))
       (map (fn [b] (assoc b :match-score (score b matter-spec))))
       (sort-by (juxt (comp - :match-score) :bengoshi-id))
       vec))

;; ---------------------------------------------------------------------------
;; Co-counsel grants
;; ---------------------------------------------------------------------------

(def grantable-capabilities
  "The capability vocabulary a grant may draw on. `:sign` and `:file` are
  present but are never exercised by the actor — they record that a human
  co-counsel is authorised to do those things themselves."
  #{:read :comment :upload-document :draft :propose :sign :file :appear})

(defn- fee-amount [x] (if (number? x) x 0))

(defn referral-fee-violations
  "紹介料の授受は双方向で禁止（職務基本規程13条1項・2項）。

  Checked on the grant *and* on any fee split attached to it, because a
  referral fee renamed as a 'coordination share' to a participant who did no
  work is the same prohibited payment."
  [grant]
  (cond-> []
    (pos? (fee-amount (:referral-fee grant)))
    (conj {:rule :referral-fee-forbidden
           :detail (str "紹介料 " (:referral-fee grant)
                        " の支払いは職務基本規程13条1項により禁止（弁護士は依頼者の"
                        "紹介を受けたことに対する対価を支払ってはならない）")})

    (pos? (fee-amount (:referral-fee-received grant)))
    (conj {:rule :referral-fee-forbidden
           :detail (str "紹介料 " (:referral-fee-received grant)
                        " の受領は職務基本規程13条2項により禁止（弁護士は依頼者の"
                        "紹介をしたことに対する対価を受け取ってはならない）")})))

(defn fee-split-violations
  "A 共同受任 split is lawful because each share pays for legal work actually
  performed. So every share must name a participant, a role describing that
  work, and the shares must total the whole fee — a residual with no owner is
  where a referral fee hides."
  [{:keys [fee-split]}]
  (if (empty? fee-split)
    []
    (let [total (reduce + 0 (map (comp fee-amount :share) fee-split))
          unroled (remove #(seq (str (:role %))) fee-split)
          unnamed (remove #(some? (:bengoshi-id %)) fee-split)]
      (cond-> []
        (not= 100 total)
        (conj {:rule :fee-split-not-whole
               :detail (str "報酬分配の合計が " total "% で 100% にならない"
                            "（帰属先のない残余は紹介料の温床）")})

        (seq unnamed)
        (conj {:rule :fee-split-unnamed-participant
               :detail "報酬分配の受領者に bengoshi-id が無い"})

        (seq unroled)
        (conj {:rule :fee-split-without-role
               :detail (str "分配 " (pr-str (mapv :bengoshi-id unroled))
                            " に担当業務の記載が無い（実際の業務に対する対価で"
                            "なければ紹介料にあたる）")})))))

(defn grant-violations
  "Everything wrong with a proposed co-counsel grant. Empty means the grant is
  representable; issuing it is still an escalated human decision."
  [store grant today]
  (let [grantee (store/bengoshi store (:grantee-bengoshi-id grant))
        caps (set (:capabilities grant))
        m (store/matter store (:matter-id grant))]
    (-> []
        (into (map #(update % :detail (fn [d] (str "共同受任先: " d)))
                   (verification-violations grantee today)))
        (into (referral-fee-violations grant))
        (into (fee-split-violations grant))
        (cond->
         (nil? m)
          (conj {:rule :grant-unknown-matter
                 :detail (str "事件 " (:matter-id grant) " が未登録")})

          (seq (set/difference caps grantable-capabilities))
          (conj {:rule :grant-unknown-capability
                 :detail (str "未定義の capability "
                              (pr-str (vec (sort (set/difference caps grantable-capabilities)))))})

          (empty? caps)
          (conj {:rule :grant-no-capability
                 :detail "capability の無い grant は権限範囲が不明"})

          (not (date/valid? (:expires-on grant)))
          (conj {:rule :grant-no-expiry
                 :detail "共同受任 grant には有効期限が必須（無期限の記録開示は秘密保持義務違反の温床）"})

          (and (date/valid? (:expires-on grant)) (date/before? (:expires-on grant) today))
          (conj {:rule :grant-already-expired
                 :detail (str "有効期限 " (:expires-on grant) " が既に経過")})

          (not (:conflict-cleared? grant))
          (conj {:rule :grant-conflict-not-cleared
                 :detail "共同受任先の利益相反チェックが未了"})))))

;; ---------------------------------------------------------------------------
;; Recruitment funnel — 提携弁護士を集める工程の計器
;; ---------------------------------------------------------------------------

(def pipeline-stages
  "Ordered stages a partner candidate passes through. Kept in code, not in a
  spreadsheet, so the recruitment plan in `docs/partner-recruitment.edn` has
  something that can actually be counted — an unmeasured funnel is a wish."
  [:sourced        ; 候補として特定（弁護士会名簿・分野別・地域別）
   :contacted      ; 初回接触（照会・紹介・登壇後の面談依頼）
   :responded      ; 返信あり
   :verified       ; 日弁連名簿で資格・登録状態を確認済み
   :screened       ; 既存 book に対する利益相反スクリーン通過
   :agreed         ; 提携合意書を締結（紹介料ゼロを明記）
   :onboarded      ; 事務所 OS のアカウント発行・capability 設定完了
   :active])       ; 直近90日に1件以上の共同受任

(defn funnel
  "Count candidates per stage, plus the conversion from each stage to the
  next. `candidates` is a seq of maps carrying `:stage`."
  [candidate-records]
  (let [counts (reduce (fn [acc c] (update acc (:stage c) (fnil inc 0))) {} candidate-records)
        ordered (mapv (fn [s] {:stage s :count (get counts s 0)}) pipeline-stages)]
    {:stages (mapv (fn [{:keys [stage count]} next]
                     (cond-> {:stage stage :count count}
                       next (assoc :to-next (:stage next)
                                   :conversion (if (pos? count)
                                                 (/ (double (:count next)) count)
                                                 0.0))))
                   ordered
                   (concat (rest ordered) [nil]))
     :total (count candidate-records)}))

(defn blocked-at
  "Stages whose conversion to the next stage falls **strictly below**
  `threshold` — where to spend the next hour of recruiting effort. A stage
  converting at exactly the threshold is meeting it, not failing it."
  ([funnel-result] (blocked-at funnel-result 0.5))
  ([funnel-result threshold]
   (->> (:stages funnel-result)
        (filter :to-next)
        (filter #(and (pos? (:count %)) (< (:conversion %) threshold)))
        (mapv #(select-keys % [:stage :to-next :count :conversion])))))
