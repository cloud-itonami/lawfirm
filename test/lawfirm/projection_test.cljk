(ns lawfirm.projection-test
  (:require [calendar.model :as calendar]
            [clojure.test :refer [deftest is testing]]
            [drive.model :as drive]
            [lawfirm.deadline :as deadline]
            [lawfirm.fixture :as fx]
            [lawfirm.projection :as projection]
            [lawfirm.qa :as qa]
            [lawfirm.store :as store]
            [lawfirm.trust :as trust]))

;; ---------------------------------------------------------------------------
;; One way
;; ---------------------------------------------------------------------------

(deftest projecting-does-not-touch-the-record
  (testing "the property the whole design rests on — a workspace cannot move a 期限"
    (let [s (fx/fresh-store)
          before (store/snapshot s)]
      (projection/deadline-calendar s fx/today)
      (projection/matter-drive s "M-1")
      (projection/practice-summary s fx/today)
      (is (= before (store/snapshot s))))))

;; ---------------------------------------------------------------------------
;; Calendar
;; ---------------------------------------------------------------------------

(deftest a-deadline-becomes-an-all-day-event-on-its-own-date
  (let [ev (first (projection/deadline-events (fx/fresh-store) fx/today))]
    (is (= "2026-08-05" (:calendar/start ev)))
    (is (= "2026-08-05" (:calendar/end ev)))
    (is (true? (:calendar/all-day? ev))
        "a 出訴期限 is a calendar day, not midnight in some timezone")
    (testing "and it carries the record identity so a host need not parse the title"
      (is (= "D-1" (:lawfirm/deadline-id ev)))
      (is (= "M-1" (:lawfirm/matter-id ev)))
      (is (true? (:lawfirm/critical? ev))))))

