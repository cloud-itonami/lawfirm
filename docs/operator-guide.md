# 運用ガイド — 弁護士が実務で使う手順

`lawfirm` は「受任から終結までを、**弁護士の判断を必ず経由する形で**記録・監査する
事務所 OS」であって、法律事務を代行するソフトではない。本ガイドは、
その前提のもとで実際にどう回すかを書く。

前提の技術的な説明は [`README.md`](../README.md)、提携弁護士の獲得は
[`docs/partner-recruitment.md`](partner-recruitment.md)。

---

## 0. このソフトが絶対にしないこと

| しないこと | 根拠 |
|---|---|
| 裁判所・登記所への提出 | actor の `:effect` は `:propose` のみ（HARD invariant 1） |
| 送金・出金の実行 | 同上。預り金の出金は弁護士の承認を経た**記録**であって、実際の送金は別 |
| 署名・書面の外部送付 | 同上 |
| 弁護士が精査していない書面の発出 | HARD invariant 10（法務省2023年ガイドライン） |
| 紹介料の授受 | HARD invariant 9（職務基本規程13条・双方向） |

`:sign` / `:file` という capability は存在するが、これは
「その人が自分でそれを行う権限を持つことの記録」であって、actor が代わりに実行する
という意味ではない。

---

## 1. 事務所のセットアップ（1回だけ）

### 1.1 弁護士の登録

```clojure
(require '[lawfirm.store :as store])

(def s (store/mem-store))

(store/register-bengoshi!
 s {:bengoshi-id "B-1" :name "山田 太郎"
    :registration-number "第12345号"
    :bar-association "東京弁護士会"
    :admitted-on "2014-12-16"
    :status :active
    :verified-on "2026-04-01"                          ;; 名簿を実際に見た日
    :verification-source :nichibenren-directory        ;; 出典（本人申告は不可）
    :specializations ["labour" "corporate"]
    :jurisdictions ["JP-13"]})
```

`:verified-on` は**365日で失効する**（`partner/verification-validity-days`）。
登録取消・業務停止は日弁連から通知されないので、こちらから年次で見に行く。
失効した状態で操作しようとすると `:verification-stale` で HARD hold になる。

### 1.2 依頼者の登録

```clojure
(store/register-client!
 s {:client-id "C-1" :name "株式会社甲野商事" :kind :corporate
    :aliases ["甲野商事" "甲野"]})   ;; 名寄せの穴を塞ぐのは :aliases だけ
```

---

## 2. 受任前 — 利益相反チェックが先、受任判断が後

### 2.1 事件を仮登録して、相手方を書く

```clojure
(store/register-matter!
 s {:matter-id "M-1" :client-id "C-1" :bengoshi-id "B-1"
    :name "丙山建設との請負代金請求"
    :domain "corporate" :jurisdiction "JP-13"
    :status :prospective
    :adverse-parties ["丙山建設株式会社"]
    :related-parties ["丙山 太郎"]        ;; 代表者・保証人・関連会社など
    :engagement "第一審の訴訟追行"
    :max-billable-hours 40})
```

**`:adverse-parties` と `:related-parties` の記載が、そのままスクリーンの精度になる。**
書かれていない相手方は検出されない。

### 2.2 スクリーンを走らせる

```clojure
(require '[lawfirm.conflict :as conflict])
(conflict/screen-matter s (store/matter s "M-1"))
;; => {:cleared? false
;;     :hits [{:rule :adverse-to-current-client :detail "相手方が現依頼者（事件 M-7）と一致（28条2号）" ...}]
;;     :screened-parties [...]}
```

検出される類型と根拠条文は [`lawfirm.conflict/screen`](../src/lawfirm/conflict.cljk)
の docstring に一覧がある（27条1号・2号・3号、28条2号・3号・4号、共同受任先の抵触）。

> **スクリーンが clean であることは必要条件であって十分条件ではない。**
> 氏名の一致しか見ておらず、資本関係・親族関係・実質的支配は見ていない。
> だから次の 2.3 が別の手順として存在する。

### 2.3 弁護士が判断を記録する

