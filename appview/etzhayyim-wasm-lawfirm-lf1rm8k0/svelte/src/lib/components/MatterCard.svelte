<script lang="ts">
  import { shortDid } from "../xrpc";
  import type { Matter } from "../xrpc";

  interface Props {
    matter: Matter;
    onclick?: () => void;
  }
  const { matter, onclick }: Props = $props();

  const confidentialityBadge = $derived(
    matter.confidentiality === "ethicalWall" ? "🔒 ethical wall" :
    matter.confidentiality === "matter" ? "🔐 matter-scoped" : "firm"
  );
</script>

<button
  class="w-full text-left rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-3 shadow-sm hover:shadow-md transition"
  onclick={onclick}
>
  <div class="flex items-start justify-between gap-2">
    <div class="font-medium text-sm leading-tight">
      {matter.matterNumber ?? matter.matterRkey}
    </div>
    <span class="text-[10px] uppercase tracking-wide text-neutral-500">{matter.matterType}</span>
  </div>
  {#if matter.subjectMatter}
    <div class="mt-1 text-xs text-neutral-600 dark:text-neutral-400 line-clamp-2">
      {matter.subjectMatter}
    </div>
  {/if}
  <div class="mt-2 flex flex-wrap gap-1 text-[10px] text-neutral-500">
    <span class="rounded bg-neutral-100 dark:bg-neutral-800 px-1.5 py-0.5" title={matter.clientDid}>
      client: {shortDid(matter.clientDid)}
    </span>
    <span class="rounded bg-neutral-100 dark:bg-neutral-800 px-1.5 py-0.5" title={matter.leadBengoshiDid}>
      lead: {shortDid(matter.leadBengoshiDid)}
    </span>
    {#if matter.jurisdiction}
      <span class="rounded bg-neutral-100 dark:bg-neutral-800 px-1.5 py-0.5">{matter.jurisdiction}</span>
    {/if}
  </div>
  <div class="mt-2 flex items-center justify-between text-[10px] text-neutral-500">
    <span>{confidentialityBadge}</span>
    {#if matter.nextHearingAt}
      <span>⚖️ {new Date(matter.nextHearingAt).toLocaleDateString()}</span>
    {:else if matter.billableHoursTotal}
      <span>⏱ {matter.billableHoursTotal.toFixed(1)}h</span>
    {/if}
  </div>
</button>
