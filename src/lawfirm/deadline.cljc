(ns lawfirm.deadline
  "期限・期日管理 — the calendar whose failure mode is 弁護過誤.

  A missed 出訴期限 or 上訴期間 is the single most common malpractice claim
  against a practice, and it is the one failure no amount of later diligence
  repairs. So the calendar is not a convenience feature here: `breached-critical`
  feeds a HARD hold in `lawfirm.governor`, which means the practice cannot
  quietly bill new work on a matter whose critical deadline has already lapsed
  without the lapse surfacing first.

  **期間計算 follows 民法140条・143条**, which day arithmetic does not do for
  free:

    * 初日不算入 (140条) — a period counted in days or weeks from an event does
      not count the day of the event. 「判決書送達の日から2週間」 served on
      7月1日 expires at the end of 7月15日, not 7月14日.
    * 暦による計算 (143条) — month and year periods run to the corresponding
      calendar day, clamped to month end (`lawfirm.date/plus-months`).
    * 期間の末日が休日のとき (142条) — the period ends the next business day.
      This namespace takes an injected `holiday?` predicate rather than
      shipping a Japanese holiday table: 国民の祝日 shift by legislation and
      年末年始 closure of a given 裁判所 is not a statute at all. A practice
      that does not supply the predicate gets the un-extended date, which is
      the conservative direction — early, never late."
  (:require [lawfirm.date :as date]
            [lawfirm.store :as store]))

;; ---------------------------------------------------------------------------
;; Statutory periods
;; ---------------------------------------------------------------------------

(def statutory
  "Common Japanese limitation and appeal periods, each with the provision it
  comes from. `:unit` is `:days` or `:months`; `:from` names the event the
  period runs from, because getting the trigger wrong is as fatal as getting
  the length wrong.

  This table is a drafting aid for registering a deadline, never an authority.
  The 弁護士 who registers the 期限 owns it."
  {:appeal-civil            {:label "控訴期間" :n 14 :unit :days :critical? true
                             :from "判決書の送達" :cite "民訴法285条"}
   :appeal-final            {:label "上告期間" :n 14 :unit :days :critical? true
                             :from "原判決の送達" :cite "民訴法313条・285条"}
   :immediate-appeal        {:label "即時抗告期間" :n 7 :unit :days :critical? true
                             :from "裁判の告知" :cite "民訴法332条"}
   :criminal-appeal         {:label "刑事控訴期間" :n 14 :unit :days :critical? true
                             :from "判決宣告" :cite "刑訴法373条"}
   :labor-tribunal-objection {:label "労働審判異議申立期間" :n 14 :unit :days :critical? true
                              :from "審判書の送達" :cite "労働審判法21条1項"}
   :administrative-suit     {:label "取消訴訟出訴期間" :n 6 :unit :months :critical? true
                             :from "処分を知った日" :cite "行訴法14条1項"}
   :prescription-known      {:label "消滅時効（主観的起算点）" :n 5 :unit :years :critical? true
                             :from "権利を行使できることを知った時" :cite "民法166条1項1号"}
   :prescription-objective  {:label "消滅時効（客観的起算点）" :n 10 :unit :years :critical? true
                             :from "権利を行使できる時" :cite "民法166条1項2号"}
   :tort-prescription       {:label "不法行為の消滅時効" :n 3 :unit :years :critical? true
                             :from "損害及び加害者を知った時" :cite "民法724条1号"}})

