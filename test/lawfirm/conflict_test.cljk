(ns lawfirm.conflict-test
  (:require [clojure.test :refer [deftest is testing]]
            [lawfirm.conflict :as conflict]
            [lawfirm.fixture :as fx]
            [lawfirm.store :as store]))

(deftest normalization-folds-corporate-affixes
  (testing "prefix and suffix 株式会社 carry no identity"
    (is (= (conflict/normalize "株式会社甲野商事") (conflict/normalize "甲野商事株式会社")))
    (is (= (conflict/normalize "甲野商事") (conflict/normalize "株式会社 甲野商事"))))
  (testing "latin affixes and case"
    (is (= (conflict/normalize "Acme Corp.") (conflict/normalize "ACME")))
    (is (= (conflict/normalize "Acme, Inc.") (conflict/normalize "acme"))))
  (testing "blank input has no key"
    (is (nil? (conflict/normalize "")))
    (is (nil? (conflict/normalize "   ")))
    (is (nil? (conflict/normalize nil))))
  (testing "distinct parties keep distinct keys"
    (is (not= (conflict/normalize "甲野商事") (conflict/normalize "乙川工業")))))

(deftest clean-book-screens-clean
  (let [s (fx/fresh-store)]
    (is (:cleared? (conflict/screen s {:matter-id "M-NEW" :client-id "C-2"
                                       :bengoshi-id "B-1"
                                       :adverse-parties ["丁田物産"]
                                       :domain "labour"})))))

(deftest matter-does-not-conflict-with-itself
  (let [s (fx/fresh-store)
        m (store/matter s "M-1")]
    (is (:cleared? (conflict/screen-matter s m)))))

(deftest adverse-to-current-client-28-2
  (testing "taking a matter against 甲野商事, who is already our client"
    (let [s (fx/fresh-store)
          {:keys [cleared? hits]} (conflict/screen s {:matter-id "M-NEW" :client-id "C-2"
                                                      :bengoshi-id "B-1"
                                                      :adverse-parties ["甲野商事"]
                                                      :domain "labour"})]
      (is (false? cleared?))
      (is (contains? (set (map :rule hits)) :adverse-to-current-client)))))

(deftest own-client-is-adverse-elsewhere-27-3
  (testing "the prospective client is the opponent in a live matter"
    (let [s (fx/fresh-store)
          _ (store/register-client! s {:client-id "C-3" :name "丙山建設株式会社"})
          {:keys [cleared? hits]} (conflict/screen s {:matter-id "M-NEW" :client-id "C-3"
                                                      :bengoshi-id "B-1"
                                                      :adverse-parties ["戊野商会"]
                                                      :domain "labour"})]
      (is (false? cleared?))
      (is (contains? (set (map :rule hits)) :own-client-is-adverse-elsewhere)))))

(deftest same-matter-opposing-side-27-1
  (testing "both sides of the same dispute — the mirrored pair"
    (let [s (fx/fresh-store)
          _ (store/register-client! s {:client-id "C-3" :name "丙山建設株式会社"})
          {:keys [cleared? hits]} (conflict/screen s {:matter-id "M-NEW" :client-id "C-3"
                                                      :bengoshi-id "B-1"
                                                      :adverse-parties ["株式会社甲野商事"]
                                                      :domain "corporate"})]
      (is (false? cleared?))
      (is (contains? (set (map :rule hits)) :same-matter-opposing-side))
      (testing "reported once, not once per overlapping rule"
        (is (not (contains? (set (map :rule hits)) :adverse-to-current-client)))))))

(deftest former-client-substantially-related-27-2
  (let [s (fx/fresh-store)
        m (store/matter s "M-1")
        _ (store/register-matter! s (assoc m :status :closed))
        _ (store/register-client! s {:client-id "C-3" :name "丁田物産株式会社"})
        {:keys [cleared? hits]} (conflict/screen s {:matter-id "M-NEW" :client-id "C-3"
                                                    :bengoshi-id "B-1"
                                                    :adverse-parties ["丙山建設株式会社"]
                                                    :domain "corporate"})]
    (is (false? cleared?))
    (is (contains? (set (map :rule hits)) :former-client-related))
    (testing "a different domain against the same opponent does not fire the related rule"
      (let [r (conflict/screen s {:matter-id "M-NEW2" :client-id "C-3" :bengoshi-id "B-1"
                                  :adverse-parties ["丙山建設株式会社"] :domain "family"})]
        (is (not (contains? (set (map :rule (:hits r))) :former-client-related)))))))

(deftest self-interest-28-4
  (let [s (fx/fresh-store)
        {:keys [cleared? hits]} (conflict/screen s {:matter-id "M-NEW" :client-id "C-2"
                                                    :bengoshi-id "B-1"
                                                    :adverse-parties ["山田 太郎"]
                                                    :domain "labour"})]
    (is (false? cleared?))
    (is (contains? (set (map :rule hits)) :self-interest))))

(deftest human-clearance-must-be-signed-and-dated
  (let [s (fx/fresh-store)]
    (is (conflict/cleared-by-human? s "M-1"))
    (testing "an affirmative clearance with no decider is not a clearance"
      (store/register-conflict-check! s {:check-id "CC-2" :matter-id "M-1"
                                         :cleared? true :decided-on "2026-07-01"})
      (is (false? (conflict/cleared-by-human? s "M-1"))))
    (testing "and neither is an absent one"
      (is (false? (conflict/cleared-by-human? s "M-404"))))))

(deftest clearance-does-not-survive-a-new-adverse-matter
  (testing "the reason the governor re-screens instead of trusting the flag"
    (let [s (fx/fresh-store)]
      (is (conflict/cleared-by-human? s "M-1"))
      (is (:cleared? (conflict/screen-matter s (store/matter s "M-1"))))
      ;; Tuesday: the practice takes a matter FOR 丙山建設, M-1's opponent.
      (store/register-client! s {:client-id "C-3" :name "丙山建設株式会社"})
      (store/register-matter! s {:matter-id "M-3" :client-id "C-3" :bengoshi-id "B-1"
                                 :status :open :domain "labour"
                                 :adverse-parties ["株式会社甲野商事"]})
      (testing "Monday's clearance record still says cleared"
        (is (conflict/cleared-by-human? s "M-1")))
      (testing "but the live screen no longer does"
        (is (false? (:cleared? (conflict/screen-matter s (store/matter s "M-1")))))))))
