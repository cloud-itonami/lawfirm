(ns lawfirm.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [lawfirm.demo :as demo]
            [lawfirm.store :as store]))

;; ---------------------------------------------------------------------------
;; Per-matter reads
;;
;; These exist because the by-matter filters are the shape of read that is
;; easiest to get subtly wrong and hardest to notice: a filter that returns
;; nothing renders as an empty panel, and an empty panel looks like a matter
;; with no documents rather than like a broken query.
;; ---------------------------------------------------------------------------

(deftest per-matter-reads-return-that-matters-rows
  (let [s (demo/fresh-store)]
    (testing "work products"
      (is (= ["W-1" "W-9"] (mapv :doc-id (store/work-products-of s "M-1"))))
      (is (= [] (store/work-products-of s "M-404"))))
    (testing "recipients"
      (is (= ["R-1" "R-2"] (mapv :recipient-id (store/recipients-of s "M-1"))))
      (is (= [] (store/recipients-of s "M-404"))))
    (testing "questions"
      (is (= ["Q-1" "Q-2"] (mapv :question-id (store/qa-questions-of s "M-1")))))
    (testing "transmissions, oldest first"
      (is (= ["TX-1" "TX-2" "IN-9001"]
             (mapv :transmission-id (store/transmissions-of s "M-1")))))
    (testing "answers, by question"
      (is (= ["A-1"] (mapv :answer-id (store/qa-answers-for s "Q-1"))))
      (is (= [] (store/qa-answers-for s "Q-404"))))))

(deftest records-whose-state-changes-are-upserted-by-id
  (let [s (store/mem-store)]
    (testing "a cured 期限 must not leave its uncured self behind"
      (store/register-deadline! s {:deadline-id "D-1" :matter-id "M" :satisfied? false})
      (store/register-deadline! s {:deadline-id "D-1" :matter-id "M" :satisfied? true})
      (is (= [true] (mapv :satisfied? (store/deadlines-of s "M")))))
    (testing "distinct 送達 ids accumulate — the same 書面 lawfully goes to
              the court, the opposing counsel and the client"
      (store/register-transmission! s {:transmission-id "T-1" :matter-id "M" :channel :fax})
      (store/register-transmission! s {:transmission-id "T-2" :matter-id "M" :channel :post})
      (is (= 2 (count (store/transmissions-of s "M")))))
    (testing "but one 送達 confirmed does not leave its unconfirmed self behind"
      (store/register-transmission! s {:transmission-id "T-1" :matter-id "M"
                                       :channel :fax :result :ok})
      (is (= 2 (count (store/transmissions-of s "M"))))
      (is (= [:ok nil] (mapv :result (store/transmissions-of s "M")))))))

;; ---------------------------------------------------------------------------
;; Durable store
;; ---------------------------------------------------------------------------

(defn- exercise!
  "One of every kind of write, so a test can compare two implementations on
  the whole protocol rather than on the parts someone remembered."
  [s]
  (store/register-bengoshi! s {:bengoshi-id "B-1" :name "山田"})
  (store/register-partner-firm! s {:firm-id "F-1" :name "戊田"})
  (store/register-client! s {:client-id "C-1" :name "甲野"})
  (store/register-matter! s {:matter-id "M-1" :client-id "C-1"})
  (store/register-conflict-check! s {:check-id "CC-1" :matter-id "M-1" :cleared? true})
  (store/register-deadline! s {:deadline-id "D-1" :matter-id "M-1" :due-on "2026-08-05"})
  (store/register-time-entry! s {:entry-id "T-1" :matter-id "M-1" :hours 3 :billable? true})
  (store/register-trust-entry! s {:entry-id "TR-1" :matter-id "M-1" :direction :in
                                  :amount 1000 :account :trust})
  (store/register-invoice! s {:invoice-id "I-1" :matter-id "M-1" :amount 1000})
  (store/register-work-product! s {:doc-id "W-1" :matter-id "M-1" :status :draft})
  (store/register-counsel-grant! s {:grant-id "G-1" :matter-id "M-1"})
  (store/register-recipient! s {:recipient-id "R-1" :matter-id "M-1"})
  (store/register-transmission! s {:transmission-id "TX-1" :matter-id "M-1"})
  (store/register-qa-question! s {:question-id "Q-1" :matter-id "M-1"})
  (store/register-qa-answer! s {:answer-id "A-1" :question-id "Q-1" :matter-id "M-1"})
  (store/commit-record! s {:op :test :client-id "C-1"})
  (store/append-ledger! s {:disposition :commit})
  s)

