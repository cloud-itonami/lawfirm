(ns lawfirm.console
  "弁護士コンソール — the surface a 弁護士 actually works from.

  Pure `.cljc` hiccup on the kotoba-ui stack (skill `kotoba-uiux`,
  ADR-2607122200): `kotoba-ui.core` + `appkit.core` are the only UI requires,
  every colour and type size is a `--hig-*` token, and layout comes from the
  shell scaffolds. Views are data-in / hiccup-out, so the same code renders
  server-side via `->page` and mounts in a browser through shitsuke's reagent
  seam.

  **Every number on this screen is read through the same functions the
  governor gates on** — `store/billed-hours`, `trust/balance`,
  `deadline/with-status`, `conflict/screen-matter`. There is no display-only
  computation anywhere in this namespace. A console that computed its own
  'remaining hours' would eventually disagree with the gate, and the lawyer
  would trust the screen.

  Ordering is deliberate: the docket comes first, because the failure that
  ends practices is a missed 期限, not an unbilled hour.

  Actions render as `:act` buttons with no client behaviour attached. Wiring
  them to a transport is the host's job (a Worker ingress in cljs — Kotoba has
  no ingress capability today, CLAUDE.md); nothing here performs an effect."
  (:require [appkit.core :as app]
            [kotoba.lang.text :as str]
            [kotoba-ui.core :as ui]
            [lawfirm.conflict :as conflict]
            [lawfirm.deadline :as deadline]
            [lawfirm.partner :as partner]
            [lawfirm.projection :as projection]
            [lawfirm.qa :as qa]
            [lawfirm.store :as store]
            [lawfirm.transmission :as transmission]
            [lawfirm.trust :as trust]))

(def theme
  "One map, per rule 5. The accent is the only place a hex belongs in app
  code; `:auto` lets the viewer's system decide light or dark."
  {:accent "#1F4E79" :accent-dark "#7FB3E8" :appearance :auto})

;; ---------------------------------------------------------------------------
;; Formatting
;; ---------------------------------------------------------------------------

(defn yen
  "Minor-unit integer -> 「¥1,234,567」. JPY has no minor unit, so the integer
  is already 円."
  [n]
  (let [n (or n 0)
        neg? (neg? n)
        digits (str (if neg? (- n) n))
        grouped (->> (reverse digits)
                     (partition-all 3)
                     (map (comp str/join reverse))
                     reverse
                     (str/join ","))]
    (str (when neg? "-") "¥" grouped)))

(defn hours [n] (str (or n 0) "h"))

(defn pct
  "Ratio -> rounded percentage string. Hand-rolled rather than `Math/round`,
  which is JVM-only and would break the cljs render of the same view."
  [r]
  (str (int (+ 0.5 (* 100.0 (or r 0)))) "%"))

(def ^:private status-label
  {:breached "徒過" :at-risk "期限間近" :pending "予定" :satisfied "完了"
   :unknown "要確認"
   ;; 送達先の確認状態と Q&A の進行状態。One vocabulary rather than three, so
   ;; a colour means the same thing everywhere on the page: red is something
   ;; already wrong, orange is something about to be.
   :verified "確認済" :stale "要再確認"
   :unanswered "未回答" :awaiting-review "精査待ち"
   :awaiting-send "送信待ち" :answered "回答済"
   :misdirected "誤送信" :undeterminable "照合不能"})

(def ^:private status-token
  "System palette tokens, not invented hex — the do/don't table names this
  exact mistake."
  {:breached "var(--hig-palette-red)"
   :at-risk "var(--hig-palette-orange)"
   :pending "var(--hig-color-secondary-label)"
   :satisfied "var(--hig-palette-green)"
   :unknown "var(--hig-palette-purple)"
   :verified "var(--hig-palette-green)"
   :stale "var(--hig-palette-red)"
   :unanswered "var(--hig-palette-orange)"
   :awaiting-review "var(--hig-palette-orange)"
   :awaiting-send "var(--hig-color-secondary-label)"
   :answered "var(--hig-palette-green)"
   :misdirected "var(--hig-palette-red)"
   :undeterminable "var(--hig-palette-purple)"})

(defn- status-chip [status]
  [:span {:class "lf-status" :style {:color (get status-token status)}}
   (get status-label status (name status))])

;; ---------------------------------------------------------------------------
;; Docket — 期限 across the whole practice
;; ---------------------------------------------------------------------------

