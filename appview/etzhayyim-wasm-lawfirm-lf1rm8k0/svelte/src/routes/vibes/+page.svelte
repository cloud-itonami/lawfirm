<script lang="ts">
  /**
   * Firm space — practice-area channels mirrored from kotodama.jsonld `space.convos`.
   * Each channel card links to yoro.etzhayyim.com where the actual feed renders; lawfirm
   * surfaces only the index + deep-link so we don't duplicate AT Protocol transport.
   */

  interface Channel {
    name: string;
    kind: "firm" | "private";
    description: string;
    default?: boolean;
  }

  const CHANNELS: Channel[] = [
    { name: "lawfirm-matters",    kind: "firm",    description: "Active matter board — new filings, status transitions, deadline alerts", default: true },
    { name: "lawfirm-hearings",   kind: "firm",    description: "Upcoming court events — cron-driven reminders 24h before scheduledAt"  },
    { name: "lawfirm-docs-review",kind: "firm",    description: "ISCO-2611 review queue for AI-generated drafts (RULE-003)"              },
    { name: "lawfirm-external",   kind: "private", description: "External counsel collaboration threads (matter-scoped, ethical wall)"  },
  ];

  function channelUrl(c: Channel): string {
    return `https://yoro.etzhayyim.com/space/lawfirm/${encodeURIComponent(c.name)}`;
  }

  function kindBadge(kind: Channel["kind"]): string {
    return kind === "private" ? "🔒 private" : "🏢 firm";
  }
</script>

<div class="flex items-center justify-between mb-3">
  <h1 class="text-lg font-semibold">Vibes — firm space</h1>
  <span class="text-xs text-neutral-500">{CHANNELS.length} channel(s)</span>
</div>

<p class="text-xs text-neutral-500 mb-4">
  Practice-area channels declared in <span class="font-mono">kotodama.jsonld</span> <code>space.convos</code>.
  Posts are AT records (<code>app.bsky.feed.post</code>) authored by the firm DID and federated to followers.
  The <strong>external</strong> channel is private (invite-only, matter-scoped).
</p>

<div class="grid grid-cols-1 md:grid-cols-2 gap-3">
  {#each CHANNELS as c}
    <a href={channelUrl(c)}
       class="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-4 shadow-sm hover:shadow-md transition block">
      <div class="flex items-center gap-2">
        <h2 class="text-sm font-semibold">
          <span class="font-mono text-neutral-500">#</span>{c.name}
        </h2>
        <span class="text-[10px] uppercase tracking-wide text-neutral-500">{kindBadge(c.kind)}</span>
        {#if c.default}
          <span class="text-[10px] rounded bg-green-100 text-green-800 px-1.5 py-0.5">default</span>
        {/if}
      </div>
      <p class="mt-2 text-xs text-neutral-600 dark:text-neutral-400">{c.description}</p>
      <div class="mt-3 text-[10px] text-neutral-500 font-mono">yoro.etzhayyim.com/space/lawfirm/{c.name} →</div>
    </a>
  {/each}
</div>

<section class="mt-6 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-4">
  <h2 class="text-xs font-semibold uppercase tracking-wide text-neutral-700 dark:text-neutral-400">Federation posture</h2>
  <dl class="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-xs">
    <dt class="text-neutral-500">space.joinRule</dt>
    <dd class="font-mono">invite</dd>
    <dt class="text-neutral-500">space.historyVisibility</dt>
    <dd class="font-mono">firm-only</dd>
    <dt class="text-neutral-500">AT Record author</dt>
    <dd class="font-mono">firm did:etzhayyim root</dd>
    <dt class="text-neutral-500">Ethical wall</dt>
    <dd>lawfirm-external posts filtered by matter scope (hash prefix)</dd>
  </dl>
</section>
