(ns lawfirm.workspace-test
  (:require [clojure.test :refer [deftest is testing]]
            [lawfirm.actor :as actor]
            [lawfirm.fixture :as fx]
            [lawfirm.store :as store]
            [lawfirm.workspace :as workspace]))

(defn- fake-inbox [items]
  (reify workspace/InboundPort
    (-arrivals [_ since] (filterv #(>= (compare (:received-on %) since) 0) items))))

(defn- recorder []
  (let [seen (atom [])]
    [seen (reify
            workspace/CalendarPort
            (-publish-calendar [_ cal] (swap! seen conj [:calendar cal]))
            workspace/DrivePort
            (-publish-tree [_ matter-id tree] (swap! seen conj [:drive matter-id tree])))]))

;; ---------------------------------------------------------------------------
;; In — arrivals become requests, not records
;; ---------------------------------------------------------------------------

(def ^:private arrivals
  [{:id "9101" :channel :fax :received-on "2026-07-29" :origin "03-XXXX-0002"
    :page-count 3 :digest "sha256:a" :matter-id "M-1"}
   {:id "9102" :channel :email :received-on "2026-07-30" :origin "sender@example.jp"
    :digest "sha256:b" :matter-id "M-1"}])

(deftest arrivals-become-requests-the-gate-still-has-to-clear
  (let [rs (workspace/inbound-requests (fake-inbox arrivals) "2026-07-01"
                                       {:bengoshi-id "B-1" :client-id "C-1"})]
    (is (= 2 (count rs)))
    (is (every? #(= :record-inbound-transmission (:op %)) rs))
    (testing "a mail server's assertion is not yet a fact about the practice"
      (let [s (fx/fresh-store)
            before (count (store/transmissions-of s "M-1"))]
        (workspace/inbound-requests (fake-inbox arrivals) "2026-07-01"
                                    {:bengoshi-id "B-1" :client-id "C-1"})
        (is (= before (count (store/transmissions-of s "M-1"))))))))

(deftest a-request-built-from-an-arrival-carries-no-prose
  (let [r (first (workspace/inbound-requests
                  (fake-inbox [(assoc (first arrivals) :body "本文…")])
                  "2026-07-01" {:bengoshi-id "B-1" :client-id "C-1"}))]
    (doseq [k [:body :text :content :summary :message :prose]]
      (is (nil? (get (:transmission r) k))
          (str "an arrival's " k " must not survive into the request")))
    (is (= "sha256:a" (get-in r [:transmission :digest])))))

(deftest an-arrival-that-replays-produces-the-same-transmission-id
  (testing "so a gateway re-delivering its queue does not create a second record"
    (let [once (workspace/inbound-requests (fake-inbox arrivals) "2026-07-01" {})
          twice (workspace/inbound-requests (fake-inbox arrivals) "2026-07-01" {})]
      (is (= (map #(get-in % [:transmission :transmission-id]) once)
             (map #(get-in % [:transmission :transmission-id]) twice)))
      (is (= ["IN-9101" "IN-9102"]
             (map #(get-in % [:transmission :transmission-id]) once))))))

(deftest an-arrival-runs-through-the-actor-and-lands
  (let [s (fx/fresh-store)
        g (actor/build-graph {:store s})
        req (first (workspace/inbound-requests (fake-inbox arrivals) "2026-07-01"
                                               {:bengoshi-id "B-1" :client-id "C-1"}))
        result (actor/run-request! g req fx/context "t-inbound")]
    (is (actor/committed? result) (pr-str (get-in result [:state :verdict])))
    (is (some #(= "IN-9101" (:transmission-id %)) (store/transmissions-of s "M-1")))))

(deftest an-arrival-with-no-matter-can-be-triaged-at-the-front-door
  (let [c (workspace/arrival->consult
           {:id "9103" :channel :fax :received-on "2026-07-30" :digest "sha256:c"}
           "先週判決書が送達されました。控訴を検討しています。"
           {})]
    (is (= "CONSULT-9103" (:consult-id c)))
    (is (= :fax (:channel c)))
    (is (= :statutory-deadline (:urgency c))
        "the deadline markers must fire on a fax exactly as on a web form")
    (is (nil? (:body c)))
    (is (= "sha256:c" (:summary-digest c)))))

;; ---------------------------------------------------------------------------
;; Out
;; ---------------------------------------------------------------------------

(deftest publishing-uses-the-projections-id-scheme
  (let [[seen port] (recorder)
        s (fx/fresh-store)]
    (workspace/publish-docket! port s fx/today)
    (workspace/publish-matter-drive! port s "M-1")
    (is (= [:calendar :drive] (mapv first @seen)))
    (is (contains? (:calendar/events (second (first @seen))) "lawfirm-deadline-D-1"))
    (is (= "M-1" (second (second @seen))))))

;; ---------------------------------------------------------------------------
;; dispatch-plan — the destination comes from the record all the way to the wire
;; ---------------------------------------------------------------------------

(deftest the-plan-carries-the-registered-number-not-a-supplied-one
  (let [s (fx/fresh-store)
        plan (workspace/dispatch-plan s "TX-1")]
    (is (true? (:ok? plan)))
    (is (= :fax (:channel plan)))
    (is (= "03-XXXX-0001" (get-in plan [:recipient :coordinate :number])))
    (is (= "W-9" (get-in plan [:document :doc-id])))
    (testing "and it follows the record when the record changes"
      (store/register-recipient! s (assoc-in (store/recipient s "R-1")
                                             [:channels :fax :number] "03-XXXX-1111"))
      (is (= "03-XXXX-1111"
             (get-in (workspace/dispatch-plan s "TX-1") [:recipient :coordinate :number]))))))

(deftest a-plan-is-refused-rather-than-returned-half-built
  (let [s (fx/fresh-store)]
    (is (= :unknown-transmission (:reason (workspace/dispatch-plan s "TX-404"))))
    (is (= :not-outbound (:reason (workspace/dispatch-plan s "IN-9001"))))
    (testing "an unissued document cannot be dispatched even if a row exists"
      (store/register-transmission! s {:transmission-id "TX-3" :matter-id "M-1"
                                       :direction :outbound :channel :fax
                                       :doc-id "W-1" :recipient-id "R-1"})
      (is (= :work-product-not-issued (:reason (workspace/dispatch-plan s "TX-3")))))
    (testing "and a row naming a recipient nobody registered cannot either"
      (store/register-transmission! s {:transmission-id "TX-4" :matter-id "M-1"
                                       :direction :outbound :channel :fax
                                       :doc-id "W-9" :recipient-id "R-404"})
      (is (= :recipient-not-registered (:reason (workspace/dispatch-plan s "TX-4")))))))
