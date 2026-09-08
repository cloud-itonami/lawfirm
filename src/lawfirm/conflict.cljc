(ns lawfirm.conflict
  "利益相反チェック — the conflict-of-interest screen (弁護士職務基本規程
  第27条・第28条).

  Two things have to be true before this practice may prepare work product on
  a matter, and they are deliberately *different* things:

    1. a **human clearance record** exists — a named 弁護士 decided, on a
       date, that the matter may be taken (`lawfirm.store/conflict-check`);
    2. a **live screen** over the current book of business finds no hit
       (`screen` here).

  Requiring both is the whole point. A clearance is a judgement made against
  the facts of one day; the firm takes new matters after that day. If the gate
  trusted the stored `:cleared?` flag alone, accepting an adverse matter on
  Tuesday would silently un-clear Monday's matter and nothing would notice.
  `lawfirm.governor` therefore re-runs `screen` at proposal time and holds on
  a hit even when a clearance record says cleared.

  **What this screen is not.** It matches *names*, not corporate families,
  beneficial ownership, or 同居の親族 relationships. It cannot see that
  「甲野商事」 is a wholly owned subsidiary of an existing client unless
  someone recorded that in `:aliases` or `:related-parties`. A clean screen is
  necessary, never sufficient — which is exactly why the human clearance is a
  separate, irreducible requirement rather than something this namespace could
  ever grant on its own."
  (:require [clojure.set :as set]
            [kotoba.lang.text :as str]
            [lawfirm.store :as store]))

;; ---------------------------------------------------------------------------
;; Name normalization
;; ---------------------------------------------------------------------------

(def ^:private corporate-affixes
  ["株式会社" "有限会社" "合同会社" "合資会社" "合名会社" "一般社団法人"
   "一般財団法人" "公益社団法人" "公益財団法人" "医療法人" "学校法人"
   "宗教法人" "特定非営利活動法人" "(株)" "（株）" "(有)" "（有）"])

