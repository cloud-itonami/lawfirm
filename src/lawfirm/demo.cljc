(ns lawfirm.demo
  "A sample practice — one open matter mid-flight, with the records a real
  一件記録 carries: a signed 利益相反 clearance, a 委任契約, recorded time, a
  預り金 balance and a live 控訴期間.

  It lives in `src` rather than `test` on purpose. The tests assert against
  this exact practice **and** `lawfirm.render-console` renders the sample page
  from it, so the numbers on the demo page are the numbers the test suite
  proves. A demo built from its own separate fixture drifts from the tested
  behaviour and becomes a screenshot of something that never ran."
  (:require [lawfirm.store :as store]))

(def today "2026-07-30")

(def counsel
  "B-1 is this practice's 弁護士. B-2 is a verified partner. B-3 and B-4 exist
  to prove the two ways a person stops being able to act: 業務停止 and a
  verification nobody refreshed."
  {:b1 {:bengoshi-id "B-1" :name "山田 太郎"
        :registration-number "第12345号" :bar-association "東京弁護士会"
        :admitted-on "2014-12-16" :status :active
        :verified-on "2026-04-01" :verification-source :nichibenren-directory
        :specializations ["labour" "corporate"] :jurisdictions ["JP-13"]
        :capacity-hours 60}
   :b2 {:bengoshi-id "B-2" :name "佐藤 花子"
        :registration-number "第23456号" :bar-association "第一東京弁護士会"
        :admitted-on "2018-12-14" :status :active
        :verified-on "2026-05-20" :verification-source :bar-association-direct
        :specializations ["labour" "family"] :jurisdictions ["JP-13" "JP-14"]
        :capacity-hours 40}
   :b3 {:bengoshi-id "B-3" :name "鈴木 一郎"
        :registration-number "第34567号" :bar-association "大阪弁護士会"
        :admitted-on "2010-12-15" :status :suspended
        :verified-on "2026-06-01" :verification-source :nichibenren-directory
        :specializations ["criminal"] :jurisdictions ["JP-27"]}
   :b4 {:bengoshi-id "B-4" :name "高橋 次郎"
        :registration-number "第45678号" :bar-association "横浜弁護士会"
        :admitted-on "2016-12-15" :status :active
        :verified-on "2024-01-10" :verification-source :nichibenren-directory
        :specializations ["labour"] :jurisdictions ["JP-14"]}})

(defn fresh-store
  "A practice mid-flight: one open matter with a cleared conflict check, a
  registered 委任契約, recorded time, a trust balance and a live 控訴期間."
  []
  (let [s (store/mem-store)]
    (doseq [b (vals counsel)] (store/register-bengoshi! s b))

    (store/register-client! s {:client-id "C-1" :name "株式会社甲野商事"
                               :kind :corporate :aliases ["甲野商事"]})
    (store/register-client! s {:client-id "C-2" :name "乙川工業株式会社"
                               :kind :corporate})

    (store/register-matter! s {:matter-id "M-1" :client-id "C-1" :bengoshi-id "B-1"
                               :name "丙山建設との請負代金請求"
                               :domain "corporate" :jurisdiction "JP-13"
                               :engagement "第一審の訴訟追行"
                               :max-billable-hours 40
                               :fee-agreement "委任契約書 2026-05-10 締結"
                               :status :open
                               :adverse-parties ["丙山建設株式会社"]
                               :related-parties []
                               :opened-on "2026-05-10"
                               :court "東京地方裁判所" :case-number "令和8年(ワ)第1234号"})

    (store/register-conflict-check! s {:check-id "CC-1" :matter-id "M-1"
                                       :cleared? true :hits []
                                       :screened-parties ["甲野商事" "丙山建設"]
                                       :decided-by "B-1" :decided-on "2026-05-09"})

    (store/register-time-entry! s {:entry-id "T-1" :matter-id "M-1" :bengoshi-id "B-1"
                                   :hours 12 :date "2026-06-02" :billable? true
                                   :narrative "訴状起案・証拠整理"})

    (store/register-trust-entry! s {:entry-id "TR-1" :matter-id "M-1" :client-id "C-1"
                                    :direction :in :amount 500000 :currency "JPY"
                                    :account :trust :date "2026-05-12"
                                    :purpose :retainer})

    (store/register-deadline! s {:deadline-id "D-1" :matter-id "M-1"
                                 :kind :appeal-civil :label "控訴期間"
                                 :due-on "2026-08-05" :critical? true :satisfied? false})

    (store/register-work-product! s {:doc-id "W-1" :matter-id "M-1"
                                     :kind :準備書面 :status :draft})
    s))

(def request-base
  {:client-id "C-1" :matter-id "M-1" :bengoshi-id "B-1"})

(def context {:today today})
