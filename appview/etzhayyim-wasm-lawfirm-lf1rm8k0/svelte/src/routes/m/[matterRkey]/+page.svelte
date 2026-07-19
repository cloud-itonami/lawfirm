<script lang="ts">
  import { onMount } from "svelte";
  import { page } from "$app/stores";
  import {
    listMatters, listGrants, listHearings, listDocuments, listInvoices, listConflictChecks,
    revokeExternalCounsel, closeMatter, shortDid, isCurrentCaller,
    type Matter, type ExternalCounselGrant, type Hearing, type LegalDocument, type Invoice,
    type ConflictResult, type MatterStatus,
  } from "$lib/xrpc";
  import InviteCounselDialog  from "$lib/components/InviteCounselDialog.svelte";
  import IssueInvoiceDialog   from "$lib/components/IssueInvoiceDialog.svelte";
  import ConflictCheckDialog  from "$lib/components/ConflictCheckDialog.svelte";
  import StatusTransitionBar  from "$lib/components/StatusTransitionBar.svelte";

  const firmDid = $derived($page.url.searchParams.get("firm") ?? "");
  const matterRkey = $derived($page.params.matterRkey);
  const matterDid = $derived(firmDid ? `${firmDid}:${matterRkey}` : "");

  let matter = $state<Matter | null>(null);
  let grants = $state<ExternalCounselGrant[]>([]);
  let hearings = $state<Hearing[]>([]);
  let docs = $state<LegalDocument[]>([]);
  let invoices = $state<Invoice[]>([]);
  let conflictChecks = $state<Array<{ rkey: string; result: ConflictResult; scanScope: string; scannedAt: string; wallId?: string }>>([]);
  let loading = $state(true);

  let showInvite = $state(false);
  let showInvoice = $state(false);
  let showConflict = $state(false);
  let conflictScopeHint = $state<"matterIntake" | "externalCounselInvite">("matterIntake");
  let conflictCandidate = $state<string | undefined>(undefined);
  let lastConflictCheckRef = $state<string | undefined>(undefined);

  let closingBusy = $state(false);
  let closeBlockers = $state<string[]>([]);

  async function load() {
    if (!matterDid) return;
    loading = true;
    try {
      const [ms, gs, hs, ds, inv, ccs] = await Promise.all([
        listMatters({ firmDid, limit: 200 }),
        listGrants(matterDid, true),
        listHearings(matterDid),
        listDocuments(matterDid),
        listInvoices(matterDid),
        listConflictChecks(matterDid),
      ]);
      matter = ms.items.find((m) => m.matterRkey === matterRkey) ?? null;
      grants = gs;
      hearings = hs;
      docs = ds;
      invoices = inv;
      conflictChecks = ccs;
      const latest = ccs.at(0);
      if (latest && firmDid) {
        lastConflictCheckRef = `at://${firmDid}/com.etzhayyim.apps.lawfirm.conflictCheck/${latest.rkey}`;
      }
    } finally {
      loading = false;
    }
  }

  onMount(() => { load(); });

  async function doRevoke(g: ExternalCounselGrant) {
    if (!confirm(`Revoke grant for ${g.granteeHandle ?? shortDid(g.granteeDid)}?\nCascade invalidates all descendant sessions.`)) return;
    try {
      const r = await revokeExternalCounsel({ grantDid: g.grantDid, reason: "request" });
      alert(`Revoked at ${r.revokedAt}. ${r.descendantsInvalidated} descendant DIDs invalidated.`);
      await load();
    } catch (e) { alert(e instanceof Error ? e.message : String(e)); }
  }

  async function doClose() {
    if (!matter) return;
    const outcome = prompt("Outcome?\n(wonPlaintiff | wonDefendant | settled | dismissed | withdrawn | referredOut | archivedInactive | other)") ?? "";
    if (!outcome) return;
    closingBusy = true; closeBlockers = [];
    try {
      const r = await closeMatter({ matterDid, outcome });
      if (r.openBlockers?.length) { closeBlockers = r.openBlockers; return; }
      alert(`Matter closed at ${r.closedAt}. ${r.grantsRevoked ?? 0} grants revoked.`);
      await load();
    } catch (e) { alert(e instanceof Error ? e.message : String(e)); }
    finally { closingBusy = false; }
  }

  function openConflict(scope: "matterIntake" | "externalCounselInvite" = "matterIntake", candidate?: string) {
    conflictScopeHint = scope;
    conflictCandidate = candidate;
    showConflict = true;
  }

  function onConflictClosed(res?: { rkey: string; result: ConflictResult; wallId?: string }) {
    showConflict = false;
    if (res && firmDid) {
      lastConflictCheckRef = `at://${firmDid}/com.etzhayyim.apps.lawfirm.conflictCheck/${res.rkey}`;
      load();
    }
  }

  const activeGrants = $derived(grants.filter((g) => g.status === "accepted" || g.status === "invited"));
  const privilegedDocs = $derived(docs.filter((d) => d.privileged));
  const pendingReviewDocs = $derived(docs.filter((d) => d.status === "pendingReview"));
  const overdueInvoices = $derived(invoices.filter((i) => i.status === "overdue" || (i.dueAt && new Date(i.dueAt).getTime() < Date.now() && i.status !== "paid" && i.status !== "void")));
  const counterpartyOrgDids = $derived(matter?.counterpartyDids ?? []);
  const latestConflict = $derived(conflictChecks.at(0));

  function conflictBadgeColor(r?: ConflictResult): string {
    switch (r) {
      case "clear":              return "bg-green-100 text-green-800";
      case "disclosureRequired": return "bg-blue-100  text-blue-800";
      case "waivable":           return "bg-amber-100 text-amber-800";
      case "blocked":            return "bg-red-100   text-red-800";
      default:                   return "bg-neutral-100 text-neutral-700";
    }
  }
