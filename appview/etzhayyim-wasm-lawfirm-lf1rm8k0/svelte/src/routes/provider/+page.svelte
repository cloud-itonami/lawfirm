<script lang="ts">
  import { onMount } from "svelte";
  import { listMatters, type Matter } from "$lib/xrpc";

  /**
   * Provider tab — Murakumo LLM + cross-actor cards.
   * Cards are entry points; invocation routes into murakumo.etzhayyim.com /
   * hanrei.etzhayyim.com / bengoshi.etzhayyim.com via wRPC cross-actor (not yet wired from here —
   * the cards surface the tool contract and deep-link to the right runner).
   */

  let matters = $state<Matter[]>([]);
  let selectedMatterRkey = $state("");

  onMount(async () => {
    const r = await listMatters({ limit: 50 }).catch(() => ({ items: [] as Matter[] } as any));
    matters = r.items as Matter[];
    if (matters.length) selectedMatterRkey = matters[0].matterRkey;
  });

  const selectedMatter = $derived(matters.find((m) => m.matterRkey === selectedMatterRkey));

  interface Tool {
    id: string;
    title: string;
    icon: string;
    summary: string;
    inputHint: string;
    routesTo: string;
    action: () => void;
    disabled?: string;
  }

  const tools: Tool[] = $derived([
    {
      id: "draft-motion",
      title: "Draft motion",
      icon: "📝",
      summary: "Murakumo drafts a jurisdiction-aware motion from the matter's subject + prior pleadings. Output enters status=pendingReview awaiting ISCO-2611 approval (RULE-003).",
      inputHint: "matter · motion type · audience court",
      routesTo: "wRPC → murakumo.etzhayyim.com:draftMotion → uploadDocument(aiGenerated=true)",
      action: () => {
        if (!selectedMatter) return;
        location.assign(`https://murakumo.etzhayyim.com/agent/draft-motion?matterDid=${encodeURIComponent(selectedMatter.matterDid)}`);
      },
      disabled: !selectedMatter ? "pick a matter first" : undefined,
    },
    {
      id: "cite-check",
      title: "Cite check",
      icon: "📚",
      summary: "Resolves every citation in an uploaded document against hanrei.etzhayyim.com precedent index + e-Gov laws. Flags overruled / reversed / superseded cites.",
      inputHint: "documentDid OR raw text",
      routesTo: "wRPC → hanrei.etzhayyim.com:resolveCitations",
      action: () => {
        if (!selectedMatter) return;
        location.assign(`https://hanrei.etzhayyim.com/agent/cite-check?matterDid=${encodeURIComponent(selectedMatter.matterDid)}`);
      },
      disabled: !selectedMatter ? "pick a matter first" : undefined,
    },
    {
      id: "conflict-scan",
      title: "Conflict scan",
      icon: "⚖️",
      summary: "Shortcut to runConflictCheck with matter-intake scope. Use before advancing status from conflictCheck → engaged.",
      inputHint: "matterDid · counterparty DIDs",
      routesTo: "XRPC → com.etzhayyim.apps.lawfirm.runConflictCheck",
      action: () => {
        if (!selectedMatter) return;
        location.assign(`/m/${selectedMatter.matterRkey}?firm=${encodeURIComponent(selectedMatter.firmDid)}&openConflict=1`);
      },
      disabled: !selectedMatter ? "pick a matter first" : undefined,
    },
    {
      id: "invoice-forecast",
      title: "Invoice forecast",
      icon: "💰",
      summary: "Projects next-period invoice total from current billable hours velocity + pending flat fees. Soft preview of what issueInvoice would settle on day N.",
      inputHint: "matter · projection window",
      routesTo: "wRPC → murakumo.etzhayyim.com:invoiceForecast (read-only)",
      action: () => {
        if (!selectedMatter) return;
        location.assign(`https://murakumo.etzhayyim.com/agent/invoice-forecast?matterDid=${encodeURIComponent(selectedMatter.matterDid)}`);
      },
      disabled: !selectedMatter ? "pick a matter first" : undefined,
    },
  ]);
</script>

<div class="flex items-center justify-between mb-3">
  <h1 class="text-lg font-semibold">Provider — Murakumo LLM tools</h1>
  <div class="flex items-center gap-2 text-xs">
    <label class="text-neutral-500" for="provider-matter-select">matter</label>
    <select id="provider-matter-select" class="rounded border border-neutral-300 dark:border-neutral-700 bg-transparent px-2 py-1 max-w-[280px]" bind:value={selectedMatterRkey}>
      {#if matters.length === 0}
        <option value="">(no matters)</option>
      {/if}
      {#each matters as m}
        <option value={m.matterRkey}>{m.matterNumber ?? m.matterRkey} · {m.status}</option>
      {/each}
    </select>
  </div>
</div>

<p class="text-xs text-neutral-500 mb-4">
  4 agent tools auto-registered to the platform agent graph via <code>capabilityDeclare()</code>.
  Discover via <code>POST mcp.etzhayyim.com/mcp</code> <code>tools/list</code>.
</p>

<div class="grid grid-cols-1 md:grid-cols-2 gap-3">
  {#each tools as t}
    <div class="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-4 flex flex-col">
      <div class="flex items-start gap-3">
        <div class="text-2xl">{t.icon}</div>
        <div class="flex-1">
          <h2 class="text-sm font-semibold">{t.title}</h2>
          <p class="mt-1 text-xs text-neutral-600 dark:text-neutral-400">{t.summary}</p>
        </div>
      </div>
      <dl class="mt-3 space-y-1 text-[10px] text-neutral-500">
        <div class="flex gap-2"><dt class="shrink-0">input</dt><dd class="font-mono text-neutral-700 dark:text-neutral-300">{t.inputHint}</dd></div>
        <div class="flex gap-2"><dt class="shrink-0">routes</dt><dd class="font-mono text-neutral-700 dark:text-neutral-300">{t.routesTo}</dd></div>
      </dl>
      <div class="flex-1"></div>
      <div class="mt-3 flex items-center justify-between">
        {#if t.disabled}
          <span class="text-[10px] text-amber-600">⚠ {t.disabled}</span>
        {:else}
          <span class="text-[10px] text-neutral-500">ready</span>
        {/if}
        <button class="rounded bg-neutral-900 text-white dark:bg-neutral-100 dark:text-neutral-900 px-3 py-1 text-xs disabled:opacity-50"
                disabled={!!t.disabled}
                onclick={t.action}>
          Open →
        </button>
      </div>
    </div>
  {/each}
</div>

<section class="mt-6 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-4 text-xs text-neutral-600 dark:text-neutral-400">
  <h2 class="text-xs font-semibold uppercase tracking-wide text-neutral-700 dark:text-neutral-400 mb-2">
    Governance (HIL + HAR)
  </h2>
  <ul class="space-y-1 list-disc ml-5">
    <li>Every AI draft emits <code>legalDocument.status='pendingReview'</code>; advancing to <code>approved/filed</code> requires <code>approverBengoshiDid</code> (ISCO-2611).</li>
    <li>LLM calls route through Murakumo fleet — on-prem Apple Silicon nodes for confidentiality. No raw matter text hits third-party APIs.</li>
    <li>Audit trail lives in OCEL event stream; replay via <code>etzhayyim audit trail --matter {'{matterDid}'}</code>.</li>
  </ul>
</section>
