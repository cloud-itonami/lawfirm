(ns lawfirm.projection
  "The practice record projected into the workspace types a host already
  speaks — `calendar.model` events, a `drive.model` tree, and a portable
  summary map for a portal.

  ## One way, always

  Nothing here reads a workspace back into the record. That is the whole
  design and it is worth stating as a rule rather than an omission: the
  governor gates on `deadline/with-status`, `trust/balance`,
  `conflict/screen-matter`. The moment a calendar could move a 期限, the date
  the gate enforces and the date the 弁護士 sees would have two sources, and
  the screen would be the one they believe. `lawfirm.console` refuses
  display-only computation for exactly this reason; a writable projection
  would be the same mistake with a network hop in the middle.

  So: the record is upstream of the workspace. A 期限 changes by
  `:remediate-deadline` going through the gate, and the calendar finds out
  afterwards.

  ## A 期限 is a date, not an instant

  `calendar.model` events carry ISO time strings, and a 出訴期限 is a calendar
  day — it is not midnight, and it is not midnight-in-a-timezone. The
  projection emits `:due-on` verbatim and marks the event `:all-day? true`.
  A host that converts these to instants will move deadlines across a date
  boundary for anyone east or west of it, which is the same class of bug
  `lawfirm.date` exists to avoid (CLAUDE.md: no `java.time`, no `js/Date`)."
  (:require [calendar.model :as calendar]
            [kotoba.lang.text :as str]
            [drive.model :as drive]
            [lawfirm.deadline :as deadline]
            [lawfirm.qa :as qa]
            [lawfirm.store :as store]
            [lawfirm.transmission :as transmission]))

;; ---------------------------------------------------------------------------
;; Calendar
;; ---------------------------------------------------------------------------

(def ^:private status-prefix
  "The status travels in the title because a calendar row is often all a
  弁護士 sees on a phone, and 徒過 that reads like any other entry is 徒過
  nobody acts on."
  {:breached "【徒過】" :at-risk "【期限間近】" :pending "" :satisfied "【完了】"
   :unknown "【要確認】"})

(defn deadline-event
  "One 期限 as a `calendar.model` event. `:lawfirm/*` keys carry the record
  identity back, so a host that shows an event can link to the matter without
  parsing the title."
  [d]
  (calendar/event
   (str "lawfirm-deadline-" (:deadline-id d))
   {:calendar/title (str (get status-prefix (:status d) "")
                         (or (:label d) (some-> (:kind d) name) "期限")
                         "　" (:matter-id d))
    :calendar/start (:due-on d)
    :calendar/end (:due-on d)
    :calendar/all-day? true
    :lawfirm/deadline-id (:deadline-id d)
    :lawfirm/matter-id (:matter-id d)
    :lawfirm/critical? (boolean (:critical? d))
    :lawfirm/status (:status d)
    :lawfirm/basis (let [{:keys [cite trigger-date]} (:derived-from d)]
                     (when cite (str cite (when trigger-date
                                            (str " / 起算 " trigger-date)))))}))

(defn deadline-events
  "Every 期限 in the practice, with the status the gate computes."
  [store today]
  (mapv deadline-event (deadline/firm-calendar store today)))

(defn deadline-calendar
  "The whole docket as a `calendar.model` calendar, ready for a host to
  publish. Building the calendar here rather than handing a host a bag of
  events means the id scheme is defined once — a host that invented its own
  would create a duplicate on every publish."
  ([store today] (deadline-calendar store today "lawfirm-docket"))
  ([store today cal-id]
   (reduce calendar/add-event
           (calendar/calendar cal-id {:calendar/title "期限・期日（事務所全体）"})
           (deadline-events store today))))

;; ---------------------------------------------------------------------------
;; Drive
;; ---------------------------------------------------------------------------

(def ^:private matter-sections
  "The 一件記録 sections a matter folder carries. Fixed rather than derived
  from what happens to exist, so an empty 送達 folder is visibly empty
  instead of silently absent."
  [["work-products" "書面"]
   ["transmissions" "送達"]
   ["qa" "相談Q&A"]
   ["trust" "預り金"]])

(defn- folder-id [matter-id suffix] (str "lawfirm/" matter-id "/" suffix))

