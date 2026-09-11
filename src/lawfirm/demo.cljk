(ns lawfirm.demo
  "A sample practice — one open matter mid-flight, with the records a real
  一件記録 carries: a signed 利益相反 clearance, a 委任契約, recorded time, a
  預り金 balance, a live 控訴期間, registered 送達先 and their transmissions,
  and a 相談 Q&A thread in each of the states the metrics distinguish.

  Two of those records are deliberately in a *bad* state, because a fixture
  where everything is fine proves only that the happy path renders: R-2's fax
  number was last verified in January and is stale as of `today`, and A-2 is
  reviewed but unsent — work sitting on a named 弁護士's desk rather than work
  never started.

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
    (store/register-work-product! s {:doc-id "W-9" :matter-id "M-1"
                                     :kind :準備書面 :status :issued
                                     :reviewed-by "B-1" :reviewed-on "2026-07-18"
                                     :object-ref "sha256:1f0c…"})

    ;; 送達先. R-1's fax was checked this month; R-2's was checked in January
    ;; and is therefore stale as of `today` — a live example of the state the
    ;; practice is supposed to find *before* it tries to send, which is why
    ;; `transmission/stale-channels` exists and why the console shows it.
    (store/register-recipient! s {:recipient-id "R-1" :matter-id "M-1"
                                  :name "東京地方裁判所 民事第○部" :role :court
                                  :channels {:fax {:number "03-XXXX-0001"
                                                   :verified-by "B-1"
                                                   :verified-on "2026-07-01"}
                                             :post {:address "東京都千代田区霞が関1-1-4"
                                                    :verified-by "B-1"
                                                    :verified-on "2026-07-01"}}})
    (store/register-recipient! s {:recipient-id "R-2" :matter-id "M-1"
                                  :name "丙山建設 訴訟代理人 戊田法律事務所"
                                  :role :opposing-counsel
                                  :channels {:fax {:number "03-XXXX-0002"
                                                   :verified-by "B-1"
                                                   :verified-on "2026-01-05"}}})

    (store/register-transmission! s {:transmission-id "TX-1" :matter-id "M-1"
                                     :direction :outbound :channel :fax
                                     :doc-id "W-9" :recipient-id "R-1"
                                     :sent-on "2026-07-20" :page-count 12
                                     :result :ok})
    (store/register-transmission! s {:transmission-id "TX-2" :matter-id "M-1"
                                     :direction :outbound :channel :post
                                     :doc-id "W-9" :recipient-id "R-2"
                                     :sent-on "2026-07-21" :page-count 12})
    (store/register-transmission! s {:transmission-id "IN-9001" :matter-id "M-1"
                                     :direction :inbound :channel :fax
                                     :sent-on "2026-07-25" :origin "03-XXXX-0002"
                                     :page-count 3 :digest "sha256:9ab3…"})

    ;; 相談 Q&A. Q-1 was answered; Q-2 is sitting on a 弁護士's desk in the
    ;; reviewed-but-not-sent state, which is the one the metrics separate out
    ;; because it fails differently from work that was never started.
    (store/register-qa-question! s {:question-id "Q-1" :matter-id "M-1"
                                    :client-id "C-1" :asked-on "2026-07-10"
                                    :channel :email :digest "sha256:7c21…"
                                    :kind :legal-advice :domain "corporate"})
    (store/register-qa-answer! s {:answer-id "A-1" :question-id "Q-1"
                                  :matter-id "M-1" :drafted-by "B-1"
                                  :drafted-on "2026-07-12" :status :sent
                                  :reviewed-by "B-1" :reviewed-on "2026-07-13"
                                  :sent-on "2026-07-14" :digest "sha256:44ef…"})
    (store/register-qa-question! s {:question-id "Q-2" :matter-id "M-1"
                                    :client-id "C-1" :asked-on "2026-07-27"
                                    :channel :email :digest "sha256:0b5d…"
                                    :kind :legal-advice :domain "corporate"})
    (store/register-qa-answer! s {:answer-id "A-2" :question-id "Q-2"
                                  :matter-id "M-1" :drafted-by "B-1"
                                  :drafted-on "2026-07-28" :status :lawyer-reviewed
                                  :reviewed-by "B-1" :reviewed-on "2026-07-29"
                                  :digest "sha256:c910…"})
    s))

(def request-base
  {:client-id "C-1" :matter-id "M-1" :bengoshi-id "B-1"})

(def context {:today today})
