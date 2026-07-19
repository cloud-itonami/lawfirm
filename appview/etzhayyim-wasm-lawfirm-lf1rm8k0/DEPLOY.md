# lawfirm.etzhayyim.com — Deploy + E2E runbook

Phase A–D lawfirm appview Worker. Covers deploy and smoke verification.

## Prerequisites

- `etzhayyim` CLI built (`go install ./70-tools/etzhayyim/etzhayyim`)
- `pnpm` for Svelte build + Playwright
- Active session JWT: `etzhayyim auth login` (writes `~/.etzhayyim/auth.json`)
- A depth-1 `did:etzhayyim:` firm root — run `etzhayyim auth login` once; the returned
  `accountDid` is the firm DID. Persist for subsequent XRPC calls.
- (Optional) Cluster views applied from
  `30-graph/graph-schema/migrations/20260417160000_lawfirm_projection_mvs.ts` and
  `.../20260417170000_lawfirm_invoice_conflict_views.ts`. The latter depends on
  `vertex_atrecord_lawfirm_{invoice,conflictcheck}` existing; those tables are
  PDS-pipeline-generated on first writeRecord, so apply the 17170000 migration
  **after** issuing the first matter/invoice.

## Build + deploy

```bash
cd 60-apps/etzhayyim-project-lawfirm/appview/etzhayyim-wasm-lawfirm-lf1rm8k0/svelte
pnpm install
pnpm build   # → svelte/build/

cd ..        # back to Worker root
etzhayyim deploy  # reads wrangler.jsonc, bundles src/app.ts, attaches svelte/build via ASSETS binding
```

Deploy smoke:

```bash
curl -s https://lawfirm.etzhayyim.com/health
curl -s https://lawfirm.etzhayyim.com/_app/meta | jq '.commands | length'
# → expect 16 (14 mutating + 2 list)
```

## Run E2E smoke

```bash
cd 60-apps/etzhayyim-project-lawfirm/appview/etzhayyim-wasm-lawfirm-lf1rm8k0/e2e
pnpm install
pnpm exec playwright install chromium

export LAWFIRM_BASE_URL=https://lawfirm.etzhayyim.com
export LAWFIRM_AUTH_BEARER=$(jq -r '.session.accessJwt' ~/.etzhayyim/auth.json)
export LAWFIRM_FIRM_DID=$(jq -r '.session.accountDid' ~/.etzhayyim/auth.json)

# Provision test fixtures (one-time): second did:etzhayyim account for client,
# third for bengoshi, fourth for external counsel.
#   etzhayyim auth login         # fresh passkey → becomes LAWFIRM_CLIENT_DID
#   etzhayyim auth login         # ...            → LAWFIRM_BENGOSHI_DID
#   etzhayyim auth login         # ...            → LAWFIRM_EXTERNAL_DID
export LAWFIRM_CLIENT_DID=did:etzhayyim:...
export LAWFIRM_BENGOSHI_DID=did:etzhayyim:...
export LAWFIRM_EXTERNAL_DID=did:etzhayyim:...

pnpm test:api       # full lifecycle XRPC path
pnpm test:ui        # UI smoke (Kanban + invite dialog + transition bar)
```

API smoke (`lifecycle.api.spec.ts`) walks the 13-step path: `createMatter →
runConflictCheck → updateMatterStatus ×3 → uploadDocument → inviteExternalCounsel
→ [acceptExternalCounsel] → scheduleHearing → updateMatterStatus ×2 →
recordTimeEntry → issueInvoice → revokeExternalCounsel → closeMatter`. Every
DID-minting response is asserted to carry `materialHashProof` and the expected
`didDepth` (2 for matter; 3 for grant / hearing / document / invoice).

UI smoke (`kanban.ui.spec.ts`) loads `/`, asserts the 10 status columns plus the
4-tab header, opens the invite dialog and triggers client-side conflict
detection by typing the firm's own DID (overlaps with itself → warning fires).

## Rollback

`etzhayyim deploy` is atomic — a previous Worker version is kept. To revert:

```bash
wrangler deployments list       # inside the Worker dir
wrangler rollback <deployment-id>
```

The Svelte bundle is versioned alongside the Worker via the ASSETS binding; a
Worker rollback reverts both code and UI in one step. AT records written by
the old deployment remain valid (lexicon schemas are forward-compatible for the
Phase A lifetime — any enum additions since A are additive).

## Known gotchas

- **listInvoices / listConflictChecks return empty** until the 17170000
  migration is applied *and* at least one invoice / conflictCheck record has
  been written (atrecord tables created lazily).
- **RisingWave MV creation via wrangler dev** is untested; apply migrations
  against the production cluster only (see graph-schema CLAUDE.md §How to Add
  a New Table).
- **Cross-firm external counsel flow** requires the external bengoshi to hold
  their own `etzhayyim auth login` session — cannot be simulated from a single
  passkey. Use `LAWFIRM_EXTERNAL_BEARER` to pass the second session JWT to the
  acceptExternalCounsel step.
