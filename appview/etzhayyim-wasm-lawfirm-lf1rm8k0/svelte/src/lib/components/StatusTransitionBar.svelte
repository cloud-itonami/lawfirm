<script lang="ts">
  import { MATTER_TRANSITIONS, updateMatterStatus, type MatterStatus, type NextStatus } from "../xrpc";

  interface Props {
    matterDid: string;
    currentStatus: MatterStatus;
    lastConflictCheckRef?: string;
    onTransitioned?: (newStatus: string) => void;
    onNeedConflictCheck?: () => void;
  }
  const { matterDid, currentStatus, lastConflictCheckRef, onTransitioned, onNeedConflictCheck }: Props = $props();

  const allowed: NextStatus[] = $derived((MATTER_TRANSITIONS[currentStatus] ?? []) as NextStatus[]);
  let busy = $state<string>("");
  let error = $state("");

  async function go(next: NextStatus) {
    error = "";
    if (next === "engaged" && currentStatus === "conflictCheck" && !lastConflictCheckRef) {
      onNeedConflictCheck?.();
      error = "conflictCheckRef required — run a conflict scan first.";
      return;
    }
    if (!confirm(`Move matter ${currentStatus} → ${next}?`)) return;
    busy = next;
    try {
      const r = await updateMatterStatus({ matterDid, newStatus: next, conflictCheckRef: lastConflictCheckRef });
      onTransitioned?.(r.newStatus);
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    } finally {
      busy = "";
    }
  }

  const statusBadgeColor = $derived(`bg-matter-${currentStatus}`);
</script>

<div class="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-3">
  <div class="flex items-center justify-between flex-wrap gap-2">
    <div class="flex items-center gap-2">
      <span class="text-xs font-semibold uppercase tracking-wide text-neutral-700 dark:text-neutral-400">Lifecycle</span>
      <span class="rounded text-[10px] uppercase px-1.5 py-0.5 {statusBadgeColor} text-neutral-900">{currentStatus}</span>
    </div>
    {#if allowed.length === 0}
      <span class="text-[10px] text-neutral-500 italic">terminal — close via Close matter</span>
    {:else}
      <div class="flex flex-wrap items-center gap-1">
        <span class="text-[10px] text-neutral-500">Advance to →</span>
        {#each allowed as next}
          <button
            class="rounded border border-neutral-300 dark:border-neutral-700 bg-white dark:bg-neutral-900 hover:bg-neutral-100 dark:hover:bg-neutral-800 px-2 py-0.5 text-[11px] disabled:opacity-50"
            disabled={!!busy}
            onclick={() => go(next)}
          >
            {busy === next ? "…" : next}
          </button>
        {/each}
      </div>
    {/if}
  </div>
  {#if error}
    <div class="mt-2 text-[11px] text-red-600">{error}</div>
  {/if}
  {#if lastConflictCheckRef}
    <div class="mt-1 text-[10px] text-neutral-500 font-mono">linked conflictCheck: {lastConflictCheckRef.split("/").pop()}</div>
  {/if}
</div>