(defn matter-drive
  "A matter's 一件記録 as a `drive.model` tree: one folder per matter, the
  fixed sections above, and a file per registered 書面.

  The files carry `:drive/object-ref` from the work product's own
  `:object-ref` and nothing else — this namespace never invents a location
  for bytes it has not been told about. A file whose ref is nil is a document
  the record knows exists and the drive cannot serve, which is a true
  statement and a more useful one than a broken link."
  ([store matter-id] (matter-drive store matter-id (str "lawfirm-" matter-id)))
  ([store matter-id drive-id]
   (let [m (store/matter store matter-id)
         root (folder-id matter-id "")
         base (-> (drive/drive drive-id {:drive/title (str matter-id "　" (:name m))})
                  (drive/add-item (drive/folder root {:drive/title (str matter-id "　" (:name m))})))
         with-sections
         (reduce (fn [d [suffix title]]
                   (let [fid (folder-id matter-id suffix)]
                     (-> d
                         (drive/add-item (drive/folder fid {:drive/title title}))
                         (drive/add-child root fid))))
                 base
                 matter-sections)
         docs-folder (folder-id matter-id "work-products")]
     (reduce (fn [d w]
               (let [fid (str "lawfirm/" matter-id "/work-products/" (:doc-id w))]
                 (-> d
                     (drive/add-item
                      (drive/file fid {:drive/title (str (:doc-id w) "　"
                                                         (some-> (:kind w) name))
                                       :drive/object-ref (:object-ref w)
                                       :lawfirm/doc-id (:doc-id w)
                                       :lawfirm/status (:status w)}))
                     (drive/add-child docs-folder fid))))
             with-sections
             (store/work-products-of store matter-id)))))

;; ---------------------------------------------------------------------------
;; Portal summary
;; ---------------------------------------------------------------------------

(defn matter-summary
  "One matter, reduced to what a portal outside this repository can render
  without depending on `lawfirm.store`.

  Every number here is read through the function the governor gates on, so a
  portal showing these cannot disagree with the practice console showing the
  same matter. That property is the reason this is a projection rather than a
  view the portal builds itself."
  [store matter-id today]
  (let [m (store/matter store matter-id)
        ds (deadline/with-status store matter-id today)
        counts (frequencies (map :status ds))]
    {:matter-id matter-id
     :name (:name m)
     :status (:status m)
     :court (:court m)
     :case-number (:case-number m)
     :bengoshi-id (:bengoshi-id m)
     :deadlines {:breached (get counts :breached 0)
                 :at-risk (get counts :at-risk 0)
                 :pending (get counts :pending 0)
                 :satisfied (get counts :satisfied 0)
                 :next-due (->> ds
                                (remove :satisfied?)
                                (sort-by :due-on)
                                first
                                :due-on)}
     :qa (qa/metrics store matter-id)
     :transmissions {:by-channel (transmission/channel-mix store matter-id)
                     :unconfirmed (count (transmission/unconfirmed store matter-id))
                     :stale-channels (count (transmission/stale-channels store matter-id today))
                     ;; An incident count, not a metric. One row here is a
                     ;; document that reached somewhere the record did not
                     ;; name — a confidentiality event with duties attached,
                     ;; which is why it is carried separately from
                     ;; `:undeterminable` below rather than summed with it.
                     :misdirected (count (transmission/misdirected store matter-id))
                     :undeterminable (count (transmission/undeterminable-direction
                                             store matter-id))}}))

(defn practice-summary
  "The whole practice as one portable map — the shape a portal (kaisya)
  consumes. Plain data on purpose: the portal renders it without a dependency
  on this repository, and this repository owns what the numbers mean."
  [store today]
  (let [matters (sort-by :matter-id (store/matters store))
        summaries (mapv #(matter-summary store (:matter-id %) today) matters)]
    {:as-of today
     :matters summaries
     :totals {:matters (count summaries)
              :breached (reduce + 0 (map #(get-in % [:deadlines :breached]) summaries))
              :at-risk (reduce + 0 (map #(get-in % [:deadlines :at-risk]) summaries))
              :qa-open (reduce + 0 (map #(+ (get-in % [:qa :by-state :unanswered] 0)
                                            (get-in % [:qa :by-state :awaiting-review] 0)
                                            (get-in % [:qa :by-state :awaiting-send] 0))
                                        summaries))
              :transmissions-unconfirmed
              (reduce + 0 (map #(get-in % [:transmissions :unconfirmed]) summaries))
              :misdirected
              (reduce + 0 (map #(get-in % [:transmissions :misdirected]) summaries))
              :stale-channels
              (reduce + 0 (map #(get-in % [:transmissions :stale-channels]) summaries))}}))

;; ---------------------------------------------------------------------------
;; Inbound
;; ---------------------------------------------------------------------------

(defn inbound->intake
  "Map an inbound transmission onto the argument shape
  `lawfirm.intake/->consult-record` takes, so a fax that turns out to be a new
  enquiry enters triage as a fax rather than as an untyped event.

  The digest travels; the prose does not, because there is none here to
  travel — see `lawfirm.transmission/inbound-record`."
  [t triage]
  {:consult-id (str "CONSULT-" (:transmission-id t))
   :received-on (:sent-on t)
   :channel (transmission/->consult-channel t)
   :digest (:digest t)
   :triage triage})

(defn describe-origin
  "A human-readable origin for an inbound row, without ever printing a full
  fax number into a UI that may be shared."
  [t]
  (let [o (str (:origin t))]
    (cond
      (str/blank? o) "発信元不明"
      (= :fax (:channel t)) (str "…" (apply str (take-last 4 o)))
      :else o)))
