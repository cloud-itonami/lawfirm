# etzhayyim-project-lawfirm

Legal case management and BPO automation platform (`lawfirm.etzhayyim.com`).

## Runtime

**containerd-shim-kotodama** (`40-engine/kotoba/crates/kotoba-kotodama`) を使用。App CRD / containerd-shim-kotodama は除去済み。

| 項目 | 値 |
|---|---|
| WASM binary | TS Native (`src/app.ts`) |
| ランタイム | `kotodama-server` (Rust) |
| トリガー | HTTP (`0.0.0.0:8080`) |
| AT コレクション | `com.etzhayyim.command`, `com.etzhayyim.conversation.message` |
| Storage backend | yata Broker (SQL graph) |
| Config | `kotodama/kotodama.jsonld` |
| Deploy | `etzhayyim build && etzhayyim deploy` (Cloudflare Container) |

旧 `deploy config` (App CRD 時代) および `kotodama/k8s/deployment.yaml` は除去済み。deploy は `etzhayyim deploy` を使用する。

## Components

| Component | Path | Role |
|---|---|---|
| `lawfirm-client-mcp-component` | `wasm/lawfirm-client-mcp-component/` | Client-facing XRPC + AT command handler |
| `lawfirm-lawyer-static-component` | `wasm/lawfirm-lawyer-static-component/` | Lawyer UI (Svelte SPA) + LawyerService XRPC |

## AT Protocol

- `AT_ACTOR_DID` は k8s Secret `lawfirm-at-identity` の `actor-did` key から inject される
- AT Firehose: **abolished** — events via yata-wrpc (embedded)
- 購読コレクション: `com.etzhayyim.command`, `com.etzhayyim.conversation.message`
- AT commit は `dispatchATCommit` → `rt.InvokeATCommand` で performer Runtime に渡る

## Structured Data

- storage は kotodama WIT bindings (`kotodama.LanceQuerySQL`, `kotodama.LanceUpsertOne`, `kotodama.KvGet` 等) 経由
- storage は kotodama WIT bindings 経由。直接 HTTP client 禁止
- 主要テーブルは `lawfirmPrimaryKeys` map (`db_http.go`) に定義済み
- RLS 列 `org_id`, `user_id`, `actor_id` は全テーブルに必須

## Daily Evolution

- `handle_daily_evolution` が `PerformerConfig.Methods` に登録済み（必須）
- `DefaultAppTeam("lawfirm1", "lawfirm", ...)` + `RegisterDailyEvolutionReminder` 設定済み
- LLM: `murakumo.etzhayyim.com` / `qwen3-vl-8b`

## India Intake (Hindi / 22 Scheduled Languages, 2026-04)

Hindi / regional-language user が `https://lawfirm.etzhayyim.com` から申込可能。実装: `src/app.ts` §India routing helpers + 4 commands。

| NSID | 役割 | 根拠 |
|---|---|---|
| `com.etzhayyim.apps.lawfirm.translateToLang` | en/JP → hi/bn/ta/… 翻訳 (Murakumo MLX pipethrough) | lexicon L7 |
| `com.etzhayyim.apps.lawfirm.translateFromLang` | regional-lang → en (court of record) or hi | lexicon L7 |
| `com.etzhayyim.apps.lawfirm.requestConsult` | 初回 intake。**AT Repo には hash のみ** (ADR-0018 Tier 3 PII) | ADR-0018 / ADR-0026 |
| `com.etzhayyim.apps.lawfirm.createCase` | 案件作成 + India markers 検出時に peer firm へ auto-invite | ADR-0019 / ADR-0029 |

**India marker 判定**: `lang ∈ {hi, bn, ta, te, mr, gu, kn, ml, pa, or, as, ur, sa, ne, sd, ks, kok, mai, mni, sat, doi, brx}` OR `state.startsWith("IN-")` OR `jurisdiction ∈ {IND, IN}`。