(deftest the-status-the-gate-computes-is-the-status-on-the-event
  (let [s (fx/fresh-store)]
    (testing "at-risk today"
      (let [ev (first (projection/deadline-events s fx/today))]
        (is (= :at-risk (:lawfirm/status ev)))
        (is (re-find #"期限間近" (:calendar/title ev)))))
    (testing "breached once the date has passed — read through deadline/firm-calendar"
      (let [later "2026-09-01"
            ev (first (projection/deadline-events s later))]
        (is (= :breached (:lawfirm/status ev)))
        (is (= :breached (:status (first (deadline/firm-calendar s later))))
            "same function, so the calendar and the docket cannot disagree")
        (is (re-find #"徒過" (:calendar/title ev)))))))

(deftest publishing-twice-converges-instead-of-duplicating
  (testing "the event id is derived from the deadline id, not generated"
    (let [s (fx/fresh-store)
          a (projection/deadline-calendar s fx/today)
          b (projection/deadline-calendar s fx/today)]
      (is (= a b))
      (is (= 1 (count (:calendar/events a))))
      (is (some? (calendar/event-by-id a "lawfirm-deadline-D-1"))))))

;; ---------------------------------------------------------------------------
;; Drive
;; ---------------------------------------------------------------------------

(deftest a-matter-folder-has-its-sections-even-when-they-are-empty
  (let [d (projection/matter-drive (fx/fresh-store) "M-1")
        root "lawfirm/M-1/"
        titles (set (map :drive/title (drive/children d root)))]
    (is (= #{"書面" "送達" "相談Q&A" "預り金"} titles)
        "an empty 送達 folder must be visibly empty, not absent")))

(deftest every-registered-document-appears-and-keeps-its-status
  (let [d (projection/matter-drive (fx/fresh-store) "M-1")
        docs (drive/children d "lawfirm/M-1/work-products")]
    (is (= ["W-1" "W-9"] (mapv :lawfirm/doc-id docs)))
    (is (= [:draft :issued] (mapv :lawfirm/status docs)))))

(deftest a-document-with-no-bytes-is-listed-with-no-ref
  (testing "a true statement beats a broken link"
    (let [d (projection/matter-drive (fx/fresh-store) "M-1")
          by-id (into {} (map (juxt :lawfirm/doc-id identity))
                      (drive/children d "lawfirm/M-1/work-products"))]
      (is (nil? (:drive/object-ref (get by-id "W-1"))))
      (is (= "sha256:1f0c…" (:drive/object-ref (get by-id "W-9")))))))

;; ---------------------------------------------------------------------------
;; Portal summary
;; ---------------------------------------------------------------------------

(deftest the-summary-reads-the-same-functions-the-gate-does
  (let [s (fx/fresh-store)
        m (projection/matter-summary s "M-1" fx/today)]
    (is (= "M-1" (:matter-id m)))
    (is (= 1 (get-in m [:deadlines :at-risk])))
    (is (= "2026-08-05" (get-in m [:deadlines :next-due])))
    (is (= (qa/metrics s "M-1") (:qa m)))
    (is (= {:fax 2 :post 1} (get-in m [:transmissions :by-channel])))
    (is (= 1 (get-in m [:transmissions :unconfirmed])))
    (is (= 1 (get-in m [:transmissions :stale-channels])))
    (testing "and it moves when the record moves"
      (store/register-deadline! s {:deadline-id "D-1" :matter-id "M-1"
                                   :kind :appeal-civil :due-on "2026-08-05"
                                   :critical? true :satisfied? true})
      (is (= 0 (get-in (projection/matter-summary s "M-1" fx/today)
                       [:deadlines :at-risk]))))))

(deftest the-practice-summary-totals-what-the-matters-hold
  (let [p (projection/practice-summary (fx/fresh-store) fx/today)]
    (is (= fx/today (:as-of p)))
    (is (= 1 (get-in p [:totals :matters])))
    (is (= 0 (get-in p [:totals :breached])))
    (is (= 1 (get-in p [:totals :at-risk])))
    (is (= 1 (get-in p [:totals :qa-open])) "Q-2 is reviewed but unsent")
    (is (= 1 (get-in p [:totals :transmissions-unconfirmed])))
    (is (= 1 (get-in p [:totals :stale-channels])))))

(deftest the-summary-is-plain-data
  (testing "so a portal can render it without depending on this repository"
    (let [p (projection/practice-summary (fx/fresh-store) fx/today)]
      (is (= p (read-string (pr-str p)))))))

(deftest a-breached-matter-is-visible-from-the-portal-summary
  (let [s (fx/fresh-store)
        p (projection/practice-summary s "2026-09-01")]
    (is (= 1 (get-in p [:totals :breached])))
    (is (= 1 (count (filter #(pos? (get-in % [:deadlines :breached])) (:matters p)))))))

;; ---------------------------------------------------------------------------
;; Inbound
;; ---------------------------------------------------------------------------

(deftest an-inbound-row-maps-onto-the-intake-shape-without-prose
  (let [t (store/transmissions-of (fx/fresh-store) "M-1")
        inbound (first (filter #(= :inbound (:direction %)) t))
        arg (projection/inbound->intake inbound {:domain "corporate" :urgency :routine})]
    (is (= "CONSULT-IN-9001" (:consult-id arg)))
    (is (= :fax (:channel arg)))
    (is (= "sha256:9ab3…" (:digest arg)))
    (is (nil? (:body arg)))))

(deftest a-fax-origin-is-not-printed-in-full
  (let [inbound (first (filter #(= :inbound (:direction %))
                               (store/transmissions-of (fx/fresh-store) "M-1")))]
    (is (= "…0002" (projection/describe-origin inbound))
        "a console is shared; a full number in it is a number that leaves the practice")
    (is (= "発信元不明" (projection/describe-origin {:channel :fax})))))

;; ---------------------------------------------------------------------------
;; Guard: the trust ledger is not projected
;; ---------------------------------------------------------------------------

(deftest money-is-summarised-by-count-not-copied-into-the-workspace
  (testing "the drive gets a 預り金 folder, not the 預り金 ledger"
    (let [s (fx/fresh-store)
          d (projection/matter-drive s "M-1")
          m (projection/matter-summary s "M-1" fx/today)]
      (is (empty? (drive/children d "lawfirm/M-1/trust")))
      (is (nil? (:trust m)))
      (is (pos? (trust/balance s "M-1"))
          "the balance exists — it is deliberately not in the projection"))))

(deftest a-misdirection-reaches-the-portal-summary-as-an-incident
  (let [s (fx/fresh-store)]
    (is (= 0 (get-in (projection/matter-summary s "M-1" fx/today)
                     [:transmissions :misdirected])))
    (store/register-recipient! s (assoc-in (store/recipient s "R-1")
                                           [:channels :fax :number] "03-1234-5678"))
    (store/register-transmission!
     s (assoc (first (filter #(= "TX-1" (:transmission-id %))
                             (store/transmissions-of s "M-1")))
              :misdirected? true :direction-check :mismatch
              :dialled "03-9999-0000"))
    (is (= 1 (get-in (projection/matter-summary s "M-1" fx/today)
                     [:transmissions :misdirected])))
    (is (= 1 (get-in (projection/practice-summary s fx/today) [:totals :misdirected])))))

(deftest an-unverifiable-destination-is-counted-apart-from-an-incident
  (let [s (fx/fresh-store)]
    (store/register-transmission!
     s (assoc (first (filter #(= "TX-2" (:transmission-id %))
                             (store/transmissions-of s "M-1")))
              :direction-check :undeterminable :result :ok))
    (let [t (:transmissions (projection/matter-summary s "M-1" fx/today))]
      (is (= 1 (:undeterminable t)))
      (is (= 0 (:misdirected t))
          "a gap in the evidence is not a confidentiality event"))))