(defn docket-rows [store today]
  (for [d (deadline/firm-calendar store today)]
    {:status (status-chip (:status d))
     :due (:due-on d)
     :matter (:matter-id d)
     :label (or (:label d) (some-> (:kind d) name))
     :basis (let [{:keys [cite trigger-date]} (:derived-from d)]
              (if cite (str cite (when trigger-date (str " / 起算 " trigger-date))) "—"))}))

(defn docket-view
  "The docket. First on the page because a missed 期限 is the failure that
  cannot be repaired later."
  [store today]
  (let [rows (docket-rows store today)
        counts (frequencies (map :status (deadline/firm-calendar store today)))]
    (ui/section
     {:title "期限・期日" :wide true :id "docket"}
     (ui/grid
      {:min "180px"}
      (ui/metric {:label "徒過" :value (str (get counts :breached 0))
                  :status (when (pos? (get counts :breached 0)) "要対応")})
      (ui/metric {:label "期限間近（14日以内）" :value (str (get counts :at-risk 0))})
      (ui/metric {:label "予定" :value (str (get counts :pending 0))})
      (ui/metric {:label "完了" :value (str (get counts :satisfied 0))}))
     (app/panel
      [(ui/data-table
        {:caption (str "全事件の期限（" today " 時点）")
         :columns [{:key :status :label "状態"}
                   {:key :due :label "期限"}
                   {:key :matter :label "事件"}
                   {:key :label :label "種別"}
                   {:key :basis :label "根拠"}]
         :rows (vec rows)
         :empty (ui/empty-state {:title "登録された期限がありません"
                                 :body "期限は事件ごとに登録します。起算点を誤ると期限自体が誤るため、根拠条文と起算日を必ず記録してください。"})})]))))

;; ---------------------------------------------------------------------------
;; Matter detail
;; ---------------------------------------------------------------------------

(defn conflict-panel [store m]
  (let [live (conflict/screen-matter store m)
        human? (conflict/cleared-by-human? store (:matter-id m))
        rec (store/conflict-check-of store (:matter-id m))]
    (app/panel
     [[:h3 {:class "hig-headline"} "利益相反"]
      (ui/list-view
       [(ui/list-row [:span "本日の再スクリーン"]
                     {:trailing (status-chip (if (:cleared? live) :satisfied :breached))})
        (ui/list-row [:span "弁護士による判断記録"]
                     {:trailing (status-chip (if human? :satisfied :breached))})
        (ui/list-row [:span "判断者 / 判断日"]
                     {:trailing [:span {:class "hig-footnote"}
                                 (if rec
                                   (str (:decided-by rec) " / " (:decided-on rec))
                                   "未記録")]})])
      (when (seq (:hits live))
        [:ul {:class "lf-hits hig-footnote"}
         (for [h (:hits live)]
           [:li {:key (str (:rule h))} [:strong (name (:rule h))] " — " (:detail h)])])
      [:p {:class "hig-caption1 lf-muted"}
       "スクリーンは氏名の一致を見るもので、資本関係・親族関係までは見ません。"
       "スクリーンが clean であることは必要条件であって十分条件ではありません。"]])))

(defn engagement-panel [store m]
  (let [used (store/billed-hours store (:matter-id m))
        cap (or (:max-billable-hours m) 0)]
    (app/panel
     [[:h3 {:class "hig-headline"} "受任範囲"]
      (ui/metric {:label "計上済 / 上限" :value (str (hours used) " / " (hours cap))
                  :detail (:engagement m)})
      (ui/progress-bar used {:max (max cap 1)})
      (ui/list-view
       [(ui/list-row [:span "委任契約書"]
                     {:trailing [:span {:class "hig-footnote"}
                                 (or (:fee-agreement m) "未登録")]})
        (ui/list-row [:span "残り"]
                     {:trailing [:span {:class "hig-footnote"} (hours (- cap used))]})])])))

(defn trust-panel [store m]
  (let [lines (trust/ledger-lines store (:matter-id m))]
    (app/panel
     [[:h3 {:class "hig-headline"} "預り金"]
      (ui/metric {:label "預り金残高（分別管理）"
                  :value (yen (trust/balance store (:matter-id m)))})
      (ui/data-table
       {:columns [{:key :date :label "日付"}
                  {:key :purpose :label "摘要"}
                  {:key :in :label "入金"}
                  {:key :out :label "出金"}
                  {:key :running :label "残高"}]
        :rows (vec (for [l lines]
                     {:date (:date l)
                      :purpose (some-> (:purpose l) name)
                      :in (when (= :in (:direction l)) (yen (:amount l)))
                      :out (when (= :out (:direction l)) (yen (:amount l)))
                      :running (yen (:running-balance l))}))
        :empty (ui/empty-state {:title "預り金の記録がありません"})})
      [:p {:class "hig-caption1 lf-muted"}
       "出金は弁護士の承認を要します。報酬への充当には発行済みの請求書が必要です。"]])))

