(ns lawfirm.intake
  "法律相談の受付とトリアージ — the front door, before there is a matter.

  Someone describes a problem in their own words. This namespace turns that
  into a classification (分野・緊急度・管轄) that `lawfirm.partner/candidates`
  can match against, and nothing more. It does **not** advise, does not say
  whether the person has a case, and does not tell them what the law is —
  that is 法律事務 and requires a 弁護士 (弁護士法72条).

  ## The plaintext never lands in the record

  An intake description is the most sensitive text a practice ever receives,
  and at intake time there is not yet an attorney-client relationship to
  protect it. So `classify` takes the text, returns a classification, and the
  caller persists only `->consult-record` — which carries the classification,
  a caller-supplied digest, and no prose.

  This is a deliberate departure from the earlier `lg_lawfirm_intake` port
  this replaces, which stored the summary base64-encoded behind a
  `signal:v1:` prefix and called the step `encrypt`. **Base64 is an encoding,
  not encryption**; anything holding the record could read the text, and the
  name suggested otherwise. Not storing it is the only version of that
  promise that is true. A practice that genuinely needs the prose should
  encrypt it under a key the record store does not hold, and put the
  ciphertext somewhere this namespace does not reach.

  ## The classifier

  `classify` prefers an injected LLM and falls back to a deterministic
  keyword classifier when none is configured or the call fails. The fallback
  is not a formality — it is what runs whenever the model is unavailable, so
  it returns a *low* `:confidence` and says `:source :fallback`. Downstream,
  a low-confidence triage means the matching is a suggestion for a human to
  read, not a routing decision."
  (:require [clojure.string :as str]
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

(def domains
  "受付分野。Keys are stable ids used by `lawfirm.partner/candidates` for
  matching against a 弁護士's `:specializations`; values carry the Japanese
  label and the keywords the deterministic classifier looks for."
  {"labour"         {:label "労働"
                     :keywords ["解雇" "残業" "未払い" "賃金" "パワハラ" "セクハラ"
                                "退職" "労災" "雇止め" "ハラスメント" "配転"]}
   "family"         {:label "離婚・家事"
                     :keywords ["離婚" "親権" "養育費" "婚姻" "面会交流" "財産分与" "DV"]}
   "inheritance"    {:label "相続"
                     :keywords ["相続" "遺産" "遺言" "遺留分" "被相続人" "分割協議"]}
   "traffic"        {:label "交通事故"
                     :keywords ["交通事故" "追突" "人身事故" "物損" "後遺障害" "過失割合"]}
   "debt"           {:label "債務整理"
                     :keywords ["借金" "破産" "任意整理" "個人再生" "過払い" "督促"]}
   "realestate"     {:label "不動産"
                     :keywords ["賃貸" "明渡" "立退き" "敷金" "境界" "賃料" "建物"]}
   "corporate"      {:label "企業法務・契約"
                     :keywords ["契約" "請負" "代金" "取引" "株主" "取締役" "M&A" "業務委託"]}
   "consumer"       {:label "消費者"
                     :keywords ["解約" "返金" "詐欺" "特定商取引" "クーリングオフ" "定期購入"]}
   "criminal"       {:label "刑事"
                     :keywords ["逮捕" "勾留" "起訴" "被疑者" "被告人" "示談" "前科"]}
   "ip"             {:label "知的財産"
                     :keywords ["特許" "商標" "著作権" "意匠" "不正競争" "無断使用"]}
   "administrative" {:label "行政"
                     :keywords ["許認可" "営業停止" "行政処分" "取消訴訟" "審査請求" "在留"]}
   "other"          {:label "その他" :keywords []}})

(def urgencies
  "緊急度. `:statutory-deadline` is not 'very urgent' — it is a different
  kind of thing, and it is the one that must never be triaged into a queue."
  #{:statutory-deadline :urgent :routine})

(def ^:private deadline-markers
  "Phrases that mean a period may already be running. Their presence flips
  urgency to `:statutory-deadline` regardless of anything else the classifier
  concluded — the cost of a false positive is one extra human read; the cost
  of a false negative is 弁護過誤."
  ["判決" "送達" "時効" "期限" "控訴" "上告" "抗告" "異議" "出訴"
   "訴状が届" "支払督促" "催告" "内容証明" "明日まで" "今週中"])

;; ---------------------------------------------------------------------------
;; Deterministic classifier
;; ---------------------------------------------------------------------------

(defn- hits-for [text {:keys [keywords]}]
  (count (filter #(str/includes? text %) keywords)))

(defn- deadline-risk? [text]
  (boolean (some #(str/includes? text %) deadline-markers)))

(defn fallback-classify
  "Keyword classification. Runs whenever no model is configured or the model
  call fails, so it reports its own weakness honestly rather than presenting a
  guess as a result."
  [text {:keys [domain-hint jurisdiction-hint]}]
  (let [text (or text "")
        scored (->> (dissoc domains "other")
                    (map (fn [[id d]] [id (hits-for text d)]))
                    (filter (comp pos? second))
                    (sort-by (comp - second)))
        [best n] (first scored)
        domain (or (when (contains? domains domain-hint) domain-hint)
                   best
                   "other")
        deadline? (deadline-risk? text)]
    {:domain domain
     :urgency (cond deadline? :statutory-deadline
                    (= "criminal" domain) :urgent
                    :else :routine)
     :jurisdiction (or jurisdiction-hint "JP")
     :confidence (cond deadline? 0.5      ; the deadline flag is the reliable part
                       (nil? best) 0.1
                       (> n 1) 0.4
                       :else 0.25)
     :source :fallback
     :matched-keywords (vec (take 3 (map first scored)))}))

;; ---------------------------------------------------------------------------
;; LLM classifier
;; ---------------------------------------------------------------------------

(def system-prompt
  (str/join
   "\n"
   ["あなたは日本の法律事務所の相談受付の分類担当です。法的助言は一切行いません。"
    "相談内容を読み、EDN の map を1つだけ返してください。"
    "キー: :domain :urgency :jurisdiction :confidence"
    ""
    (str ":domain は次のいずれか — " (str/join " / " (keys domains)))
    ":urgency は :statutory-deadline / :urgent / :routine のいずれか。"
    "  判決の送達、時効、控訴・上告・抗告の期間、支払督促など、"
    "  期間が既に進行している可能性があれば必ず :statutory-deadline。"
    ":jurisdiction は ISO 3166-2 (例 JP-13) または JP。"
    ":confidence は 0.0〜1.0 の自己申告。不明なら低くすること。"
    ""
    "禁止: 法律の内容を述べること。見通しを述べること。推測を断定として述べること。"
    "返答は EDN の map 1つだけ。"]))

(defn- parse
  "Read an EDN map out of a model response, or nil. A response we cannot read
  falls through to the deterministic classifier rather than becoming an
  exception at the front door."
  [content]
  (try
    (let [m (edn/read-string (str content))]
      (when (map? m) m))
    (catch #?(:clj Exception :cljs :default) _ nil)))

(defn classify
  "Classify an intake description.

  `opts`:
    `:generate-fn`       `(fn [messages] {:content str})` — injected. Absent
                         means no ambient network authority and the
                         deterministic path runs.
    `:domain-hint`       caller-supplied domain, wins over inference
    `:jurisdiction-hint` caller-supplied jurisdiction

  The model's answer is **not trusted wholesale**: the deadline markers are
  re-checked against the raw text afterwards and can only ever *raise*
  urgency. A model that fails to notice 「判決書が届いた」 must not be able to
  route the intake into a routine queue."
  [text {:keys [generate-fn] :as opts}]
  (let [fallback (fallback-classify text opts)
        ;; The whole call is guarded, not just the parse: a transport error at
        ;; the front door must degrade to the keyword classifier, not reject a
        ;; person describing their problem.
        parsed (when (ifn? generate-fn)
                 (try
                   (parse (:content (generate-fn
                                     [{:role :system :content system-prompt}
                                      {:role :user :content (str text)}])))
                   (catch #?(:clj Exception :cljs :default) _ nil)))
        result (if (and parsed (contains? domains (:domain parsed)))
                 (-> parsed
                     (update :urgency #(if (contains? urgencies %) % :routine))
                     (assoc :source :llm))
                 fallback)]
    ;; The deadline flag is a floor, never overridden downward.
    (cond-> result
      (deadline-risk? (or text ""))
      (assoc :urgency :statutory-deadline
             :deadline-marker-detected true))))

;; ---------------------------------------------------------------------------
;; The record
;; ---------------------------------------------------------------------------

(defn ->consult-record
  "The persistable intake record: classification, provenance, and a digest the
  caller computed. No prose.

  `digest` is whatever identifier the caller can later use to find the
  original (a hash, a document id in an encrypted store). This namespace does
  not compute it, because computing it here would mean the text passed through
  the record layer."
  [{:keys [consult-id received-on channel digest triage]}]
  {:consult-id consult-id
   :received-on received-on
   :channel channel
   :summary-digest digest
   :domain (:domain triage)
   :domain-label (get-in domains [(:domain triage) :label])
   :urgency (:urgency triage)
   :jurisdiction (:jurisdiction triage)
   :triage-confidence (:confidence triage)
   :triage-source (:source triage)
   :deadline-marker-detected (boolean (:deadline-marker-detected triage))})

(defn matter-spec
  "Turn an intake record into the shape `lawfirm.partner/candidates` and
  `lawfirm.conflict/screen` take, so a 受付 can be screened before anyone
  agrees to anything."
  [consult {:keys [client-id bengoshi-id adverse-parties related-parties]}]
  {:matter-id (str "PROSPECT-" (:consult-id consult))
   :client-id client-id
   :bengoshi-id bengoshi-id
   :domain (:domain consult)
   :jurisdiction (:jurisdiction consult)
   :adverse-parties (vec adverse-parties)
   :related-parties (vec related-parties)})