(def ^:private latin-affixes
  #"(?i)\s*(,)?\s*\b(co\.|co|ltd\.|ltd|inc\.|inc|llc|l\.l\.c\.|corp\.|corp|k\.k\.|kk|gmbh|s\.a\.|sa|plc|pte\.|pte)\b\.?")

(defn normalize
  "Fold a party name to a comparison key: width/space/case normalized, common
  corporate affixes stripped. `nil` for blank input.

  Affix stripping is what makes 「株式会社甲野商事」 and 「甲野商事株式会社」
  the same key — the prefix/suffix position of 株式会社 carries no identity
  and is a routine source of missed hits in hand-run checks."
  [s]
  (when (and (string? s) (seq (str/trim s)))
    (let [;; Latin affixes are stripped FIRST, while the spaces that delimit
          ;; them still exist — the regex leans on word boundaries, and folding
          ;; whitespace before this step turns "acme corp." into a single token
          ;; that `\bcorp\b` can no longer see.
          base (-> s str/trim str/lower (str/replace latin-affixes ""))
          base (reduce (fn [acc affix] (str/replace acc (str/lower affix) ""))
                       base corporate-affixes)
          base (-> base
                   (str/replace #"[\s　]+" "")
                   (str/replace #"[.,、。・]" ""))]
      (when (seq base) base))))

(defn- keys-of
  "Every comparison key a party record contributes: its name plus any aliases."
  [party]
  (cond
    (string? party) (into #{} (keep normalize) [party])
    (map? party) (into #{} (keep normalize) (cons (:name party) (:aliases party)))
    :else #{}))

(defn- key-set [parties]
  (into #{} (mapcat keys-of) parties))

(defn- client-keys [c]
  (when c (key-set [{:name (:name c) :aliases (:aliases c)}])))

;; ---------------------------------------------------------------------------
;; The screen
;; ---------------------------------------------------------------------------

(defn- hit [rule detail parties]
  {:rule rule :detail detail :parties (vec (sort parties))})

(def ^:private open-statuses #{:open :active :engaged nil})

(defn- open? [m] (contains? open-statuses (:status m)))

(defn screen
  "Screen a candidate matter against the whole book of business.

  `candidate` is `{:matter-id :client-id :bengoshi-id :adverse-parties
  :related-parties :domain}`. When `:matter-id` names an already-registered
  matter it is excluded from the scan (a matter never conflicts with itself).

  Returns `{:cleared? bool :hits [{:rule :detail :parties}] :screened-parties
  [...]}`. Rules, each traced to the 規程 provision it implements:

    :adverse-to-current-client        28条2号 — 受任している他の事件の依頼者を
                                       相手方とする事件
    :own-client-is-adverse-elsewhere  27条3号 — 現に受任している事件の相手方
                                       からの依頼による他の事件
    :same-matter-opposing-side        27条1号 — 相手方の依頼を承諾した事件
    :former-client-related            27条2号 — 相手方の協議を受けた事件と
                                       実質的に関連する事件（同一 domain の
                                       終結済み事件を近似として用いる）
    :co-client-interest-conflict      28条3号 — 依頼者の利益相反
    :self-interest                    28条4号 — 依頼者の利益と弁護士自身の
                                       経済的利益の相反
    :partner-grant-conflict           共同受任先が本件の相手方側で grant を
                                       保持している"
  [store candidate]
  (let [{:keys [matter-id client-id bengoshi-id adverse-parties related-parties domain]} candidate
        this-client (store/client store client-id)
        my-keys (or (client-keys this-client) #{})
        adverse-keys (key-set adverse-parties)
        related-keys (key-set related-parties)
        me (store/bengoshi store bengoshi-id)
        my-own-keys (into #{} (keep normalize) [(:name me)])
        others (remove #(= matter-id (:matter-id %)) (store/matters store))
        hits
        (reduce
         (fn [acc other]
           (let [oc (store/client store (:client-id other))
                 oc-keys (or (client-keys oc) #{})
                 o-adverse (key-set (:adverse-parties other))
                 open-other? (open? other)
                 ;; 28条2号: we would act against a firm client.
                 a (set/intersection adverse-keys oc-keys)
                 ;; 27条3号: our new client is the opponent of a live matter.
                 b (set/intersection my-keys o-adverse)
                 ;; 27条1号: mirrored pair — same two parties, sides swapped.
                 mirrored? (and (seq (set/intersection adverse-keys oc-keys))
                                (seq (set/intersection my-keys o-adverse)))
                 ;; 27条2号 approximation: closed matter, same opponent, same domain.
                 related? (and (not open-other?)
                               (seq (set/intersection adverse-keys o-adverse))
                               (some? domain)
                               (= domain (:domain other)))]
             (cond-> acc
               mirrored?
               (conj (hit :same-matter-opposing-side
                          (str "既存事件 " (:matter-id other)
                               " と当事者が反転して一致（27条1号 相手方の依頼を承諾した事件）")
                          (set/union a b)))

               (and open-other? (seq a) (not mirrored?))
               (conj (hit :adverse-to-current-client
                          (str "相手方が現依頼者（事件 " (:matter-id other)
                               "）と一致（28条2号）")
                          a))

               (and open-other? (seq b) (not mirrored?))
               (conj (hit :own-client-is-adverse-elsewhere
                          (str "依頼者が現に受任中の事件 " (:matter-id other)
                               " の相手方と一致（27条3号）")
                          b))

               related?
               (conj (hit :former-client-related
                          (str "終結事件 " (:matter-id other)
                               " と相手方・分野が一致し実質的関連の疑い（27条2号）")
                          (set/intersection adverse-keys o-adverse)))

               (seq (set/intersection related-keys oc-keys))
               (conj (hit :co-client-interest-conflict
                          (str "関係者が別事件 " (:matter-id other)
                               " の依頼者と一致し利益相反の疑い（28条3号）")
                          (set/intersection related-keys oc-keys))))))
         []
         others)
        ;; 28条4号 — the acting lawyer is a party on either side.
        hits (cond-> hits
               (seq (set/intersection my-own-keys
                                              (set/union adverse-keys related-keys)))
               (conj (hit :self-interest
                          "担当弁護士自身が当事者・関係者に含まれる（28条4号）"
                          (set/intersection
                           my-own-keys (set/union adverse-keys related-keys)))))
        ;; Co-counsel already sitting on the other side of this dispute.
        hits (reduce
              (fn [acc other]
                (let [o-adverse (key-set (:adverse-parties other))]
                  (if (and (open? other)
                           (seq (store/grants-of store (:matter-id other)))
                           (seq (set/intersection my-keys o-adverse)))
                    (conj acc (hit :partner-grant-conflict
                                   (str "共同受任先が事件 " (:matter-id other)
                                        " で本件依頼者の相手方側に関与している")
                                   (set/intersection my-keys o-adverse)))
                    acc)))
              hits
              others)]
    {:cleared? (empty? hits)
     :hits (vec (distinct hits))
     :screened-parties (vec (sort (set/union my-keys adverse-keys related-keys)))}))

(defn screen-matter
  "`screen` for an already-registered matter — the form the governor uses."
  [store m]
  (screen store (select-keys m [:matter-id :client-id :bengoshi-id
                                :adverse-parties :related-parties :domain])))

(defn cleared-by-human?
  "True when a clearance record exists for `matter-id`, is affirmative, and
  names the 弁護士 who made the call. An unsigned clearance is not a
  clearance — 27条・28条 are duties of a person, not of a checkbox."
  [store matter-id]
  (let [c (store/conflict-check-of store matter-id)]
    (boolean (and c (:cleared? c) (:decided-by c) (:decided-on c)))))
