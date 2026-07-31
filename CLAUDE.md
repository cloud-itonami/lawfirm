# CLAUDE.md — lawfirm

法律事務所 practice OS。**正典実装は `src/lawfirm/**` の `.cljc`**。
概要は [`README.md`](README.md)、実務手順は [`docs/operator-guide.md`](docs/operator-guide.md)、
提携弁護士の獲得計画は [`docs/partner-recruitment.md`](docs/partner-recruitment.md)。

## 触る前に読むもの

1. [`src/lawfirm/governor.cljc`](src/lawfirm/governor.cljc) の docstring —
   19 の HARD 不変条件と 9 の必須承認操作。**このリポジトリの仕様はここにある。**
2. [`src/lawfirm/conflict.cljc`](src/lawfirm/conflict.cljc) の docstring —
   なぜ「人間の判断記録」と「本日の再スクリーン」が別々に必要なのか。
3. [`src/lawfirm/advisor.cljc`](src/lawfirm/advisor.cljc) の `payload-keys` —
   モデルが作れないものの一覧と、その理由。

## 開発

```bash
clojure -M:test              # 152 tests / 609 assertions
clojure -M:lint              # clj-kondo, errors fail, warnings 0 を維持する
clojure -M:render-console    # docs/samples/lawyer-console.html を再生成
```

- コンソールは design-quality の決定論的 HIG/WCAG 監査で **100.00** を維持する
  （`console_test.clj` の `score-floor`）。**回帰を通すために floor を下げない。**
  レポートが名指しした指摘を直す。
- `render-console` は **byte-identical across reruns** でなければならない
  （`rendering-is-deterministic`）。時刻・乱数を入れない。

## この repo 固有の不変条件（破らない）

- **記録の書き込みは `lawfirm.actor/apply-effect!` からのみ。** `:commit` ノード以外から
  `store/register-*!` を呼ぶ実務コードを追加しない（テストとセットアップは除く）。
- **verdict の組み立てを手で書き直さない。** `gov/verdict` / `gov/disposition` /
  `gov/violations` と provenance 4規則（`no-actuation` / `missing-subject` /
  `unknown-scope` / `scope-owner-mismatch`）は `kotoba-lang/governor` のもの。
  fleet で 376 repo に複製され1件が乖離した層なので、ここでローカルコピーに戻さない
  （ADR-2607309100）。domain 規則と `:detail` 文言はこの repo のもの。
- **governor は advisor の理由を読まない。** 提案の良し悪しではなく、登録済みの記録に
  対して照合する。`governor.cljc` に `:rationale` を見るコードを足さない。
- **`:hold` も台帳に積む。** 何をしたかしか残らない事務所は、弁護士会に
  「何をしなかったか」を示せない。
- **紹介料は escalate ではなく HARD hold。** 人間が承認しても通ってはいけない（規程13条）。
- **UI は `kotoba-ui.core` + `appkit.core` のみ require。** 生の hex はテーマ map の
  2 色だけ。app CSS は unlayered のままにし、`.liquid-glass__*` との詳細度勝負をしない
  （skill `kotoba-uiux`）。
- **`.cljc` に `java.time` / `js/Date` / `Math/round` を入れない。** 日付は
  `lawfirm.date`、丸めは `console/pct`。
- **金額は最小単位の整数。** 浮動小数点を持ち込まない。
- **送達先を「フィールド」に戻さない。** `:recipient-id` を宛先そのもの（番号・住所・
  アドレス）で置き換える変更を入れない。誤送信をコード上不可能にしているのはこの一点で、
  `workspace/dispatch-plan` が記録から番号を引き直すところまで含めて成立している。
- **誤送信の記録を hold にしない。** `:confirm-transmission` は不一致を検出しても commit する。
  拒否すると事故の証跡が残らない。`direction-check` の `:undeterminable` を `false` に
  畳まない——照合していないことと、照合して問題が無かったことは別の事実。
- **misdirection の判定を payload から受け取らない。** `transmission/apply-confirmation` が
  記録から計算する（経路が自分について報告できてはならない）。
- **`lawfirm.projection` に書き戻し経路を足さない。** カレンダー / ドライブ側の編集を
  記録に反映する関数を追加すると、ゲートが判定に使う値と画面の値の出所が2つになる。
- **本文（`:body` / `:text` / `:content` / …）を記録に載せない。** `governor/prose-keys`
  がゲートで弾く。キーを増やして回避しない。
- **`lawfirm.store` の2実装は必ず同じ純関数群（`db-*`）に委譲する。** どちらかに固有の
  読み書きロジックを書かない（`store_test.clj` の
  `the-two-implementations-cannot-drift` が両者の全読み取りを突き合わせている）。
- **モデル名を焼かない。** `advisor/resolve-model` の解決順（明示 → `murakumo-main`
  alias → endpoint のみ）を迂回しない（ADR-2607173100）。
- **`bb.edn` / `.sh` を新規に置かない**（ADR-2607173000、CLAUDE.md repo-wide）。
  スクリプトが要るなら nbb。

## テストの約束

- `lawfirm.demo` はテストとデモページの**共通**の ground truth。
  デモ専用のフィクスチャを別に作らない（作った瞬間にデモは「実際には走っていないものの
  スクリーンショット」になる）。
- 新しい HARD 不変条件を足したら、`governor_test.clj` に
  「違反すると hold」「違反しなければ通る」の両方を書く。片方だけだと、
  常に hold する実装でもテストが緑になる。

## legacy — `appview/`

`appview/etzhayyim-wasm-lawfirm-lf1rm8k0/` は `etzhayyim/root` からの抽出物
（Svelte + TS + Cloudflare Worker、インド / ヒンディー語の intake、
AT Protocol lexicon `com.etzhayyim.apps.lawfirm.*`）。**正典ではない。**

- 参照する `lawfirm.etzhayyim.com` / `bengoshi.etzhayyim.com` /
  `dispatcher.etzhayyim.com` は 2026-07-30 時点でいずれも名前解決しない（未デプロイ）。
- Svelte / Tailwind / TS はワークスペースの UI 規約に反する。

**ここに新しい作業を積まない。** 同等の機能が必要なら `src/lawfirm/` に `.cljc` で書く。
インド intake を正典側に持ってくる場合は、`lawfirm.intake/domains` の分野語彙と
`lawfirm.deadline/statutory` の法定期間表を当該法域向けに追加するのが正しい入口で、
`appview/` を拡張するのは違う。

`lg-clj/`（Python LangGraph の bb 移植）は削除済み。移植先と、移植しなかったものは
[`README.md`](README.md) 末尾に記録した。
