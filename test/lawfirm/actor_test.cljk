(ns lawfirm.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [lawfirm.actor :as actor]
            [lawfirm.fixture :as fx]
            [lawfirm.store :as store]
            [lawfirm.trust :as trust]))

(defn- graph [s] (actor/build-graph {:store s}))

(defn- run [s request]
  (actor/run-request! (graph s) (merge fx/request-base request) fx/context
                      (str "T-" (hash request))))

(defn- run-on [g request thread]
  (actor/run-request! g (merge fx/request-base request) fx/context thread))

;; ---------------------------------------------------------------------------
;; Commit path
;; ---------------------------------------------------------------------------

(deftest routine-work-commits-and-lands-in-the-record
  (let [s (fx/fresh-store)
        r (run s {:op :prepare-work-product :billable-hours 6
                  :work-product {:doc-id "W-2" :matter-id "M-1" :kind :準備書面}})]
    (is (= :done (:status r)))
    (is (= :commit (get-in r [:state :disposition])))
    (is (= :prepare-work-product (get-in r [:state :record :op])))
    (testing "the effect actually landed"
      (is (= :draft (:status (store/work-product s "W-2")))))
    (testing "and the ledger says so"
      (is (= [:commit] (mapv :disposition (store/ledger s)))))))

(deftest time-entries-accumulate-against-the-ceiling-through-the-actor
  (let [s (fx/fresh-store)]
    (is (= 12 (store/billed-hours s "M-1")))
    (run s {:op :record-time-entry :billable-hours 10
            :time-entry {:entry-id "T-9" :matter-id "M-1" :bengoshi-id "B-1"
                         :hours 10 :date "2026-07-20" :billable? true}})
    (is (= 22 (store/billed-hours s "M-1")))
    (testing "the next proposal is measured against the new total"
      (let [r (run s {:op :record-time-entry :billable-hours 19
                      :time-entry {:entry-id "T-10" :matter-id "M-1" :bengoshi-id "B-1"
                                   :hours 19 :date "2026-07-21" :billable? true}})]
        (is (actor/held? r))
        (is (= 22 (store/billed-hours s "M-1")) "nothing was written")))))

;; ---------------------------------------------------------------------------
;; Hold path
;; ---------------------------------------------------------------------------

(deftest a-held-proposal-changes-nothing-but-the-ledger
  (let [s (fx/fresh-store)
        before (store/work-product s "W-1")
        r (run s {:op :issue-work-product :doc-id "W-1"})]
    (is (actor/held? r))
    (is (= before (store/work-product s "W-1")) "the draft was not issued")
    (is (empty? (store/records-of s "C-1")))
    (testing "the refusal itself is on the record — a practice must be able to show what it declined"
      (let [[fact] (store/ledger s)]
        (is (= :hold (:disposition fact)))
        (is (= :issue-work-product (:op fact)))
        (is (contains? (set (map :rule (get-in fact [:verdict :violations])))
                       :work-product-not-reviewed))))))

(deftest an-unverified-person-cannot-drive-the-actor
  (let [s (fx/fresh-store)
        r (run s {:op :prepare-work-product :bengoshi-id "B-3" :billable-hours 1
                  :work-product {:doc-id "W-5" :matter-id "M-1"}})]
    (is (actor/held? r))
    (is (nil? (store/work-product s "W-5")))))

;; ---------------------------------------------------------------------------
;; Escalation + approval
;; ---------------------------------------------------------------------------

(deftest an-escalated-decision-parks-until-a-named-lawyer-resumes-it
  (let [s (fx/fresh-store)
        g (graph s)
        thread "T-accept"
        r (run-on g {:op :accept-representation :matter-id "M-1" :confidence 0.99} thread)]
    (testing "the run interrupts instead of committing"
      (is (actor/escalated? r))
      (is (empty? (store/records-of s "C-1"))))
    (testing "an anonymous approval is refused"
      (is (thrown? clojure.lang.ExceptionInfo (actor/approve! g thread {:by nil :on "2026-07-30"})))
      (is (thrown? clojure.lang.ExceptionInfo (actor/approve! g thread {:by "B-1"}))))
    (testing "a named, dated approval lands the effect"
      (let [r2 (actor/approve! g thread {:by "B-1" :on "2026-07-30"})]
        (is (= :done (:status r2)))
        (is (actor/committed? r2))
        (is (= "B-1" (get-in r2 [:state :record :approved-by])))
        (is (= "2026-07-30" (get-in r2 [:state :record :approved-on])))))
    (testing "and the ledger records the approval before the commit"
      (is (= [:approved :commit] (mapv :disposition (store/ledger s)))))))

