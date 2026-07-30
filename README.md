# lawfirm — 法律事務所 practice OS

受任から終結までを、**弁護士の判断を必ず経由する形で**記録・監査する事務所 OS。
langgraph StateGraph の governed actor（`LawFirmAdvisor ⊣ LawFirmGovernor`）として実装し、
台帳は commit も hold も両方積む append-only。

**成熟度: `:implemented`.** 79 tests / 361 assertions green（`clojure -M:test`）、
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
| [`lawfirm.store`](src/lawfirm/store.cljc) | 記録層の SSoT。弁護士・依頼者・事件・利益相反・期限・時間・預り金・請求・書面・共同受任 grant・台帳 |
| [`lawfirm.conflict`](src/lawfirm/conflict.cljc) | 利益相反スクリーン（職務基本規程27条・28条の7類型） |
| [`lawfirm.deadline`](src/lawfirm/deadline.cljc) | 期限管理。民法140条 初日不算入 / 143条 暦計算 / 142条 休日繰越、条文付きの法定期間表 |
| [`lawfirm.trust`](src/lawfirm/trust.cljc) | 預り金の分別管理（日弁連 預り金等の取扱いに関する規程） |
| [`lawfirm.partner`](src/lawfirm/partner.cljc) | 提携弁護士。資格確認・適合度マッチング・共同受任 grant・獲得ファネル |
| [`lawfirm.intake`](src/lawfirm/intake.cljc) | 相談受付のトリアージ。本文は記録に載せない |
| [`lawfirm.advisor`](src/lawfirm/advisor.cljc) | LLM を封じ込める唯一のノード。提案しか返さない |
| [`lawfirm.governor`](src/lawfirm/governor.cljc) | ゲート。13 の HARD 不変条件 + 7 の必須承認操作 |
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

**必ず弁護士の承認を要する操作**（確信度に関わらず）: 受任・裁判所への提出・預り金の出金・
和解・辞任・書面の外部送付・共同受任の招請。加えて確信度が 0.6 未満の提案。

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
clojure -M:test              # 79 tests / 361 assertions
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
3. **`MemStore` はプロセス内。** 永続化は `lawfirm.store/Store` の別実装で、
   プロトコルの外は変わらない。
4. **HTTP の入口は無い。** Kotoba には現時点で ingress capability が無いため
   （CLAUDE.md）、Worker のエントリポイントは cljs 側の責務。
5. **コンソールの `:act` ボタンは何もしない。** SSR の意味論しか持たない。
   トランスポートへの結線はホストの仕事。

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