(defn documents-panel [store m]
  (let [docs (store/work-products-of store (:matter-id m))]
    (app/panel
     [[:h3 {:class "hig-headline"} "書面"]
      (ui/data-table
       {:columns [{:key :doc :label "書面"}
                  {:key :kind :label "種別"}
                  {:key :state :label "状態"}
                  {:key :reviewer :label "精査した弁護士"}]
        :rows (vec (for [w docs]
                     {:doc (:doc-id w)
                      :kind (some-> (:kind w) name)
                      :state (case (:status w)
                               :draft (ui/badge "起案")
                               :lawyer-reviewed (ui/badge "弁護士精査済")
                               :issued (ui/badge "発送済")
                               (ui/badge "—"))
                      :reviewer (or (:reviewed-by w) "—")}))
        :empty (ui/empty-state {:title "書面がありません"})})
      [:p {:class "hig-caption1 lf-muted"}
       "弁護士が自ら精査していない書面は外部に出せません（法務省2023年ガイドライン）。"]])))

(defn transmission-panel
  "送達. The destinations come first and the sent rows second, because the
  question a 弁護士 needs answered before sending is 'is this address still
  good', not 'what did I send last week'."
  [store m today]
  (let [mid (:matter-id m)
        rs (store/recipients-of store mid)
        stale (set (map (juxt :recipient-id :channel)
                        (transmission/stale-channels store mid today)))]
    (app/panel
     [[:h3 {:class "hig-headline"} "送達"]
      (ui/data-table
       {:caption "登録済みの送達先"
        :columns [{:key :name :label "送達先"}
                  {:key :role :label "立場"}
                  {:key :channel :label "経路"}
                  {:key :state :label "確認"}
                  {:key :on :label "確認日"}]
        :rows (vec (for [r rs
                         [ch coord] (sort-by key (:channels r))]
                     {:name (:name r)
                      :role (some-> (:role r) name)
                      :channel (get transmission/channel-labels ch (name ch))
                      :state (status-chip (if (contains? stale [(:recipient-id r) ch])
                                            :stale :verified))
                      :on (or (:verified-on coord) "未記録")}))
        :empty (ui/empty-state
                {:title "送達先が登録されていません"
                 :body "宛先は送信時に入力するものではなく、事前に登録して弁護士が確認したものから選びます。"})})
      (ui/data-table
       {:caption "送達記録"
        :columns [{:key :on :label "日付"}
                  {:key :dir :label "方向"}
                  {:key :channel :label "経路"}
                  {:key :what :label "書面 / 発信元"}
                  {:key :result :label "結果"}]
        :rows (vec (for [t (store/transmissions-of store mid)]
                     {:on (:sent-on t)
                      :dir (if (= :inbound (:direction t)) (ui/badge "受信") (ui/badge "発信"))
                      :channel (get transmission/channel-labels (:channel t) (some-> (:channel t) name))
                      :what (if (= :inbound (:direction t))
                              (projection/describe-origin t)
                              (:doc-id t))
                      :result (cond
                                (:misdirected? t) (status-chip :misdirected)
                                (= :undeterminable (:direction-check t))
                                (status-chip :undeterminable)
                                :else (case (:result t)
                                        :ok "送信完了"
                                        :failed "送信失敗"
                                        :pending "送信中"
                                        "未確認"))}))
        :empty (ui/empty-state {:title "送達の記録がありません"})})
      [:p {:class "hig-caption1 lf-muted"}
       "送信確認は機械が応答したことを示すだけで、送達の証明ではありません。"
       "受信した書面の本文は記録に載せず、digest のみを保持します。"
       "「誤送信」は経路が記録と異なる宛先へ送ったことを示し、"
       "「照合不能」は照合の材料が無かったことを示します——後者は事故ではなく証跡の欠落です。"]])))

