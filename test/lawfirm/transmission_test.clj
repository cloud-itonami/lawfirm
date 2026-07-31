(ns lawfirm.transmission-test
  (:require [clojure.test :refer [deftest is testing]]
            [lawfirm.fixture :as fx]
            [lawfirm.governor :as governor]
            [lawfirm.store :as store]
            [lawfirm.transmission :as transmission]))

(defn- check [s proposal & [request-extra]]
  (governor/check (merge fx/request-base request-extra) fx/context proposal s))

(defn- rules [v] (set (map :rule (:violations v))))

(def ^:private send-to-court
  "The clean case: an issued 準備書面 going to the court by fax, to a
  recipient whose number a 弁護士 verified this month."
  {:op :transmit-work-product :effect :propose :matter-id "M-1"
   :confidence 0.9 :doc-id "W-9"
   :transmission {:transmission-id "TX-9" :matter-id "M-1" :direction :outbound
                  :channel :fax :doc-id "W-9" :recipient-id "R-1"
                  :sent-on "2026-07-30" :page-count 12}})

;; ---------------------------------------------------------------------------
;; The invariant holds — and the clean case still passes
;; ---------------------------------------------------------------------------

(deftest a-registered-verified-destination-passes-the-gate
  (let [v (check (fx/fresh-store) send-to-court)]
    (is (empty? (:violations v)) (pr-str (:violations v)))
    (is (false? (:hard? v)))
    (testing "but 送達 is still a 弁護士's decision, at any confidence"
      (is (true? (:escalate? v)))
      (is (= :counsel-decision (:escalation-reason v))))))

(deftest an-unregistered-destination-is-held
  (testing "the whole point: a destination that is not in the record cannot be sent to"
    (let [v (check (fx/fresh-store)
                   (assoc-in send-to-court [:transmission :recipient-id] "R-404"))]
      (is (true? (:hard? v)))
      (is (contains? (rules v) :recipient-not-registered)))))

(deftest a-destination-belonging-to-another-matter-is-held
  (let [s (fx/fresh-store)]
    (store/register-matter! s {:matter-id "M-2" :client-id "C-1" :bengoshi-id "B-1"
                               :status :open :domain "corporate"})
    (store/register-recipient! s {:recipient-id "R-9" :matter-id "M-2"
                                  :name "別事件の相手方" :role :opposing-counsel
                                  :channels {:fax {:number "03-XXXX-9999"
                                                   :verified-by "B-1"
                                                   :verified-on "2026-07-01"}}})
    (let [v (check s (assoc-in send-to-court [:transmission :recipient-id] "R-9"))]
      (is (true? (:hard? v)))
      (is (contains? (rules v) :recipient-wrong-matter)))))

(deftest a-stale-verification-is-held
  (testing "R-2's fax was verified in January — an address is not a fact that stays true"
    (let [v (check (fx/fresh-store)
                   (assoc-in send-to-court [:transmission :recipient-id] "R-2"))]
      (is (true? (:hard? v)))
      (is (contains? (rules v) :recipient-channel-unverified))))
  (testing "and re-verifying it today clears exactly that violation"
    (let [s (fx/fresh-store)
          r (store/recipient s "R-2")]
      (store/register-recipient!
       s (assoc-in r [:channels :fax :verified-on] fx/today))
      (let [v (check s (assoc-in send-to-court [:transmission :recipient-id] "R-2"))]
        (is (not (contains? (rules v) :recipient-channel-unverified)))))))

(deftest a-channel-the-recipient-has-no-coordinate-for-is-held
  (testing "R-2 has a fax number and no postal address"
    (let [v (check (fx/fresh-store)
                   (-> send-to-court
                       (assoc-in [:transmission :recipient-id] "R-2")
                       (assoc-in [:transmission :channel] :post)))]
      (is (true? (:hard? v)))
      (is (contains? (rules v) :recipient-channel-unregistered)))))

(deftest an-unissued-document-cannot-be-transmitted
  (testing "W-1 is a draft; 発出 and 送達 are two acts and the first has not happened"
    (let [v (check (fx/fresh-store)
                   (-> send-to-court
                       (assoc :doc-id "W-1")
                       (assoc-in [:transmission :doc-id] "W-1")))]
      (is (true? (:hard? v)))
      (is (contains? (rules v) :work-product-not-issued)))))