**Auto-route to peer firm**: India marker 一致時、`LAWYER_FIRM_DID_HINT` (e.g. lawyer.etzhayyim.com) 宛に `externalCounselGrant` を自動発行 (role=coCounsel, capabilities=read/comment/uploadDocument/propose/sign/scheduleHearing, expires=+90d)。`KUNAL_LEAD_HANDLE_HINT`(default `k.bakshi`) が `granteeHandle`、`KUNAL_LEAD_DID_HINT` が `leadBengoshiHint` として記録される。

**PII handling (CRITICAL)**:
- `requestConsult`: plaintext summary は drop。AT Repo には `summaryHash` + `lang` + `state` + `triageCohortDid` のみ
- `createCase`: `subjectSummary` は `signal:v1:{base64(utf8)}` prefix で wrap (wproto signal ADR-0010 Stage 1、PDS helpers.ts runtime detect)

### Deploy Config (CRITICAL)

`bootstrap` 後に以下の 3 var を wrangler secret / vars で埋める必要がある:

```bash
# post-phase1 (lawyer.etzhayyim.com firm did:etzhayyim root)
wrangler secret put LAWYER_FIRM_DID_HINT   # did:etzhayyim:{h_lawyer}
wrangler secret put KUNAL_LEAD_DID_HINT    # did:etzhayyim:{h_lawyer}:{h_bakshi}
# handle はデフォルト k.bakshi (wrangler.jsonc vars)
```

**Silent skip は禁止 (ADR-0036)**。India marker 検出時に auto-route が失敗した場合、`createCase` は必ず以下を行う:
1. `console.error("[createCase] auto-route skipped|failed (<code>): <message> caseDid=... lang=... state=...")` を記録
2. response body に `autoRouteError: { code, message }` + `autoRouteExpected: true` を含めて返す

`code` 値: `NotConfigured` (env 未設定) / `InvalidConfig` (DID 形式不正) / `MintOrWriteFailed` (mint/write 例外)。caller は `autoRouteExpected === true && !autoGrant` で routing 失敗を検知可能。case record 自体は作成される (intake 救済)。

### Smoke (India path)

```bash
# Hindi intake
curl -sX POST https://lawfirm.etzhayyim.com/xrpc/com.etzhayyim.apps.lawfirm.requestConsult \
  -H "Authorization: Bearer ${BEARER}" -H "Content-Type: application/json" \
  -d '{"lang":"hi","state":"IN-MH","city":"mumbai","summary":"मेरा चेक बाउंस हो गया","domainHint":"ni138","channel":"web"}'
# → { consultDid, uri, triageCohortDid, suggestedDomain: "ni138" }

# India case (auto-routes to Kunal if LAWYER_FIRM_DID_HINT set)
curl -sX POST https://lawfirm.etzhayyim.com/xrpc/com.etzhayyim.apps.lawfirm.createCase \
  -H "Authorization: Bearer ${BEARER}" -H "Content-Type: application/json" \
  -d '{"domain":"ni138","state":"IN-MH","lang":"hi","city":"mumbai","subjectSummary":"Cheque ₹5L bounced","amountInDispute":500000,"currency":"INR","urgency":"routine"}'
# → { did, uri, cohortDid, caseNumber, autoGrant: { grantDid, granteeDid, granteeHandle: "k.bakshi" } }

# Hindi translation
curl -sX POST https://lawfirm.etzhayyim.com/xrpc/com.etzhayyim.apps.lawfirm.translateToLang \
  -H "Content-Type: application/json" \
  -d '{"text":"Section 138 NI Act complaint","targetLang":"hi","register":"court-of-record","domain":"ni138"}'
```

## Build

```bash
# lawfirm-lawyer-static-component (Svelte + API)
cd 60-apps/etzhayyim-project-lawfirm/wasm/lawfirm-lawyer-static-component/svelte
pnpm install && pnpm build
cd ..
etzhayyim deploy
```

## Deploy

```bash
# client component
etzhayyim deploy --smoke-url https://lawfirm.etzhayyim.com/health

# smoke test
curl https://lawfirm.etzhayyim.com/health
curl -X POST https://lawfirm.etzhayyim.com/etzhayyim.lawfirm.v1.LawfirmQueryService/ListCases \
  -H "Content-Type: application/json" -d '{}'
```