(defn qa-panel
  "相談 Q&A. The state column is the whole point: 未回答 and 精査待ち fail
  differently, and a practice that counts them together cannot tell work not
  started from work waiting on a named person."
  [store m]
  (let [mid (:matter-id m)
        qs (qa/matter-questions store mid)
        mx (qa/metrics store mid)]
    (app/panel
     [[:h3 {:class "hig-headline"} "相談 Q&A"]
      (ui/grid
       {:min "150px"}
       (ui/metric {:label "未回答" :value (str (get-in mx [:by-state :unanswered] 0))})
       (ui/metric {:label "精査待ち" :value (str (get-in mx [:by-state :awaiting-review] 0))})
       (ui/metric {:label "回答までの中央値"
                   :value (if-let [d (:median-response-days mx)] (str d "日") "—")
                   :detail (when-let [d (:longest-response-days mx)]
                             (str "最長 " d "日"))}))
      (ui/data-table
       {:columns [{:key :on :label "受付"}
                  {:key :state :label "状態"}
                  {:key :kind :label "種別"}
                  {:key :channel :label "経路"}
                  {:key :reviewer :label "精査した弁護士"}]
        :rows (vec (for [q qs
                         :let [a (qa/latest-answer store (:question-id q))]]
                     {:on (:asked-on q)
                      :state (status-chip (:state q))
                      :kind (case (:kind q)
                              :legal-advice "法的助言"
                              :general-information "一般的情報"
                              "—")
                      :channel (get transmission/channel-labels (:channel q)
                                    (some-> (:channel q) name))
                      :reviewer (or (:reviewed-by a) "—")}))
        :empty (ui/empty-state {:title "相談の記録がありません"})})
      [:p {:class "hig-caption1 lf-muted"}
       "弁護士が自ら精査していない回答は送信できません。短い回答であることは例外の理由になりません。"]])))

(defn matter-view
  "One matter, whole."
  [store m today]
  (ui/section
   {:title (str (:matter-id m) "　" (:name m)) :wide true}
   [:p {:class "hig-subheadline lf-muted"}
    (str/join "　/　" (remove str/blank?
                              [(:court m) (:case-number m)
                               (when (:status m) (str "状態 " (name (:status m))))]))]
   (ui/grid
    {:min "320px"}
    (conflict-panel store m)
    (engagement-panel store m)
    (trust-panel store m)
    (documents-panel store m)
    (transmission-panel store m today)
    (qa-panel store m))))

;; ---------------------------------------------------------------------------
;; Approvals — the escalation queue
;; ---------------------------------------------------------------------------

(defn approvals-view
  "Operations parked for 弁護士 sign-off. `pending` is a seq of
  `{:thread-id :op :matter-id :escalation-reason :requested-on}` — the host
  owns the checkpoint store, so the queue is passed in rather than guessed at
  from the ledger."
  [pending]
  (ui/section
   {:title "承認待ち" :wide true :id "approvals"}
   (app/panel
    [(ui/data-table
      {:columns [{:key :op :label "操作"}
                 {:key :matter :label "事件"}
                 {:key :reason :label "理由"}
                 {:key :since :label "起案日"}
                 {:key :action :label ""}]
       :rows (vec (for [p pending]
                    {:op (some-> (:op p) name)
                     :matter (:matter-id p)
                     :reason (case (:escalation-reason p)
                               :counsel-decision "弁護士の判断を要する操作"
                               :low-confidence "確信度が閾値未満"
                               "—")
                     :since (:requested-on p)
                     :action (ui/button "承認" {:act [:approve (:thread-id p)]})}))
       :empty (ui/empty-state {:title "承認待ちはありません"})})
     [:p {:class "hig-caption1 lf-muted"}
      "承認は弁護士本人が行い、承認者名と日付が台帳に記録されます。"]])))

;; ---------------------------------------------------------------------------
;; Partner network
;; ---------------------------------------------------------------------------