```clojure
(store/register-conflict-check!
 s {:check-id "CC-1" :matter-id "M-1"
    :cleared? true
    :hits []                       ;; スクリーン結果を保存
    :screened-parties ["甲野商事" "丙山建設"]
    :decided-by "B-1"              ;; 判断した弁護士（必須）
    :decided-on "2026-05-09"})     ;; 判断日（必須）
```

`:decided-by` か `:decided-on` が欠けている記録は**判断として扱われない**
（`conflict/cleared-by-human?` が false を返し、HARD invariant 4 で hold）。
27条・28条は人の義務であってチェックボックスの義務ではない。

**重要**: この記録は「判断した日の事実」に対するものであって、永続的な免罪符ではない。
後日この事務所が相手方の事件を受任すれば、記録は残ったまま
`:conflict-hit-live`（HARD invariant 5）で hold される。

### 2.4 受任

```clojure
(require '[lawfirm.actor :as actor])
(def g (actor/build-graph {:store s}))

(def r (actor/run-request! g
        {:client-id "C-1" :matter-id "M-1" :bengoshi-id "B-1"
         :op :accept-representation}
        {:today "2026-05-10"}
        "thread-accept-M-1"))

(actor/escalated? r)  ;; => true — 受任は必ず弁護士の承認を要する
```

承認するまで matter の `:status` は変わらない。

```clojure
(actor/approve! g "thread-accept-M-1" {:by "B-1" :on "2026-05-10"})
;; 承認者名と日付が台帳と record の両方に記録される。匿名承認は例外を投げる
```

受任したら**委任契約書を登録する**。これがないと有償業務が一切計上できない
（HARD invariant 6、規程30条）。

```clojure
(store/register-matter! s (assoc (store/matter s "M-1")
                                 :fee-agreement "委任契約書 2026-05-10 締結"))
```

---

## 3. 期限の登録 — ここが最も落ちる

```clojure
(require '[lawfirm.deadline :as deadline])

;; 統計上の期間から導出する。起算点を書き、条文を残す
(store/register-deadline!
 s (deadline/draft "D-1" "M-1" :appeal-civil "2026-07-22"))
;; => {:deadline-id "D-1" :due-on "2026-08-05" :critical? true
;;     :derived-from {:trigger-date "2026-07-22" :trigger-event "判決書の送達"
;;                    :cite "民訴法285条"}}
```

- 民法140条の**初日不算入**が入っている。7月22日送達の2週間は8月5日まで。
- 期間が月・年のものは民法143条の暦計算＋月末クランプ。
- 民法142条（末日が休日）は**休日カレンダーを渡したときだけ**適用される。
  渡さない場合は延長しない側に倒す（早い方向の誤差は事故にならない）。

  ```clojure
  (deadline/compute-due :appeal-civil "2026-07-22" {:holiday? my-holiday-set})
  ```

  `:holiday?` は日付の集合でも関数でもよい。国民の祝日は法改正で動き、
  裁判所の年末年始閉庁は法定でもないので、このライブラリは表を持たない。

`deadline/statutory` に主な期間（控訴・上告・即時抗告・刑事控訴・労働審判異議・
取消訴訟出訴期間・消滅時効3種）が条文付きで載っている。
**これは起案の補助であって権威ではない。登録した弁護士がその期限を所有する。**

### 期限が徒過したとき

重要期限が徒過している事件では、通常業務が HARD hold される
（invariant 12）。徒過を黙って上塗りできないようにするため。
使えるのは対応そのものにあたる操作だけ:

- `:remediate-deadline` — 是正を記録して `:satisfied?` を立てる
- `:file-with-court` — 提出する
- `:withdraw-representation` — 辞任する
- `:record-time-entry` — 時間の記録（記録を止めると徒過の経緯が残らなくなるので許可）

---

## 4. 日常業務

### 4.1 書面 — 起案 → 精査 → 発出

