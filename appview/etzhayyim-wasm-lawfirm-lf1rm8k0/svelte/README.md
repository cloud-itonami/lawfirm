# lawfirm.etzhayyim.com — Svelte UI (Phase C)

SvelteKit (`adapter-static`) SPA served from the `etzhayyim-wasm-lawfirm-lf1rm8k0`
Worker via the `ASSETS` binding. Phase C scope is a working 4-tab Protocol
Canvas with a functional Matter board and invite-external-counsel flow.

## Structure

```
svelte/
├── package.json           — SvelteKit 2 + Svelte 5 runes + Tailwind
├── svelte.config.js       — adapter-static → build/
├── vite.config.ts
├── tailwind.config.js
├── postcss.config.js
├── tsconfig.json
├── src/
│   ├── app.html
│   ├── app.css            — Tailwind + status color palette
│   ├── lib/
│   │   ├── xrpc.ts        — typed lawfirm procedure client (AT Protocol XRPC)
│   │   └── components/
│   │       ├── MatterCard.svelte
│   │       └── InviteCounselDialog.svelte
│   └── routes/
│       ├── +layout.svelte       — 4-tab header (live/talk/vibes/provider)
│       ├── +page.svelte         — / — Matter Kanban (10 columns)
│       ├── m/[matterRkey]/+page.svelte  — matter detail
│       ├── at/[...rest]/+page.svelte    — AT URI deep-link resolver
│       ├── talk/+page.svelte    — stub
│       ├── vibes/+page.svelte   — stub
│       └── provider/+page.svelte — stub
└── README.md (this file)
```

## Build

```bash
cd svelte
pnpm install
pnpm build           # → build/ static output
```

The parent `wrangler.jsonc` should add an `assets` binding once build output is
produced:

```jsonc
"assets": { "directory": "svelte/build", "binding": "ASSETS" }
```

The Worker `src/app.ts` falls through to `env.ASSETS.fetch(req)` for any
non-`/xrpc/*` path, with `build/index.html` as SPA fallback (SvelteKit
adapter-static default).

## Runtime Wiring

All XRPC calls use the AT Protocol XRPC wire format through local
`atProcedure()` / `atQuery()` helpers and target `atproto.etzhayyim.com`
(platform PDS). Session JWT is picked up from the appshellv2 auth store.

## ADR-0029 Surfaces in the UI

- **DID chip truncation** via `shortDid(did, segments)`.
- **Matter deep-link** `/m/{matterRkey}?firm={firmDid}` reconstructs matterDid
  as `firmDid:matterRkey` without a DB lookup (DID ↔ rkey isomorphism).
- **Invite dialog** computes conflict detection client-side by comparing
  `granteeDid` against matter's `counterpartyDids[]` (any `startsWith` match).
- **Revoke button** shows "cascade descendants" copy because ADR-0029 revokes
  propagate via ancestor `revoked_at` without per-descendant writes.
- **Hash prefix ethical wall** visible in `matter.materialHashProof` / grant
  scope `at://.../matter/{rkey}/*` — UI does not allow org-wide grants.

## Next (Phase D)

- Projection MVs `mv_lawfirm_matter_roster` and `mv_lawfirm_external_counsel_access`
  so `listMatters` / `listGrants` can join `vertex_etzhayyim_identity` and surface
  `actor_score` / `entity_type` / `revoked_at` in cards.
