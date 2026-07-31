(ns lawfirm.qa
  "相談 Q&A — a client asks, and an answer goes back only if a 弁護士 wrote or
  examined it.

  ## Why this is not a chat feature

  Answering a question about someone's own legal situation *is* 法律事務. The
  弁護士法72条 line does not move because the answer is short, because it went
  out by email, or because a model drafted it and a human 'looked at it'. So
  an answer travels the same ladder a 書面 does — draft → lawyer-reviewed →
  sent — enforced by the same kind of invariant, and for the same authority
  (法務省 2023年ガイドライン: 弁護士が自ら精査し、必要に応じ自ら修正する).

  Making Q&A a lighter path than 書面 would be the whole failure: the
  practice would keep its discipline for the documents that look formal and
  lose it for the answers that actually reach the client first.

  ## The screen that only Q&A needs

  A question can arrive **before** there is a matter — that is the normal case
  for 法律相談. `lawfirm.governor`'s conflict invariants are matter-scoped and
  therefore say nothing about it, which is exactly how a practice ends up
  answering a question from the other side's relative. So a pre-engagement
  question carries its own screen: `screened?` requires a dated screen against
  the asker before any answer can be sent.

  ## The prose is not here either

  Like `lawfirm.intake`, this namespace holds a classification and a
  caller-supplied digest. The question text and the answer text live wherever
  the practice keeps privileged content; the record layer holds the fact that
  they exist, who examined them and when."
  (:require [lawfirm.date :as date]
            [lawfirm.partner :as partner]
            [lawfirm.store :as store]))

