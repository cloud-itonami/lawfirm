# lawfirm — 法律事務所 practice OS

受任から終結までを、**弁護士の判断を必ず経由する形で**記録・監査する事務所 OS。
langgraph StateGraph の governed actor（`LawFirmAdvisor ⊣ LawFirmGovernor`）として実装し、
台帳は commit も hold も両方積む append-only。

**成熟度: `:implemented`.** 152 tests / 609 assertions green（`clojure -M:test`）、
`clojure -M:lint` warnings 0、レンダリング済みコンソールは
[design-quality](https://github.com/kotoba-lang/design-quality) の決定論的
HIG/WCAG 監査で **100.00 / 100**。

- 実務手順: [`docs/operator-guide.md`](docs/operator-guide.md)
- 提携弁護士の獲得計画: [`docs/partner-recruitment.md`](docs/partner-recruitment.md)
- 生成済みコンソール: [`docs/samples/lawyer-console.html`](docs/samples/lawyer-console.html)

---

## 一段落でいうと

法律事務は弁護士でなければ扱えない（弁護士法72条）。だからこのソフトは法律事務をしない。
代わりに、**弁護士が下した判断を記録し、判断を経ていない操作を構造的に不可能にする**。
書面を起案することはできるが、弁護士が自ら精査していない書面は発出できない。
預り金を記録できるが、出金は弁護士の承認なしには通らない。共同受任の報酬分配は
記録できるが、紹介料は——承認しても——記録として表現できない。

その「できない」を運用ルールではなくコードの不変条件として持っているのが、
このリポジトリの中身のほぼ全部である。

---

## 何が入っているか

| namespace | 役割 |
|---|---|
| [`lawfirm.store`](src/lawfirm/store.cljc) | 記録層の SSoT。弁護士・依頼者・事件・利益相反・期限・時間・預り金・請求・書面・共同受任 grant・送達先・送達・相談Q&A・台帳。`mem-store` と `durable-store` の2実装が**同じ純関数群に委譲**するので乖離しない |
| [`lawfirm.conflict`](src/lawfirm/conflict.cljc) | 利益相反スクリーン（職務基本規程27条・28条の7類型） |
| [`lawfirm.deadline`](src/lawfirm/deadline.cljc) | 期限管理。民法140条 初日不算入 / 143条 暦計算 / 142条 休日繰越、条文付きの法定期間表 |
| [`lawfirm.trust`](src/lawfirm/trust.cljc) | 預り金の分別管理（日弁連 預り金等の取扱いに関する規程） |
| [`lawfirm.partner`](src/lawfirm/partner.cljc) | 提携弁護士。資格確認・適合度マッチング・共同受任 grant・獲得ファネル |
| [`lawfirm.transmission`](src/lawfirm/transmission.cljc) | 送達（FAX・郵便・メール・持参・電子提出）。**宛先は事前登録された recipient からしか選べない** — 誤送信の構造的封じ込め |
| [`lawfirm.qa`](src/lawfirm/qa.cljc) | 相談 Q&A。回答は書面と同じ draft → 弁護士精査 → 送信のはしごを通る |
| [`lawfirm.projection`](src/lawfirm/projection.cljc) | 記録 → workspace 型（`calendar.model` / `drive.model`）とポータル向け summary への**一方向**投影 |
| [`lawfirm.workspace`](src/lawfirm/workspace.cljc) | host が実装する port（inbox / drive / calendar / 送信ゲートウェイ）と、到着物を actor の **request** に変える入口 |
| [`lawfirm.intake`](src/lawfirm/intake.cljc) | 相談受付のトリアージ。本文は記録に載せない |
| [`lawfirm.advisor`](src/lawfirm/advisor.cljc) | LLM を封じ込める唯一のノード。提案しか返さない |
| [`lawfirm.governor`](src/lawfirm/governor.cljc) | ゲート。19 の HARD 不変条件 + 9 の必須承認操作。verdict 組み立てと provenance 4規則は [`kotoba-lang/governor`](https://github.com/kotoba-lang/governor) を使う（fleet で 376 repo に手で複製され、1件が乖離していた層 — ADR-2607309100） |
| [`lawfirm.actor`](src/lawfirm/actor.cljc) | StateGraph。`intake → advise → govern → decide → commit \| request-approval \| hold` |
| [`lawfirm.console`](src/lawfirm/console.cljc) | 弁護士コンソール（kotoba-ui、pure `.cljc` hiccup、SSR） |
| [`lawfirm.demo`](src/lawfirm/demo.cljc) | サンプル事務所。テストとデモページが**同じ記録**を使う |

```text
:intake -> :advise -> :govern -> :decide -+-> :commit           (:ok?)
                                          +-> :request-approval (:escalate?, interrupt-before)
                                          +-> :hold             (:hard?)
```

1 run = 1 操作。承認は「スレッドを再開する行為」そのもので、
再開した弁護士の氏名と日付が効果より先に台帳へ書かれる。匿名承認は例外を投げる。

---

## HARD 不変条件（承認では覆らない）

| # | 規則 | 根拠 |
|---|---|---|
| 1 | `:effect` は `:propose` のみ — 提出・送金・署名・送付を自ら実行しない | actor の定義 |
| 2 | 担当者が弁護士名簿にあり、active で、資格確認が1年以内 | 弁護士法72条 |
| 3 | 依頼者・事件の存在と帰属 | — |
| 4 | 署名・日付のある利益相反判断記録が存在する | 規程27条・28条 |
| 5 | **かつ本日の再スクリーンが clean である** | 同上 |
| 6 | 有償業務の前に委任契約書が登録されている | 規程30条 |
| 7 | 計上済＋今回の工数が受任範囲の上限を超えない | — |
| 8 | 預り金: 分別管理・残高超過の出金不可・請求書なき報酬充当不可 | 日弁連 預り金規程 |
| 9 | 紹介料は双方向で不可。共同受任の分配は担当業務の記載と合計100%を要する | 規程13条1項・2項 |
| 10 | 弁護士が自ら精査した記録のない書面は発出できない | 法務省 2023年ガイドライン |
| 11 | 精査できるのは有効登録の弁護士のみ | 同上 |
| 12 | 重要期限が徒過している事件で通常業務を進められない（是正・提出・辞任・時間記録は可） | 弁護過誤の防止 |
| 13 | 共同受任先への開示は、失効していない grant の capability 範囲内でのみ | 秘密保持義務 |
| 14 | 送達先は事件に登録済みで、その経路の宛先を弁護士が180日以内に確認していること。発出済（`:issued`）でない書面は送達できない。書面種別が取れない経路では送達できない | 誤送信の防止 / 民事訴訟規則3条1項 |
| 15 | 送達先の登録は、有効登録の弁護士による確認記録を伴うこと | 同上 |
| 16 | 弁護士が自ら精査した記録のない相談回答は送信できない。受任前の相談は、その相談者に対する日付入りの利益相反スクリーンを要する | 法務省 2023年ガイドライン / 規程27条 |
| 17 | 相談回答を精査できるのは有効登録の弁護士のみ | 同上 |
| 18 | 受付・受信記録・質問の記録に**本文を入れられない**（分類と digest のみ） | 秘密保持義務 |
| 19 | 送信結果は、存在する発信の送達に対してのみ記録できる。**誤送信は拒否せず記録する** | 事故の証跡保全 |

**必ず弁護士の承認を要する操作**（確信度に関わらず）: 受任・裁判所への提出・預り金の出金・
和解・辞任・書面の外部送付・**送達**・**相談回答の送信**・共同受任の招請。
加えて確信度が 0.6 未満の提案。

**到着したものの記録と、送信結果の記録は承認を要しない。** 受信した FAX・依頼者からの質問・
経路が返してきた送信結果は世界についての事実であって判断ではなく、書き留めるのに承認が要る
事務所は、単に書き留めない。

**誤送信は hold しない。** 書面は既に出てしまっており、残っているのは記録を正しくすることだけ。
どこへ行ったかを書き留めるのを拒否したら、事故の証跡そのものが消える。
`transmission/direction-check` は `:match` / `:mismatch` / **`:undeterminable`** の3値を返す——
照合の材料が無いときに `false`（＝誤送信でない）と答えるのは、誰も行っていない検査結果を
記録することになるので、その選択肢を持たせていない。

### 14 が「宛先フィールド」ではなく「宛先レコード」である理由

誤送信は日本の法律事務所で最も多い守秘義務事故で、構造的な原因は1つ——**宛先が、
いちばん急いでいる人によって、紙に書かれた番号から、送信の瞬間に入力される**こと。
だから送達に宛先フィールドは無い。送達は `recipient-id` を指し、recipient は事件に
登録されたレコードで、各経路の宛先を「誰が」「いつ」確認したかを持つ。登録されていない
番号には送れず、2年前に確認した番号は stale として弾かれる（番号は再割当される）。
`workspace/dispatch-plan` が送信ゲートウェイに渡す番号も**記録から引き直す**ので、
ゲートが見ている番号とモデムがダイヤルする番号がずれることがない。

### 4 と 5 が別々である理由

利益相反の判断は「判断した日の事実」に対して行われる。事務所はその後も新しい事件を受ける。
もしゲートが保存された `:cleared?` フラグだけを信じていたら、火曜日に相手方の事件を
受任した瞬間に月曜日の判断は無効になっているのに、誰も気づかない。
だから governor は提案のたびに `conflict/screen` を再実行し、
**署名済みの判断記録が残っていても** live なヒットがあれば hold する
（`conflict-test.clj` の `clearance-does-not-survive-a-new-adverse-matter` がこれを証明している）。

---

## 使う

```bash
clojure -M:test              # 152 tests / 609 assertions
clojure -M:lint              # clj-kondo, errors fail
clojure -M:render-console    # docs/samples/lawyer-console.html を再生成
```

コンソールに出る数字は、すべて governor が判定に使うのと同じ関数から読んでいる
（`store/billed-hours` / `trust/balance` / `deadline/with-status` /
`conflict/screen-matter`）。表示専用の計算はどこにもない——
画面が独自に残時間を計算すれば、いずれゲートと食い違い、弁護士は画面を信じる。

実務の手順は [`docs/operator-guide.md`](docs/operator-guide.md)。

---

## 設計上の選択

- **`.cljc`。** JVM 依存を持たない（CLAUDE.md の runtime 優先順位）。期限計算に
  `java.time` を使わないのもこの理由で、`lawfirm.date` は Howard Hinnant の
  `days_from_civil` を整数演算で持っている。`js/Date` はタイムゾーンを持つ時刻であって、
  出訴期限はどちらでもない。
- **UI は kotoba-ui のみ。** `kotoba-ui.core` + `appkit.core` 以外を require しない。
  app CSS は 5 ルール、生の hex はテーマ map の 2 色だけ（skill `kotoba-uiux`）。
- **金額は最小単位の整数。** 浮動小数点は使わない。
- **モデル名を焼かない。** `murakumo-main` alias 経由で解決し、fallback は
  endpoint のみ（ADR-2607173100）。
- **モデルは payload を作れない。** 預り金の移動・grant・書面の同一性・
  利益相反の判断記録は request から verbatim で運ばれ、モデルの出力で上書きされない
  （`advisor/payload-keys`）。モデルが作れるのは `:op`・工数・確信度・理由だけ。

---

## 既知の限界

正直に書く。どれも回避策ではなく、構造的な限界である。

1. **利益相反スクリーンは氏名の一致しか見ない。** 資本関係・実質的支配・同居の親族は
   見ていない。`:aliases` と `:related-parties` に記録されていない関係は検出されない。
   だから人間による判断記録（不変条件4）がスクリーン（不変条件5）と**別に**必要になっている。
2. **法定期間表（`deadline/statutory`）は起案の補助であって権威ではない。**
   起算点の判定は弁護士が行う。休日繰越（民法142条）は休日カレンダーを渡したときだけ働き、
   渡さなければ延長しない側に倒れる。
3. **永続化はスナップショット全書き。** `store/durable-store` は受理した書き込みごとに
   db 全体を `:persist!` に渡す。正しく、部分書き込みの中間状態を持たないが、
   一件記録が大きくなると1操作あたりのコストが線形に増える。プロトコル境界があるので、
   本物の entity store への差し替えは `lawfirm.store` より上を変えない。
   `persist!` はこの名前空間が host に触れる唯一の場所で、`slurp`/`spit` は入れない
   （`.cljc` の可搬性 — CLAUDE.md の runtime 優先順位）。
4. **HTTP の入口は無い。** Kotoba には現時点で ingress capability が無いため
   （CLAUDE.md）、Worker のエントリポイントは cljs 側の責務。
5. **コンソールの `:act` ボタンは何もしない。** SSR の意味論しか持たない。
   トランスポートへの結線はホストの仕事。
6. **送達経路の制約表（`transmission/channel-restrictions`）も起案の補助であって
   権威ではない。** 確信を持って「不可」と言える組合せだけを載せている。表に**無い**
   組合せは「可」ではなく `:requires-counsel-judgement` であり、それが送達を無条件に
   escalate している理由。
7. **FAX の送信確認は送達の証明ではない。** `:result` は機械が応答したことしか
   記録しない。送達の証明（送達報告書・配達証明）はそれ自体が別の書面であって、
   ここのフラグではない。
8. **投影は一方向で、戻す口は無い。** カレンダーやドライブ側の編集は記録に反映されない。
   これは欠落ではなく設計で、`lawfirm.projection` の docstring に理由を書いてある。

---

## `appview/` について（legacy、非正典）

`appview/etzhayyim-wasm-lawfirm-lf1rm8k0/` は `etzhayyim/root` から抽出された
Svelte + TypeScript + Cloudflare Worker のアプリで、インド（ヒンディー語 / NI Act 138）の
intake を対象にしている。**このリポジトリの正典実装ではない。**

- 参照している `bengoshi.etzhayyim.com` / `dispatcher.etzhayyim.com` / `lawfirm.etzhayyim.com`
  はいずれも 2026-07-30 時点で名前解決しない。
- Svelte / Tailwind / TS はこのワークスペースの UI 規約（skill `kotoba-uiux`）に反する。

履歴として残しているが、新しい作業をここに積まないこと。同じ機能が必要なら
`src/lawfirm/` 側に `.cljc` で実装する。

`lg-clj/`（Python LangGraph の bb ベース移植）は削除した。`bb` は script host として
退役済みで（ADR-2607173000）、参照先のサービスも存在しない。機能は移植済み:
トリアージ分類は `lawfirm.intake`、弁護士検索と招請は `lawfirm.partner/candidates` と
`:invite-partner-counsel`。**移植しなかったもの**: FastAPI 互換の HTTP ルーティングと
http-kit サーバ（入口は上記のとおり cljs 側の責務）、`signal:v1:` の base64 包み
（base64 は暗号化ではないので、本文を保存しない設計に置き換えた——
`lawfirm.intake` の docstring に理由を書いてある）。