```clojure
;; 起案（commit）
(actor/run-request! g {:client-id "C-1" :matter-id "M-1" :bengoshi-id "B-1"
                       :op :prepare-work-product :billable-hours 6
                       :work-product {:doc-id "W-2" :matter-id "M-1" :kind :準備書面}}
                    {:today "2026-07-20"} "t1")
;; => :draft

;; いきなり発出しようとすると HOLD
(actor/held? (actor/run-request! g {... :op :issue-work-product :doc-id "W-2"} ctx "t2"))
;; => true  (:work-product-not-reviewed)

;; 弁護士が自ら精査（commit、精査した弁護士が記録される）
(actor/run-request! g {... :op :review-work-product :doc-id "W-2"
                       :reviewed-by "B-1" :reviewed-on "2026-07-29"} ctx "t3")
;; => :lawyer-reviewed

;; 発出は escalate → 承認して初めて :issued
(actor/run-request! g {... :op :issue-work-product :doc-id "W-2"} ctx "t4")
(actor/approve! g "t4" {:by "B-1" :on "2026-07-30"})
```

精査者が有効な弁護士登録として確認できない場合は、`:lawyer-reviewed` になっていても
発出が hold される（`:reviewer-not-counsel`）。

### 4.1.5 送達 — 宛先を「登録」してから「送る」

発出（`:issue-work-product`）と送達（`:transmit-work-product`）は**別の行為**。
発出は「外に出してよい」という判断、送達は「誰に・どの経路で出したか」の記録。
同じ書面が裁判所・相手方代理人・依頼者へ行くのは普通のことで、それぞれ別の記録になる。

**宛先は送信時に入力しない。** 先に登録する。

```clojure
;; 宛先の登録。確認した弁護士と確認日が必須（確認者が有効登録でなければ HOLD）
(actor/run-request! g {:client-id "C-1" :matter-id "M-1" :bengoshi-id "B-1"
                       :op :register-recipient
                       :recipient {:recipient-id "R-1" :matter-id "M-1"
                                   :name "東京地方裁判所 民事第○部" :role :court
                                   :channels {:fax {:number "03-XXXX-0001"
                                                    :verified-by "B-1"
                                                    :verified-on "2026-07-01"}}}}
                    ctx "t5")

;; 送達は escalate。宛先は recipient-id で指す（番号は書かない）
(actor/run-request! g {... :op :transmit-work-product :doc-id "W-9"
                       :transmission {:transmission-id "TX-1" :matter-id "M-1"
                                      :direction :outbound :channel :fax
                                      :doc-id "W-9" :recipient-id "R-1"
                                      :sent-on "2026-07-30" :page-count 12}}
                    ctx "t6")
(actor/approve! g "t6" {:by "B-1" :on "2026-07-30"})
```

hold になる主なケース:

| 症状 | 規則 | 対処 |
|---|---|---|
| 宛先が登録されていない | `:recipient-not-registered` | 先に `:register-recipient` |
| 確認から180日超 | `:recipient-channel-unverified` | 弁護士が再確認して `:verified-on` を更新 |
| 書面がまだ `:draft` | `:work-product-not-issued` | 先に精査 → 発出 |
| 訴状を裁判所へ FAX | `:channel-forbidden-for-document` | 経路を変える（民事訴訟規則3条1項） |

**制約表に載っていない組合せは「可」ではない。** 表は確信を持って「不可」と言える
ものだけを載せている。だから送達は確信度に関わらず必ず弁護士の承認を要する。

受信（FAX・郵便・メール）は `:record-inbound-transmission` で記録する。**承認は不要**
だが、**本文は記録に載せられない**（`:digest` のみ。載せると `:prose-in-record` で hold）。
コンソールに出る発信元は末尾4桁だけ——コンソールは共有されるものなので。

### 4.1.55 送信結果の記録

送達を出したら、経路が返してきた結果を記録する。**承認は不要**（結果は判断ではない）。

```clojure
(actor/run-request! g {... :op :confirm-transmission
                       :transmission-confirmation
                       {:transmission-id "TX-1" :matter-id "M-1"
                        :result :ok           ; :ok / :failed / :pending
                        :provider :dropbox-fax
                        :provider-id "…"       ; 経路側の識別子
                        :provider-status "S"   ; 経路が返した生の状態
                        :dialled "03-1234-5678" ; 経路が「実際に送った先」と報告した番号
                        :confirmed-on "2026-07-30"}} ctx "t11")
```

`:dialled` は**照合のためにある**。`transmission/direction-check` が登録済みの宛先と
突き合わせて `:match` / `:mismatch` / `:undeterminable` を返し、`:mismatch` なら
`:misdirected? true` が記録に載る。

