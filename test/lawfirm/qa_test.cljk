(ns lawfirm.qa-test
  (:require [clojure.test :refer [deftest is testing]]
            [lawfirm.fixture :as fx]
            [lawfirm.governor :as governor]
            [lawfirm.qa :as qa]
            [lawfirm.store :as store]))

(defn- check [s proposal & [request-extra]]
  (governor/check (merge fx/request-base request-extra) fx/context proposal s))

(defn- rules [v] (set (map :rule (:violations v))))

(defn- send-answer [answer-id]
  {:op :send-qa-answer :effect :propose :matter-id "M-1" :confidence 0.95
   :answer-id answer-id :sent-on fx/today})

;; ---------------------------------------------------------------------------
;; The ladder — an answer is 法律事務 and travels it like a 書面
;; ---------------------------------------------------------------------------

(deftest a-reviewed-answer-may-be-sent-with-counsel-sign-off
  (let [v (check (fx/fresh-store) (send-answer "A-2"))]
    (is (empty? (:violations v)) (pr-str (:violations v)))
    (is (false? (:hard? v)))
    (testing "and it is still a 弁護士's decision at any confidence"
      (is (true? (:escalate? v)))
      (is (= :counsel-decision (:escalation-reason v))))))

(deftest an-unreviewed-answer-cannot-be-sent
  (let [s (fx/fresh-store)]
    (store/register-qa-answer! s (qa/->answer {:answer-id "A-3" :question-id "Q-2"
                                               :matter-id "M-1" :drafted-by "B-1"
                                               :drafted-on fx/today}))
    (let [v (check s (send-answer "A-3"))]
      (is (true? (:hard? v)))
      (is (contains? (rules v) :qa-answer-not-reviewed))))
  (testing "brevity is not an exception — the same rule holds for a one-line answer"
    (let [s (fx/fresh-store)]
      (store/register-qa-answer! s {:answer-id "A-4" :question-id "Q-2"
                                    :matter-id "M-1" :status :draft
                                    :digest "sha256:short"})
      (is (contains? (rules (check s (send-answer "A-4"))) :qa-answer-not-reviewed)))))

(deftest only-a-verified-bengoshi-can-have-reviewed-it
  (let [s (fx/fresh-store)
        a (store/qa-answer s "A-2")]
    (testing "a suspended 弁護士's review does not count"
      (store/register-qa-answer! s (assoc a :reviewed-by "B-3"))
      (let [v (check s (send-answer "A-2"))]
        (is (true? (:hard? v)))
        (is (contains? (rules v) :qa-reviewer-not-counsel))))
    (testing "one whose verification lapsed does not either"
      (store/register-qa-answer! s (assoc a :reviewed-by "B-4"))
      (is (contains? (rules (check s (send-answer "A-2"))) :qa-reviewer-not-counsel)))))

(deftest recording-the-review-is-itself-restricted-to-counsel
  (let [review (fn [by] {:op :review-qa-answer :effect :propose :matter-id "M-1"
                         :confidence 0.9 :answer-id "A-2" :reviewed-by by
                         :reviewed-on fx/today})]
    (is (empty? (:violations (check (fx/fresh-store) (review "B-1")))))
    (is (contains? (rules (check (fx/fresh-store) (review "B-3")))
                   :qa-reviewer-not-counsel))))

(deftest a-sent-answer-is-not-rewritten
  (testing "a correction is a new answer, so the record keeps what was actually said"
    (let [v (check (fx/fresh-store) (send-answer "A-1"))]
      (is (true? (:hard? v)))
      (is (contains? (rules v) :qa-answer-already-sent)))))

(deftest an-unknown-answer-cannot-be-sent
  (is (contains? (rules (check (fx/fresh-store) (send-answer "A-404")))
                 :qa-answer-unknown)))

;; ---------------------------------------------------------------------------
;; The screen the matter-scoped invariants do not perform
;; ---------------------------------------------------------------------------

(deftest a-pre-engagement-question-needs-its-own-conflict-screen
  (let [s (fx/fresh-store)]
    (store/register-qa-question! s (qa/->question {:question-id "Q-9" :client-id "C-2"
                                                   :asked-on "2026-07-29" :channel :fax
                                                   :digest "sha256:pre"}))
    (store/register-qa-answer! s {:answer-id "A-9" :question-id "Q-9"
                                  :status :lawyer-reviewed :reviewed-by "B-1"
                                  :reviewed-on fx/today :digest "sha256:x"})
    (testing "no matter means the matter-scoped conflict invariants say nothing"
      (let [v (check s (-> (send-answer "A-9") (dissoc :matter-id)) {:client-id "C-2"})]
        (is (true? (:hard? v)))
        (is (contains? (rules v) :qa-conflict-not-screened))
        (is (not (contains? (rules v) :conflict-check-not-cleared))
            "which is exactly why this separate screen exists")))
    (testing "and a dated, cleared screen against the asker satisfies it"
      (store/register-qa-question! s (assoc (store/qa-question s "Q-9")
                                            :screened-by "B-1"
                                            :screened-on "2026-07-29"
                                            :screen-cleared? true))
      (let [v (check s (-> (send-answer "A-9") (dissoc :matter-id)) {:client-id "C-2"})]
        (is (not (contains? (rules v) :qa-conflict-not-screened)))))
    (testing "a screen that ran and found something does not satisfy it"
      (store/register-qa-question! s (assoc (store/qa-question s "Q-9")
                                            :screen-cleared? false))
      (is (contains? (rules (check s (-> (send-answer "A-9") (dissoc :matter-id))
                                   {:client-id "C-2"}))
                     :qa-conflict-not-screened)))))

