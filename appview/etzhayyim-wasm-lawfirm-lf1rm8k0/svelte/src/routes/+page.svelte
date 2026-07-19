<script lang="ts">
  import { onMount } from "svelte";
  import { goto } from "$app/navigation";
  import { listMatters, MATTER_COLUMNS, type Matter, type MatterStatus } from "$lib/xrpc";
  import MatterCard from "$lib/components/MatterCard.svelte";

  let firmDid = $state<string>("");
  let items = $state<Matter[]>([]);
  let loading = $state(true);
  let error = $state<string>("");

  async function load() {
    loading = true; error = "";
    try {
      const r = await listMatters({ firmDid: firmDid || undefined, limit: 200 });
      items = r.items;
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
      items = [];
    } finally {
      loading = false;
    }
  }

  onMount(() => { load(); });

  const byStatus = $derived.by(() => {
    const map = new Map<MatterStatus, Matter[]>();
    for (const s of MATTER_COLUMNS) map.set(s, []);
    for (const m of items) {
      (map.get(m.status) ?? map.get("intake")!).push(m);
    }
    return map;
  });

  const totalOpen = $derived(items.filter((m) => m.status !== "closed" && m.status !== "archived").length);

  function openMatter(m: Matter) {
    goto(`/m/${m.matterRkey}?firm=${encodeURIComponent(m.firmDid)}`);
  }

  function stageColor(s: MatterStatus): string {
    return `bg-matter-${s}`;
  }
</script>

<div class="flex items-center gap-3 mb-3">
  <h1 class="text-lg font-semibold">Matter board</h1>
  <span class="text-xs text-neutral-500">{items.length} matters ({totalOpen} open)</span>
  <div class="flex-1"></div>
  <input
    class="rounded border border-neutral-300 dark:border-neutral-700 bg-transparent px-2 py-1 text-sm font-mono w-[320px]"
    placeholder="firmDid filter (optional)"
    bind:value={firmDid}
    onkeydown={(e) => { if (e.key === "Enter") load(); }}
  />
  <button
    class="rounded bg-neutral-900 text-white dark:bg-neutral-100 dark:text-neutral-900 px-3 py-1 text-sm"
    onclick={load}
  >Refresh</button>
</div>

{#if error}
  <div class="mb-3 rounded border border-red-300 dark:border-red-900 bg-red-50 dark:bg-red-950/40 px-3 py-2 text-xs text-red-700 dark:text-red-300">
    {error}
  </div>
{/if}

{#if loading}
  <div class="text-sm text-neutral-500">Loading matters…</div>
{:else}
  <div class="flex gap-3 overflow-x-auto pb-4">
    {#each MATTER_COLUMNS as status}
      <section class="shrink-0 w-[280px] rounded-lg {stageColor(status)} p-2">
        <header class="flex items-center justify-between mb-2 px-1">
          <h2 class="text-xs font-semibold uppercase tracking-wide text-neutral-700">{status}</h2>
          <span class="text-[10px] text-neutral-600">{byStatus.get(status)?.length ?? 0}</span>
        </header>
        <div class="flex flex-col gap-2">
          {#each byStatus.get(status) ?? [] as m (m.matterRkey)}
            <MatterCard matter={m} onclick={() => openMatter(m)} />
          {/each}
          {#if (byStatus.get(status) ?? []).length === 0}
            <div class="text-[10px] text-neutral-500 italic px-1 py-2">no matters</div>
          {/if}
        </div>
      </section>
    {/each}
  </div>
{/if}