**誤送信でも commit される。** 書面は既に出ており、残っているのは記録を正しくすることだけ。
hold にしたら事故の証跡が消える。コンソールには「誤送信」として赤で出る。

`:undeterminable`（照合不能）は**事故ではない**。経路が宛先を報告しなかった、あるいは
その経路に登録済みの宛先が無かった、という証跡の欠落で、別の列に数える。
「照合していない」を「照合して問題なし」と同じに見せないため。

### 4.1.6 相談 Q&A — 回答は書面と同じはしごを通る

「短いから」「メールだから」で軽い経路にしない。回答は 起案 → 弁護士精査 → 送信。

```clojure
(actor/run-request! g {... :op :record-qa-question
                       :qa-question {:question-id "Q-1" :matter-id "M-1"
                                     :client-id "C-1" :asked-on "2026-07-27"
                                     :channel :email :digest "sha256:…"
                                     :kind :legal-advice}} ctx "t7")   ; 承認不要
(actor/run-request! g {... :op :draft-qa-answer :billable-hours 1
                       :qa-answer {:answer-id "A-1" :question-id "Q-1"
                                   :matter-id "M-1" :drafted-by "B-1"}} ctx "t8")
(actor/run-request! g {... :op :review-qa-answer :answer-id "A-1"
                       :reviewed-by "B-1" :reviewed-on "2026-07-29"} ctx "t9")
(actor/run-request! g {... :op :send-qa-answer :answer-id "A-1"
                       :sent-on "2026-07-30"} ctx "t10")
(actor/approve! g "t10" {:by "B-1" :on "2026-07-30"})
```

**受任前の相談**（`:matter-id` が無い質問）は、事件単位の利益相反判定では捕捉されない。
その相談者に対する日付入りのスクリーン（`:screened-by` / `:screened-on` /
`:screen-cleared?`）を質問レコードに持たせないと送信できない（`:qa-conflict-not-screened`）。

送信済みの回答は書き換えない。訂正は**新しい回答を起案する**（`:qa-answer-already-sent`）。

### 4.2 時間の計上

計上済み＋今回の合計が `:max-billable-hours` を超えると HARD hold
（invariant 7）。**今回分だけでなく累計で見る。**
受任範囲を広げるなら、matter の `:engagement` と `:max-billable-hours` を
先に更新する（それ自体が依頼者との合意を要する）。

### 4.3 預り金

```clojure
;; 入金
(actor/run-request! g {... :op :receive-trust
                       :trust-entry {:entry-id "TR-1" :matter-id "M-1" :client-id "C-1"
                                     :direction :in :amount 500000 :currency "JPY"
                                     :account :trust :date "2026-05-12" :purpose :retainer}}
                    ctx "t5")

;; 出金は必ず escalate
(actor/run-request! g {... :op :disburse-trust
                       :trust-entry {... :direction :out :amount 120000
                                     :account :trust :purpose :court-fee}}
                    ctx "t6")
(actor/approve! g "t6" {:by "B-1" :on "2026-07-29"})
```

- `:account` は必須（`:trust` | `:operating`）。省略すると `:trust-commingling` で hold。
- 残高を超える出金は `:trust-overdraft` で hold。**他の事件の預り金は使えない**
  ——残高は事件ごとに計算されるので、事務所全体の余剰では埋まらない。
- 報酬への充当（`:purpose :fee-appropriation`）は、**発行済みの請求書**が
  その事件に対して存在し、金額を満たしている場合のみ。

金額は通貨の最小単位の整数。JPY は最小単位がないので円がそのまま整数。
**浮動小数点は使わない。**

---

## 5. 共同受任

```clojure
(require '[lawfirm.partner :as partner])

;; 候補（資格確認済み・利益相反なし・分野か管轄が一致）
(partner/candidates s {:matter-id "M-1" :client-id "C-1" :bengoshi-id "B-1"
                       :domain "labour" :jurisdiction "JP-13"
                       :adverse-parties ["丙山建設株式会社"]}
                    "2026-07-30")

;; 招請（escalate）
(actor/run-request!
 g {... :op :invite-partner-counsel
    :grant {:grant-id "G-1" :matter-id "M-1" :grantee-bengoshi-id "B-2"
            :role :co-counsel
            :capabilities [:read :comment :draft]
            :expires-on "2026-10-28"
            :conflict-cleared? true
            :fee-split [{:bengoshi-id "B-1" :share 70 :role "主任・訴訟追行"}
                        {:bengoshi-id "B-2" :share 30 :role "労働法論点の起案"}]}}
 ctx "t7")
```

