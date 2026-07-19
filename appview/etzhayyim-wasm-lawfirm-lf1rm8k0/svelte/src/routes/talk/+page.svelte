<script lang="ts">
  import { onMount } from "svelte";
  import { listMatters, shortDid, type Matter, type MatterStatus } from "$lib/xrpc";

  let firmDid = $state("");
  let matters = $state<Matter[]>([]);
  let loading = $state(true);

  async function load() {
    loading = true;
    try {
      const r = await listMatters({ firmDid: firmDid || undefined, limit: 200 });
      matters = r.items.filter((m) => m.status !== "closed" && m.status !== "archived");
    } finally {
      loading = false;
    }
  }
  onMount(() => { load(); });

  function convoUrl(m: Matter): string {
    return `https://yoro.etzhayyim.com/convo/${encodeURIComponent(m.matterRkey)}?firm=${encodeURIComponent(m.firmDid)}`;
  }
  function dmUrl(peerDid: string): string {
    return `https://yoro.etzhayyim.com/dm/${encodeURIComponent(peerDid)}`;
  }
</script>

<div class="flex items-center gap-3 mb-3">
  <h1 class="text-lg font-semibold">Talk — matter-scoped convo</h1>
  <span class="text-xs text-neutral-500">{matters.length} active matter(s)</span>
  <div class="flex-1"></div>
  <input class="rounded border border-neutral-300 dark:border-neutral-700 bg-transparent px-2 py-1 text-sm font-mono w-[320px]"
         placeholder="firmDid filter (optional)"
         bind:value={firmDid}
         onkeydown={(e) => { if (e.key === "Enter") load(); }} />
  <button class="rounded bg-neutral-900 text-white dark:bg-neutral-100 dark:text-neutral-900 px-3 py-1 text-sm" onclick={load}>Refresh</button>
</div>

<p class="text-xs text-neutral-500 mb-4">
  Each active matter spawns an AT Protocol convo (matter-scoped DM). Opening a thread
  delegates to <span class="font-mono">yoro.etzhayyim.com</span> — lawfirm only controls the index and
  reads convoId from the matter record. Client + lead + co-counsel + accepted external counsel
  are auto-members; privileged tag is applied to every message.
</p>

{#if loading}
  <div class="text-sm text-neutral-500">Loading…</div>
{:else if matters.length === 0}
  <div class="rounded-lg border border-neutral-200 dark:border-neutral-800 p-6 text-sm text-neutral-500 text-center">
    No active matters. Create one from the <a class="underline" href="/">matter board</a>.
  </div>
{:else}
  <ul class="divide-y divide-neutral-200 dark:divide-neutral-800 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900">
    {#each matters as m}
      <li class="p-3 flex items-start gap-3">
        <span class="rounded text-[10px] uppercase bg-matter-{m.status as MatterStatus} px-1.5 py-0.5 text-neutral-900 shrink-0 mt-0.5">{m.status}</span>
        <div class="flex-1 min-w-0">
          <div class="text-sm font-medium truncate">{m.matterNumber ?? m.matterRkey}</div>
          <div class="text-[11px] text-neutral-500 flex flex-wrap gap-2 mt-0.5">
            <span>client <button class="hover:underline font-mono" onclick={() => location.assign(dmUrl(m.clientDid))}>{shortDid(m.clientDid)}</button></span>
            <span>lead <button class="hover:underline font-mono" onclick={() => location.assign(dmUrl(m.leadBengoshiDid))}>{shortDid(m.leadBengoshiDid)}</button></span>
            {#if (m.coCounselDids ?? []).length > 0}
              <span>co · {(m.coCounselDids ?? []).length}</span>
            {/if}
          </div>
        </div>
        <div class="flex gap-1 shrink-0">
          <a class="rounded border border-neutral-300 dark:border-neutral-700 px-2 py-1 text-[11px] hover:bg-neutral-100 dark:hover:bg-neutral-800" href={convoUrl(m)}>open convo</a>
          <a class="rounded border border-neutral-300 dark:border-neutral-700 px-2 py-1 text-[11px] hover:bg-neutral-100 dark:hover:bg-neutral-800" href={`/m/${m.matterRkey}?firm=${encodeURIComponent(m.firmDid)}`}>matter detail</a>
        </div>
      </li>
    {/each}
  </ul>
{/if}