(defn compute-due
  "Due date for a statutory period running from `trigger-date`.

  `opts` may carry `:holiday?` — a `(fn [iso-date] boolean)` covering both
  国民の祝日 and any court closure the practice observes. When the computed
  末日 is a holiday it rolls forward (民法142条).

  Returns nil for an unknown key or a malformed trigger date rather than
  guessing — a silently wrong 期限 is worse than no 期限."
  ([kind trigger-date] (compute-due kind trigger-date nil))
  ([kind trigger-date {:keys [holiday?]}]
   (when-let [{:keys [n unit]} (get statutory kind)]
     (when (date/valid? trigger-date)
       ;; 初日不算入: the clock starts the day after the trigger, so a period
       ;; of n days ends on trigger + n.
       (let [raw (case unit
                   :days (date/plus-days trigger-date n)
                   :months (date/plus-months trigger-date n)
                   :years (date/plus-months trigger-date (* 12 n))
                   nil)]
         (when raw
           ;; `ifn?`, not `fn?`: a practice's holiday calendar is most naturally
           ;; a set of dates, and sets are IFn but not Fn.
           (if (ifn? holiday?)
             (loop [d raw guard 0]
               (if (and (holiday? d) (< guard 30)) (recur (date/plus-days d 1) (inc guard)) d))
             raw)))))))

(defn draft
  "A deadline record ready for `lawfirm.store/register-deadline!`, derived from
  a statutory period. `:derived-from` keeps the trigger and citation on the
  record so a later reader can re-check the arithmetic instead of trusting it."
  ([deadline-id matter-id kind trigger-date] (draft deadline-id matter-id kind trigger-date nil))
  ([deadline-id matter-id kind trigger-date opts]
   (when-let [{:keys [label critical? cite from]} (get statutory kind)]
     (when-let [due (compute-due kind trigger-date opts)]
       {:deadline-id deadline-id
        :matter-id matter-id
        :kind kind
        :label label
        :due-on due
        :critical? critical?
        :satisfied? false
        :derived-from {:trigger-date trigger-date :trigger-event from :cite cite}}))))

;; ---------------------------------------------------------------------------
;; Status
;; ---------------------------------------------------------------------------

(def default-warning-days
  "How far ahead a deadline starts reading as `:at-risk`. Two weeks is the
  shortest common appeal period, so anything inside it leaves no room to
  prepare a filing from a standing start."
  14)

(defn status
  "`:satisfied` | `:breached` | `:at-risk` | `:pending` for one deadline as of
  `today`. `:unknown` when the record has no usable due date — an unreadable
  deadline is reported, never treated as fine."
  ([deadline today] (status deadline today default-warning-days))
  ([{:keys [due-on satisfied?]} today warning-days]
   (cond
     satisfied? :satisfied
     (not (and (date/valid? due-on) (date/valid? today))) :unknown
     (date/before? due-on today) :breached
     (<= (date/days-between today due-on) warning-days) :at-risk
     :else :pending)))

(defn with-status
  "Every deadline on a matter, annotated and sorted by urgency then date."
  ([store matter-id today] (with-status store matter-id today default-warning-days))
  ([store matter-id today warning-days]
   (let [rank {:breached 0 :unknown 1 :at-risk 2 :pending 3 :satisfied 4}]
     (->> (store/deadlines-of store matter-id)
          (map #(assoc % :status (status % today warning-days)))
          (sort-by (juxt #(get rank (:status %) 9) :due-on))
          vec))))

(defn breached-critical
  "Critical, unsatisfied, past-due deadlines on a matter as of `today`.
  Non-empty means the practice has already missed something that cannot be
  un-missed; `lawfirm.governor` will not let further work be committed on the
  matter until the lapse is addressed."
  [store matter-id today]
  (->> (store/deadlines-of store matter-id)
       (filter :critical?)
       (filter #(= :breached (status % today)))
       vec))

(defn firm-calendar
  "Every deadline across every matter, annotated — the docket view. Sorted
  most urgent first so the top of the list is the thing to do today."
  ([store today] (firm-calendar store today default-warning-days))
  ([store today warning-days]
   (let [rank {:breached 0 :unknown 1 :at-risk 2 :pending 3 :satisfied 4}]
     (->> (store/matters store)
          (mapcat #(with-status store (:matter-id %) today warning-days))
          (sort-by (juxt #(get rank (:status %) 9) :due-on))
          vec))))