(def question-kinds
  "`:legal-advice` は相談者自身の事案についての判断を求めるもの。
  `:general-information` は制度・手続の一般的な説明（「控訴とは何か」）。

  境界の判定は弁護士が行う。この区別が効くのは、`:general-information` と
  記録された回答が実際には具体的助言だった場合に、**記録の側が嘘をつく**点で、
  分類は免責ではなく監査対象である。"
  #{:legal-advice :general-information})

(def answer-statuses
  "The ladder. `:sent` is reachable only through the governor."
  #{:draft :lawyer-reviewed :sent})

(defn ->question
  "The persistable question record: classification, provenance, digest. No
  prose. `triage` is a `lawfirm.intake/classify` result when the question came
  in through the front door."
  [{:keys [question-id matter-id client-id asked-on channel digest kind triage]}]
  {:question-id question-id
   :matter-id matter-id
   :client-id client-id
   :asked-on asked-on
   :channel channel
   :digest digest
   :kind (or kind :legal-advice)
   :domain (:domain triage)
   :urgency (:urgency triage)})

(defn ->answer
  "A drafted answer. Always enters at `:draft` — there is no constructor that
  produces a reviewed one, because review is an act by a person and not a
  value a caller can pass in."
  [{:keys [answer-id question-id matter-id drafted-by drafted-on digest]}]
  {:answer-id answer-id
   :question-id question-id
   :matter-id matter-id
   :drafted-by drafted-by
   :drafted-on drafted-on
   :digest digest
   :status :draft})

(defn reviewed?
  "True when a 弁護士 recorded that they personally examined this answer."
  [answer]
  (and (= :lawyer-reviewed (:status answer))
       (seq (str (:reviewed-by answer)))
       (date/valid? (:reviewed-on answer))))

(defn screened?
  "True when a dated conflict screen against this asker exists.

  Only consulted for questions with no matter. A question *on* a matter is
  covered by the governor's matter-scoped conflict invariants, which are
  stricter: they re-run the screen at proposal time."
  [question]
  (and (seq (str (:screened-by question)))
       (date/valid? (:screened-on question))
       (true? (:screen-cleared? question))))

;; ---------------------------------------------------------------------------
;; Governor input
;; ---------------------------------------------------------------------------

(defn send-violations
  "Every rule sending `answer-id` breaks, as governor-shaped violation maps.
  Empty does not mean it may be sent — `:send-qa-answer` is escalated
  unconditionally, because answering a legal question is a 弁護士's act."
  [store answer-id today]
  (let [a (store/qa-answer store answer-id)
        q (when a (store/qa-question store (:question-id a)))
        reviewer (when a (store/bengoshi store (:reviewed-by a)))]
    (cond-> []
      (nil? a)
      (conj {:rule :qa-answer-unknown
             :detail (str "未登録の回答 " (pr-str answer-id) " は送信できない")})

      (and a (nil? q))
      (conj {:rule :qa-question-unknown
             :detail (str "回答 " answer-id " が参照する質問 "
                          (pr-str (:question-id a)) " が登録されていない")})

      (and a (= :sent (:status a)))
      (conj {:rule :qa-answer-already-sent
             :detail (str "回答 " answer-id " は送信済み。訂正は新しい回答として"
                          "起案する（送信済みの記録を書き換えない）")})

      (and a (not (reviewed? a)))
      (conj {:rule :qa-answer-not-reviewed
             :detail (str "回答 " answer-id " は status " (pr-str (:status a))
                          "。弁護士が自ら精査し必要に応じ自ら修正した記録の無い回答は"
                          "送信できない（法務省2023年ガイドライン）。"
                          "短い回答であることは例外の理由にならない")})

      (and a (reviewed? a) (not (partner/verified-active? reviewer today)))
      (conj {:rule :qa-reviewer-not-counsel
             :detail (str "回答 " answer-id " の精査者 " (pr-str (:reviewed-by a))
                          " が有効な弁護士登録として確認できない")})

      (and q (nil? (:matter-id q)) (not (screened? q)))
      (conj {:rule :qa-conflict-not-screened
             :detail (str "質問 " (:question-id q) " は受任前の相談であり、"
                          "相談者に対する利益相反スクリーンの記録が無い。"
                          "受任前の相談は事件に紐づかないため、事件単位の"
                          "利益相反判定では捕捉されない")}))))

;; ---------------------------------------------------------------------------
;; Derived reads — 分析
;; ---------------------------------------------------------------------------

(defn answers-of
  "Every answer drafted for a question, oldest id first."
  [store question-id]
  (store/qa-answers-for store question-id))

(defn latest-answer [store question-id] (last (answers-of store question-id)))

(defn question-state
  "Where a question stands, as one keyword the console and the metrics share:
  `:unanswered` | `:awaiting-review` | `:awaiting-send` | `:answered`."
  [store question-id]
  (let [a (latest-answer store question-id)]
    (cond
      (nil? a) :unanswered
      (= :sent (:status a)) :answered
      (reviewed? a) :awaiting-send
      :else :awaiting-review)))

(defn matter-questions
  "Questions on a matter with their state, for display and for counting —
  one function so the number in the metric tile and the rows under it cannot
  disagree."
  [store matter-id]
  (vec (for [q (store/qa-questions-of store matter-id)]
         (assoc q :state (question-state store (:question-id q))))))

(defn response-days
  "Days from question to the answer actually going out, per answered question.
  Nil-safe and never negative-by-accident: a pair of dates this cannot read
  is dropped rather than counted as zero, because a zero would flatter the
  practice."
  [store matter-id]
  (vec
   (for [q (store/qa-questions-of store matter-id)
         :let [a (latest-answer store (:question-id q))]
         :when (and a (= :sent (:status a))
                    (date/valid? (:asked-on q))
                    (date/valid? (:sent-on a)))]
     {:question-id (:question-id q)
      :days (date/days-between (:asked-on q) (:sent-on a))})))

(defn metrics
  "The 分析 a practice can act on: how many questions are waiting, on whom,
  and how long the answered ones took.

  `:awaiting-review` is called out separately from `:unanswered` because they
  fail differently — an unanswered question is work not started, one awaiting
  review is work sitting on a named 弁護士's desk."
  [store matter-id]
  (let [qs (matter-questions store matter-id)
        days (map :days (response-days store matter-id))]
    {:total (count qs)
     :by-state (frequencies (map :state qs))
     :answered (count days)
     :median-response-days (when (seq days)
                             (nth (sort days) (quot (count days) 2)))
     :longest-response-days (when (seq days) (apply max days))}))