(defn partners-view
  "The 提携弁護士 network: who can take a matter today, and where the
  recruitment funnel is losing candidates."
  [store today {:keys [matter-spec funnel-records]}]
  (let [cs (when matter-spec (partner/candidates store matter-spec today))
        f (partner/funnel (or funnel-records []))
        blocked (set (map :stage (partner/blocked-at f)))]
    (ui/section
     {:title "提携弁護士" :wide true :id "partners"}
     (ui/grid
      {:min "320px"}
      (app/panel
       [[:h3 {:class "hig-headline"} "本件で共同受任可能な弁護士"]
        (ui/data-table
         {:columns [{:key :name :label "氏名"}
                    {:key :bar :label "所属"}
                    {:key :spec :label "取扱分野"}
                    {:key :score :label "適合度"}]
          :rows (vec (for [c cs]
                       {:name (:name c)
                        :bar (:bar-association c)
                        :spec (str/join "・" (:specializations c))
                        :score (str (:match-score c))}))
          :empty (ui/empty-state
                  {:title "該当なし"
                   :body "資格確認済み・利益相反なし・分野または管轄が一致する弁護士がいません。"})})
        [:p {:class "hig-caption1 lf-muted"}
         "利益相反に触れる候補は表示自体を行いません。紹介料の授受は双方向で禁止されています（職務基本規程13条）。"]])
      (app/panel
       [[:h3 {:class "hig-headline"} "提携獲得ファネル"]
        (ui/data-table
         {:columns [{:key :stage :label "段階"}
                    {:key :count :label "件数"}
                    {:key :conv :label "次段階への転換"}]
          :rows (vec (for [s (:stages f)]
                       {:stage [:span (when (contains? blocked (:stage s))
                                        {:style {:color (get status-token :at-risk)}})
                                (name (:stage s))]
                        :count (str (:count s))
                        :conv (if (:to-next s) (pct (:conversion s)) "—")}))
          :empty (ui/empty-state {:title "候補が登録されていません"})})
        (when (seq blocked)
          [:p {:class "hig-footnote"}
           "詰まっている段階: " (str/join "・" (map name blocked))])])))))

;; ---------------------------------------------------------------------------
;; Page
;; ---------------------------------------------------------------------------

(def app-css
  "Unlayered, so it wins over `@layer kotoba.hig, kotoba.glass` without a
  single compound selector. Five rules — anything more would mean the shell is
  missing a scaffold and should be extended upstream instead."
  (str ".lf-muted{color:var(--hig-color-secondary-label)}"
       ".lf-status{font-weight:600}"
       ".lf-hits{margin:var(--hig-spacing-2) 0 0;padding-left:var(--hig-spacing-4)}"
       ".lf-hits li{margin-bottom:var(--hig-spacing-1)}"
       ".lf-matter-nav{min-width:0}"))

(defn sidebar [store current-matter-id]
  [:nav {:class "lf-matter-nav" :aria-label "事件一覧"}
   [:h2 {:class "hig-footnote lf-muted"} "事件"]
   (ui/list-view
    (for [m (sort-by :matter-id (store/matters store))]
      (ui/list-row [:span {:class (when (= current-matter-id (:matter-id m)) "hig-headline")}
                    (str (:matter-id m) "　" (:name m))]
                   {:act [:open-matter (:matter-id m)]
                    :trailing (ui/badge (some-> (:status m) name))})))])

(defn practice-view
  "事務所全体. Read from `lawfirm.projection/practice-summary` — the same
  value a portal outside this repository renders, so the office console and
  the company portal cannot show different numbers for the same practice."
  [store today]
  (let [{:keys [totals]} (projection/practice-summary store today)]
    (ui/section
     {:title "事務所全体" :wide true :id "practice"}
     (ui/grid
      {:min "180px"}
      (ui/metric {:label "事件" :value (str (:matters totals))})
      (ui/metric {:label "徒過" :value (str (:breached totals))
                  :status (when (pos? (:breached totals)) "要対応")})
      (ui/metric {:label "未処理の相談" :value (str (:qa-open totals))})
      (ui/metric {:label "結果未確認の送達" :value (str (:transmissions-unconfirmed totals))})
      (ui/metric {:label "誤送信" :value (str (:misdirected totals))
                  :status (when (pos? (:misdirected totals)) "守秘義務事故")})
      (ui/metric {:label "要再確認の宛先" :value (str (:stale-channels totals))
                  :status (when (pos? (:stale-channels totals)) "送達前に確認")})))))

(defn view
  "The whole console. `opts`: `:matter-id` (detail pane), `:pending`
  (approval queue), `:partner` (`{:matter-spec :funnel-records}`)."
  [store today {:keys [matter-id pending partner] :as _opts}]
  (ui/app-shell
   {:nav (ui/nav-bar "法律事務所コンソール"
                     {:trailing [(ui/badge today)]})
    :sidebar (sidebar store matter-id)}
   (docket-view store today)
   (practice-view store today)
   (approvals-view pending)
   (when matter-id (matter-view store (store/matter store matter-id) today))
   (partners-view store today (or partner {}))))

(defn render
  "Complete HTML document."
  [store today opts]
  (ui/->page {:title "法律事務所コンソール"
              :description "受任から終結までを、弁護士の判断を必ず経由する形で記録・監査する事務所 OS。"
              :lang "ja"
              :theme theme
              :head [[:style app-css]]}
             (view store today opts)))