- 利益相反に触れる候補は**表示自体を行わない**（候補者名を見ること自体が
  開示の問題になりうるため）。
- `:expires-on` は必須。無期限の記録開示は秘密保持義務の穴になる。
- `:fee-split` は合計100%、各分配に担当業務（`:role`）が必要。
- `:referral-fee` が非ゼロなら HARD hold。承認では通らない。

---

## 6. コンソール

```bash
clojure -M:render-console            # docs/samples/lawyer-console.html
clojure -M:render-console out.html
```

画面に出る数字は、すべて governor が判定に使うのと**同じ関数**から読んでいる
（`store/billed-hours`、`trust/balance`、`deadline/with-status`、
`conflict/screen-matter`）。表示専用の計算はどこにもない——
画面が独自に「残り時間」を計算すると、いずれゲートと食い違い、
弁護士は画面のほうを信じてしまう。

並び順は意図的に「期限 → 承認待ち → 事件詳細 → 提携」。
事務所を潰すのは未請求の1時間ではなく、徒過した1つの期限だから。

---

## 7. 台帳

`store/ledger` は commit も hold も両方積む append-only の列。
**拒否した記録が残ることが重要**で、何をしたかしか残らない事務所は、
弁護士会に「何をしなかったか」を示せない。

```clojure
(store/ledger s)
;; [{:disposition :commit :record {...}}
;;  {:disposition :hold :op :issue-work-product :verdict {:violations [...]}}
;;  {:disposition :approved :escalation-reason :counsel-decision :approval {:by "B-1" ...}}]
```

---

## 8. 永続化と本番配備について

`mem-store` はプロセス内。実運用では `durable-store` を使う——
`:persist!` に「db を受け取って書く関数」を渡すだけで、**プロトコルの外側は
一切変わらない**（governor も console も `Store` 越しにしか喋っていない）。

```clojure
(def store
  (store/durable-store
   {:snapshot (read-snapshot-from-somewhere)     ; 前回の db、無ければ省略
    :persist! (fn [db] (write-snapshot! db))}))  ; host 側の1関数
```

`persist!` はこの記録層が host に触れる唯一の場所。`.cljc` の可搬性のため
`slurp`/`spit` は入れていない（CLAUDE.md の runtime 優先順位）。
書き込みごとに db 全体を渡すので、一件記録が大きくなればコストは線形に増える。
本物の entity store（`langchain-store` の entity-store パターン）への差し替えは
`lawfirm.store` より上を変えない。

各スナップショットは `:lawfirm.store/version` を持つ。**host は自分が既に書いた
version より古いスナップショットを捨てること**——受理された書き込み2件が遅い
host に順不同で届きうるのは host しか知らないので、その判定は host の責任。

### workspace（drive / calendar / inbox / 送信ゲートウェイ）への接続

`lawfirm.workspace` の protocol を host が実装する。方向は片道:

- **入**: `inbound-requests` が到着物を actor の**リクエスト**に変える。
  記録にはならない——メールサーバの主張が事務所の事実になるのはゲートを通ってから。
- **出**: `publish-docket!` / `publish-matter-drive!` が
  `lawfirm.projection` の値を push する。投影は読み取り専用なので、
  カレンダーが期限を動かすことはない。
- **送信**: `dispatch-plan` が返す plan を host のゲートウェイが実行する。
  **番号は記録から引き直される**ので、ゲートが見ているものとダイヤルされるものが
  ずれない。plan が組めないときは `{:ok? false :reason ...}` を返す——
  半端な plan を返すと host が手元の何かでフォールバックしうる。

HTTP の入口（Worker ingress）は cljs 側の責務。Kotoba には現時点で
ingress capability がないため（CLAUDE.md）、エントリポイントは cljs のままにする。