(deftest trust-disbursement-requires-sign-off-and-then-moves-the-money
  (let [s (fx/fresh-store)
        g (graph s)
        thread "T-payout"
        entry {:entry-id "TR-9" :matter-id "M-1" :client-id "C-1" :direction :out
               :amount 200000 :currency "JPY" :account :trust :date "2026-07-30"
               :purpose :client-payout}
        r (run-on g {:op :disburse-trust :matter-id "M-1" :trust-entry entry
                       :confidence 0.99} thread)]
    (is (actor/escalated? r))
    (is (= 500000 (trust/balance s "M-1")) "nothing moved on the un-approved run")
    (actor/approve! g thread {:by "B-1" :on "2026-07-30"})
    (is (= 300000 (trust/balance s "M-1")))
    (testing "the approver is recorded on the money movement itself"
      (is (= "B-1" (:approved-by (first (filter #(= "TR-9" (:entry-id %))
                                                (store/trust-entries-of s "M-1")))))))))

(deftest the-full-drafting-lifecycle
  (testing "起案 → 弁護士の精査 → 発送 — each stage gated by the rule that applies to it"
    (let [s (fx/fresh-store)
          g (graph s)]
      (run-on g {:op :prepare-work-product :billable-hours 4
                   :work-product {:doc-id "W-7" :matter-id "M-1" :kind :準備書面}} "T-a")
      (is (= :draft (:status (store/work-product s "W-7"))))

      (testing "issuing the draft is refused"
        (is (actor/held? (run-on g {:op :issue-work-product :doc-id "W-7"} "T-b"))))

      (run-on g {:op :review-work-product :doc-id "W-7" :reviewed-by "B-1"
                   :reviewed-on "2026-07-30"} "T-c")
      (is (= :lawyer-reviewed (:status (store/work-product s "W-7"))))
      (is (= "B-1" (:reviewed-by (store/work-product s "W-7"))))

      (testing "now issuing is an escalated decision, and sign-off completes it"
        (let [r (run-on g {:op :issue-work-product :doc-id "W-7"} "T-d")]
          (is (actor/escalated? r))
          (is (= :lawyer-reviewed (:status (store/work-product s "W-7"))))
          (actor/approve! g "T-d" {:by "B-1" :on "2026-07-30"})
          (is (= :issued (:status (store/work-product s "W-7")))))))))

(deftest co-counsel-invitation-end-to-end
  (let [s (fx/fresh-store)
        g (graph s)
        grant {:grant-id "G-7" :matter-id "M-1" :grantee-bengoshi-id "B-2"
               :role :co-counsel :capabilities [:read :comment :draft]
               :expires-on "2026-10-28" :conflict-cleared? true
               :fee-split [{:bengoshi-id "B-1" :share 70 :role "主任・訴訟追行"}
                           {:bengoshi-id "B-2" :share 30 :role "労働法論点の起案"}]}]
    (testing "a referral fee is held outright, never merely escalated"
      (let [r (run-on g {:op :invite-partner-counsel :matter-id "M-1"
                           :grant (assoc grant :referral-fee 50000)} "T-bad")]
        (is (actor/held? r))
        (is (nil? (store/counsel-grant s "G-7")))))
    (testing "a work-based fee split is escalated, then granted"
      (let [r (run-on g {:op :invite-partner-counsel :matter-id "M-1" :grant grant} "T-ok")]
        (is (actor/escalated? r))
        (is (nil? (store/counsel-grant s "G-7")))
        (actor/approve! g "T-ok" {:by "B-1" :on "2026-07-30"})
        (is (some? (store/counsel-grant s "G-7")))
        (is (true? (store/active-grant? (store/counsel-grant s "G-7") :draft fx/today)))))))

(deftest remediating-a-lapsed-deadline-reopens-ordinary-work
  (let [s (fx/fresh-store)
        g (graph s)
        late {:today "2026-08-06"}
        work {:op :prepare-work-product :billable-hours 2
              :work-product {:doc-id "W-8" :matter-id "M-1"}}]
    (testing "ordinary work is blocked while the 控訴期間 sits lapsed"
      (is (actor/held? (actor/run-request! g (merge fx/request-base work) late "T-x"))))
    (actor/run-request! g (merge fx/request-base {:op :remediate-deadline :matter-id "M-1"
                                                  :deadline-id "D-1"})
                        late "T-y")
    (is (true? (:satisfied? (first (store/deadlines-of s "M-1")))))
    (testing "and flows again once the lapse is addressed"
      (let [r (actor/run-request! g (merge fx/request-base work) late "T-z")]
        (is (actor/committed? r) (pr-str (get-in r [:state :verdict])))
        (is (= :draft (:status (store/work-product s "W-8"))))))))

(deftest the-audit-trail-records-every-node
  (let [s (fx/fresh-store)
        r (run s {:op :prepare-work-product :billable-hours 3
                  :work-product {:doc-id "W-3" :matter-id "M-1"}})
        nodes (mapv :node (get-in r [:state :audit]))]
    (is (= [:advise :govern :commit] nodes))
    (is (some? (get-in r [:state :audit 1 :verdict :screen]))
        "the conflict screen the gate actually ran is retained, not just its verdict")))