(deftest a-document-that-cannot-take-the-channel-is-held
  (testing "訴状 by fax to a court — 民事訴訟規則3条1項"
    (let [s (fx/fresh-store)]
      (store/register-work-product! s {:doc-id "W-S" :matter-id "M-1"
                                       :kind :訴状 :status :issued
                                       :reviewed-by "B-1" :reviewed-on "2026-07-18"})
      (let [v (check s (-> send-to-court
                           (assoc :doc-id "W-S")
                           (assoc-in [:transmission :doc-id] "W-S")))]
        (is (true? (:hard? v)))
        (is (contains? (rules v) :channel-forbidden-for-document))
        (is (some #(= "民事訴訟規則3条1項" (:cite %)) (:violations v))))))
  (testing "and the same 訴状 by post is not held on that ground"
    (let [s (fx/fresh-store)]
      (store/register-work-product! s {:doc-id "W-S" :matter-id "M-1"
                                       :kind :訴状 :status :issued
                                       :reviewed-by "B-1" :reviewed-on "2026-07-18"})
      (let [v (check s (-> send-to-court
                           (assoc :doc-id "W-S")
                           (assoc-in [:transmission :doc-id] "W-S")
                           (assoc-in [:transmission :channel] :post)))]
        (is (not (contains? (rules v) :channel-forbidden-for-document)))))))

(deftest the-restriction-table-does-not-grant-permission-by-silence
  (testing "an absent combination returns nil, which callers must not read as 可"
    (let [court (store/recipient (fx/fresh-store) "R-1")]
      (is (nil? (transmission/restriction court :fax :準備書面)))
      (is (some? (transmission/restriction court :fax :訴状)))))
  (testing "which is why 送達 is escalated unconditionally"
    (is (true? (:escalate? (check (fx/fresh-store) send-to-court))))))

;; ---------------------------------------------------------------------------
;; Inbound
;; ---------------------------------------------------------------------------

(deftest an-arriving-fax-carries-no-prose
  (let [t (transmission/inbound-record
           {:transmission-id "IN-1" :matter-id "M-1" :channel :fax
            :received-on "2026-07-30" :origin "03-XXXX-0002"
            :page-count 3 :digest "sha256:aa"})]
    (is (= :inbound (:direction t)))
    (is (= "sha256:aa" (:digest t)))
    (doseq [k [:body :text :content :summary :message :prose]]
      (is (nil? (get t k)) (str "inbound record must not carry " k)))))

(deftest prose-smuggled-into-an-inbound-record-is-held
  (testing "the promise is enforced at the gate, not left as a convention"
    (let [v (check (fx/fresh-store)
                   {:op :record-inbound-transmission :effect :propose
                    :matter-id "M-1" :confidence 0.9
                    :transmission {:transmission-id "IN-2" :matter-id "M-1"
                                   :direction :inbound :channel :fax
                                   :sent-on "2026-07-30"
                                   :body "相手方からの書面本文…"}})]
      (is (true? (:hard? v)))
      (is (contains? (rules v) :prose-in-record))))
  (testing "and the same record without the body commits without sign-off"
    (let [v (check (fx/fresh-store)
                   {:op :record-inbound-transmission :effect :propose
                    :matter-id "M-1" :confidence 0.9
                    :transmission {:transmission-id "IN-2" :matter-id "M-1"
                                   :direction :inbound :channel :fax
                                   :sent-on "2026-07-30" :digest "sha256:bb"}})]
      (is (true? (:ok? v)) (pr-str (:violations v)))
      (is (false? (:escalate? v))
          "recording what arrived is not a decision — requiring sign-off means it is not recorded"))))

;; ---------------------------------------------------------------------------
;; Registering a destination
;; ---------------------------------------------------------------------------

(deftest a-destination-is-registered-on-a-counsel-verification
  (let [good {:op :register-recipient :effect :propose :matter-id "M-1"
              :confidence 0.9
              :recipient {:recipient-id "R-3" :matter-id "M-1" :name "甲野商事 総務部"
                          :role :client
                          :channels {:fax {:number "03-XXXX-0003"
                                           :verified-by "B-1"
                                           :verified-on "2026-07-30"}}}}]
    (testing "verified by an active 弁護士 — passes"
      (is (empty? (:violations (check (fx/fresh-store) good)))))
    (testing "verified by a suspended one — held"
      (let [v (check (fx/fresh-store)
                     (assoc-in good [:recipient :channels :fax :verified-by] "B-3"))]
        (is (true? (:hard? v)))
        (is (contains? (rules v) :recipient-verifier-not-counsel))))
    (testing "verified by nobody — held"
      (let [v (check (fx/fresh-store)
                     (update-in good [:recipient :channels :fax] dissoc :verified-by))]
        (is (true? (:hard? v)))
        (is (contains? (rules v) :recipient-verifier-not-counsel))))))

;; ---------------------------------------------------------------------------
;; Derived reads
;; ---------------------------------------------------------------------------

(deftest the-practice-can-list-what-it-cannot-yet-send-to
  (let [stale (transmission/stale-channels (fx/fresh-store) "M-1" fx/today)]
    (is (= 1 (count stale)))
    (is (= "R-2" (:recipient-id (first stale))))
    (is (= :fax (:channel (first stale))))))

(deftest unconfirmed-outbound-is-listable
  (let [s (fx/fresh-store)]
    (is (= #{"TX-2"} (set (map :transmission-id (transmission/unconfirmed s "M-1"))))
        "TX-1 recorded a device result; TX-2 did not")))

(deftest channel-mix-counts-both-directions
  (is (= {:fax 2 :post 1} (transmission/channel-mix (fx/fresh-store) "M-1"))))

(deftest hand-delivery-has-nothing-to-verify
  (let [r (store/recipient (fx/fresh-store) "R-1")]
    (is (false? (transmission/verification-stale? r :hand fx/today)))
    (is (some? (transmission/coordinate r :hand)))))