;; ---------------------------------------------------------------------------
;; The prose stays out
;; ---------------------------------------------------------------------------

(deftest a-question-record-carries-no-prose
  (let [q (qa/->question {:question-id "Q-X" :client-id "C-1" :asked-on fx/today
                          :channel :email :digest "sha256:q"
                          :triage {:domain "labour" :urgency :routine}})]
    (is (= "sha256:q" (:digest q)))
    (is (= "labour" (:domain q)))
    (doseq [k [:body :text :content :summary :message :prose]]
      (is (nil? (get q k))))))

(deftest prose-smuggled-into-a-question-is-held
  (let [with-body {:op :record-qa-question :effect :propose :matter-id "M-1"
                   :confidence 0.9
                   :qa-question {:question-id "Q-X" :matter-id "M-1"
                                 :asked-on fx/today
                                 :text "解雇されたのですが…"}}]
    (is (contains? (rules (check (fx/fresh-store) with-body)) :prose-in-record))
    (testing "and without it, recording the question needs no sign-off"
      (let [v (check (fx/fresh-store)
                     (assoc-in with-body [:qa-question] {:question-id "Q-X"
                                                         :matter-id "M-1"
                                                         :asked-on fx/today
                                                         :digest "sha256:q"}))]
        (is (true? (:ok? v)) (pr-str (:violations v)))
        (is (false? (:escalate? v)))))))

;; ---------------------------------------------------------------------------
;; Drafting an answer consumes the engagement scope
;; ---------------------------------------------------------------------------

(deftest drafting-an-answer-is-billable-work-on-the-matter
  (testing "so 'just answer by email' is not a way to do unmetered work"
    (let [v (check (fx/fresh-store)
                   {:op :draft-qa-answer :effect :propose :matter-id "M-1"
                    :billable-hours 100 :confidence 0.9
                    :qa-answer {:answer-id "A-5" :question-id "Q-2" :matter-id "M-1"}})]
      (is (true? (:hard? v)))
      (is (contains? (rules v) :engagement-scope-exceeded))))
  (testing "and a draft inside the ceiling passes"
    (let [v (check (fx/fresh-store)
                   {:op :draft-qa-answer :effect :propose :matter-id "M-1"
                    :billable-hours 2 :confidence 0.9
                    :qa-answer {:answer-id "A-5" :question-id "Q-2" :matter-id "M-1"}})]
      (is (true? (:ok? v)) (pr-str (:violations v))))))

;; ---------------------------------------------------------------------------
;; 分析
;; ---------------------------------------------------------------------------

(deftest question-state-separates-not-started-from-waiting-on-counsel
  (let [s (fx/fresh-store)]
    (is (= :answered (qa/question-state s "Q-1")))
    (is (= :awaiting-send (qa/question-state s "Q-2"))
        "reviewed but unsent is work on a named 弁護士's desk")
    (store/register-qa-question! s {:question-id "Q-3" :matter-id "M-1"
                                    :asked-on fx/today})
    (is (= :unanswered (qa/question-state s "Q-3")))
    (store/register-qa-answer! s {:answer-id "A-6" :question-id "Q-3"
                                  :matter-id "M-1" :status :draft})
    (is (= :awaiting-review (qa/question-state s "Q-3")))))

(deftest metrics-count-only-what-can-be-measured
  (let [m (qa/metrics (fx/fresh-store) "M-1")]
    (is (= 2 (:total m)))
    (is (= {:answered 1 :awaiting-send 1} (:by-state m)))
    (is (= 1 (:answered m)))
    (is (= 4 (:median-response-days m)) "Q-1 asked 07-10, answer sent 07-14"))
  (testing "an answered question with an unreadable date is dropped, not counted as zero"
    (let [s (fx/fresh-store)]
      (store/register-qa-answer! s (assoc (store/qa-answer s "A-1") :sent-on nil))
      (is (= 0 (:answered (qa/metrics s "M-1"))))
      (is (nil? (:median-response-days (qa/metrics s "M-1")))
          "a zero here would flatter the practice"))))