</script>

<a href="/" class="text-xs text-neutral-500 hover:text-neutral-900 dark:hover:text-neutral-100">← Back to board</a>

{#if loading}
  <div class="mt-6 text-sm text-neutral-500">Loading matter…</div>
{:else if !matter}
  <div class="mt-6 text-sm text-red-600">Matter {matterRkey} not found for firm {shortDid(firmDid)}.</div>
{:else}
  <header class="mt-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-4 flex items-start gap-4">
    <div class="flex-1 min-w-0">
      <div class="flex items-center gap-2 flex-wrap">
        <span class="text-base font-semibold">{matter.matterNumber ?? matter.matterRkey}</span>
        <span class="rounded text-[10px] uppercase bg-neutral-100 dark:bg-neutral-800 px-1.5 py-0.5">{matter.matterType}</span>
        <span class="rounded text-[10px] uppercase bg-matter-{matter.status} px-1.5 py-0.5 text-neutral-900">{matter.status}</span>
        {#if matter.jurisdiction}
          <span class="rounded text-[10px] bg-neutral-100 dark:bg-neutral-800 px-1.5 py-0.5">{matter.jurisdiction}</span>
        {/if}
        {#if latestConflict}
          <span class="rounded text-[10px] uppercase px-1.5 py-0.5 {conflictBadgeColor(latestConflict.result)}"
                title="Latest conflict scan · {new Date(latestConflict.scannedAt).toLocaleString()}">
            conflict: {latestConflict.result}
          </span>
        {/if}
      </div>
      {#if matter.subjectMatter}
        <p class="text-sm text-neutral-700 dark:text-neutral-300 mt-1">{matter.subjectMatter}</p>
      {/if}
      <div class="mt-2 flex gap-3 text-[11px] text-neutral-600 dark:text-neutral-400 flex-wrap">
        <span title={matter.matterDid}>matterDid <span class="font-mono">{shortDid(matter.matterDid, 2)}</span></span>
        <span title={matter.clientDid}>client <span class="font-mono">{shortDid(matter.clientDid)}</span></span>
        <span title={matter.leadBengoshiDid}>lead <span class="font-mono">{shortDid(matter.leadBengoshiDid)}</span></span>
        {#each (matter.coCounselDids ?? []) as d}
          <span class="rounded bg-neutral-100 dark:bg-neutral-800 px-1.5 py-0.5 font-mono">co {shortDid(d)}</span>
        {/each}
      </div>
    </div>
    <div class="flex flex-col gap-2">
      {#if isCurrentCaller(matter.firmDid)}
        <button class="rounded bg-neutral-900 text-white dark:bg-neutral-100 dark:text-neutral-900 px-3 py-1 text-xs"
                onclick={() => (showInvite = true)}>+ Invite counsel</button>
        <button class="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-1 text-xs"
                onclick={() => openConflict("matterIntake")}>Run conflict scan</button>
        <button class="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-1 text-xs"
                onclick={() => (showInvoice = true)}>Issue invoice</button>
        <button class="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-1 text-xs"
                disabled={closingBusy || matter.status === "closed"}
                onclick={doClose}>
          {matter.status === "closed" ? "closed" : (closingBusy ? "checking…" : "Close matter")}
        </button>
      {/if}
    </div>
  </header>

  <div class="mt-3">
    <StatusTransitionBar
      matterDid={matter.matterDid}
      currentStatus={matter.status as MatterStatus}
      lastConflictCheckRef={lastConflictCheckRef}
      onTransitioned={() => load()}
      onNeedConflictCheck={() => openConflict("matterIntake")}
    />
  </div>

  {#if closeBlockers.length}
    <div class="mt-3 rounded border border-amber-300 bg-amber-50 dark:bg-amber-950/40 px-3 py-2 text-xs text-amber-800 dark:text-amber-300">
      Close blocked by: {closeBlockers.join(" · ")}
    </div>
  {/if}

  <div class="mt-4 grid grid-cols-1 lg:grid-cols-4 gap-4">
    <!-- Hearings -->
    <section class="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-3">
      <h3 class="text-xs font-semibold uppercase tracking-wide text-neutral-700 dark:text-neutral-400 mb-2">Hearings</h3>
      {#if hearings.length === 0}
        <div class="text-[11px] text-neutral-500 italic">none scheduled</div>
      {:else}
        <ul class="space-y-2">
          {#each hearings as h}
            <li class="border-l-2 border-matter-hearing pl-2">
              <div class="text-xs font-medium">{h.hearingType}</div>
              <div class="text-[11px] text-neutral-600 dark:text-neutral-400">{new Date(h.scheduledAt).toLocaleString()}</div>
              <div class="text-[10px] text-neutral-500">status: {h.status} {h.location ? `· ${h.location}` : ""}</div>
            </li>
          {/each}
        </ul>
      {/if}
    </section>

    <!-- Documents -->
    <section class="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-3">
      <h3 class="text-xs font-semibold uppercase tracking-wide text-neutral-700 dark:text-neutral-400 mb-2">
        Documents <span class="text-[10px] font-normal">({privilegedDocs.length} priv)</span>
      </h3>
      {#if pendingReviewDocs.length > 0}
        <div class="mb-2 rounded bg-amber-100 dark:bg-amber-900/40 px-2 py-1 text-[10px] text-amber-800 dark:text-amber-200">
          ⚠ {pendingReviewDocs.length} pending ISCO-2611 review
        </div>
      {/if}
      {#if docs.length === 0}
        <div class="text-[11px] text-neutral-500 italic">no documents uploaded</div>
      {:else}
        <ul class="space-y-1">
          {#each docs as d}
            <li class="flex items-center justify-between text-xs">
              <span class="truncate">{d.title}</span>
              <span class="text-[10px] ml-2 text-neutral-500">{d.docType} · v{d.version} · {d.status}</span>
            </li>
          {/each}
        </ul>
      {/if}
    </section>

    <!-- External counsel -->
    <section class="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-3">
      <h3 class="text-xs font-semibold uppercase tracking-wide text-neutral-700 dark:text-neutral-400 mb-2">
        External counsel <span class="text-[10px] font-normal">({activeGrants.length} active)</span>
      </h3>
      {#if grants.length === 0}
        <div class="text-[11px] text-neutral-500 italic">none invited</div>
      {:else}
        <ul class="space-y-2">
          {#each grants as g}
            <li class="rounded border border-neutral-200 dark:border-neutral-800 p-2">
              <div class="flex items-center justify-between gap-2">
                <div>
                  <div class="text-xs font-medium">{g.granteeHandle ?? shortDid(g.granteeDid)}</div>
                  <div class="text-[10px] text-neutral-500">{g.role} · {g.capabilities.join(",")}</div>
                </div>
                <span class="text-[10px] rounded px-1.5 py-0.5 {g.status === 'accepted' ? 'bg-green-100 text-green-800' : g.status === 'invited' ? 'bg-amber-100 text-amber-800' : 'bg-neutral-200 text-neutral-700'}">
                  {g.status}
                </span>
              </div>
              <div class="mt-1 flex items-center justify-between gap-2 text-[10px] text-neutral-500">
                <span>expires {new Date(g.expiresAt).toLocaleDateString()}</span>
                {#if g.status === "accepted" || g.status === "invited"}
                  <div class="flex gap-2">
                    <button class="text-blue-600 hover:underline"
                            onclick={() => openConflict("externalCounselInvite", g.granteeDid)}>rescan</button>
                    <button class="text-red-600 hover:underline"
                            onclick={() => doRevoke(g)}>revoke</button>
                  </div>
                {/if}
              </div>
            </li>
          {/each}
        </ul>
      {/if}
    </section>

    <!-- Invoices -->
    <section class="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-3">
      <h3 class="text-xs font-semibold uppercase tracking-wide text-neutral-700 dark:text-neutral-400 mb-2">
        Invoices <span class="text-[10px] font-normal">({invoices.length})</span>
      </h3>
      {#if overdueInvoices.length > 0}
        <div class="mb-2 rounded bg-red-100 dark:bg-red-900/40 px-2 py-1 text-[10px] text-red-800 dark:text-red-200">
          ⚠ {overdueInvoices.length} overdue
        </div>
      {/if}
      {#if invoices.length === 0}
        <div class="text-[11px] text-neutral-500 italic">none issued</div>
      {:else}
        <ul class="space-y-1">
          {#each invoices as inv}
            <li class="flex items-center justify-between text-xs">
              <div class="min-w-0">
                <div class="truncate">{inv.invoiceNumber ?? shortDid(inv.invoiceDid)}</div>
                {#if inv.dueAt}
                  <div class="text-[10px] text-neutral-500">due {new Date(inv.dueAt).toLocaleDateString()}</div>
                {/if}
              </div>
              <div class="text-right">
                <div class="font-mono text-[11px]">{inv.total.toLocaleString()} {inv.currency}</div>
                <div class="text-[10px] {inv.status === 'paid' ? 'text-green-700' : inv.status === 'overdue' ? 'text-red-600' : 'text-neutral-500'}">{inv.status}</div>
              </div>
            </li>
          {/each}
        </ul>
      {/if}
    </section>
  </div>

  <InviteCounselDialog
    matterDid={matterDid}
    counterpartyOrgDids={counterpartyOrgDids}
    open={showInvite}
    onclose={() => { showInvite = false; load(); }}
  />
  <IssueInvoiceDialog
    matterDid={matterDid}
    defaultCurrency={matter.currency ?? "JPY"}
    open={showInvoice}
    onclose={() => { showInvoice = false; load(); }}
  />
  <ConflictCheckDialog
    matterDid={matterDid}
    counterpartyDids={counterpartyOrgDids}
    candidateDid={conflictCandidate}
    defaultScope={conflictScopeHint}
    open={showConflict}
    onclose={onConflictClosed}
  />
{/if}