(defn- observable
  "Every read the protocol offers, as one comparable value."
  [s]
  {:bengoshi (store/bengoshi s "B-1")
   :all-bengoshi (set (store/all-bengoshi s))
   :partner-firm (store/partner-firm s "F-1")
   :client (store/client s "C-1")
   :matter (store/matter s "M-1")
   :matters (set (store/matters s))
   :conflict-check (store/conflict-check s "CC-1")
   :conflict-check-of (store/conflict-check-of s "M-1")
   :deadlines (store/deadlines-of s "M-1")
   :time-entries (store/time-entries-of s "M-1")
   :trust-entries (store/trust-entries-of s "M-1")
   :invoice (store/invoice s "I-1")
   :work-product (store/work-product s "W-1")
   :work-products (store/work-products-of s "M-1")
   :counsel-grant (store/counsel-grant s "G-1")
   :grants (store/grants-of s "M-1")
   :recipient (store/recipient s "R-1")
   :recipients (store/recipients-of s "M-1")
   :transmissions (store/transmissions-of s "M-1")
   :qa-question (store/qa-question s "Q-1")
   :qa-questions (store/qa-questions-of s "M-1")
   :qa-answer (store/qa-answer s "A-1")
   :qa-answers (store/qa-answers-for s "Q-1")
   :records (store/records-of s "C-1")
   :ledger (store/ledger s)})

(deftest the-two-implementations-cannot-drift
  (testing "same writes, same reads — the property that makes the durable store safe to swap in"
    (let [mem (exercise! (store/mem-store))
          dur (exercise! (store/durable-store {:persist! (fn [_])}))]
      (is (= (observable mem) (observable dur))))))

(deftest every-accepted-write-is-persisted
  (let [written (atom [])
        s (store/durable-store {:persist! #(swap! written conj %)})]
    (exercise! s)
    (is (= 17 (count @written)) "one snapshot per accepted transition")
    (testing "the version is monotonic, so a host can drop an out-of-order snapshot"
      (is (= (range 1 18) (map store/version-key @written))))
    (testing "the last snapshot is what the store shows"
      (is (= (dissoc (last @written) store/version-key)
             (dissoc (store/snapshot s) store/version-key))))))

(deftest a-snapshot-round-trips
  (let [written (atom nil)
        s1 (exercise! (store/durable-store {:persist! #(reset! written %)}))
        s2 (store/durable-store {:snapshot @written :persist! (fn [_])})]
    (is (= (observable s1) (observable s2))
        "a practice must come back after a restart holding what it held")))

(deftest an-old-snapshot-comes-back-with-empty-collections-not-nils
  (testing "a snapshot written before an entity existed must not read as nil"
    (let [s (store/durable-store {:snapshot {:matters {"M-1" {:matter-id "M-1"}}}
                                  :persist! (fn [_])})]
      (is (= [] (store/transmissions-of s "M-1")))
      (is (= [] (store/recipients-of s "M-1")))
      (is (= [] (store/qa-questions-of s "M-1")))
      (is (= [] (store/ledger s))))))

(deftest a-store-with-no-persist-fn-still-works
  (testing "so a caller can build one before deciding where it lives"
    (let [s (store/durable-store {})]
      (store/register-client! s {:client-id "C-1"})
      (is (= {:client-id "C-1"} (store/client s "C-1"))))))

(deftest mem-store-can-be-snapshotted-too
  (testing "a caller wanting a checkpoint should not have to know which one it holds"
    (let [s (exercise! (store/mem-store))]
      (is (= "甲野" (get-in (store/snapshot s) [:clients "C-1" :name]))))))
