<script lang="ts">
  import { runConflictCheck, shortDid, type ConflictFinding, type ConflictResult } from "../xrpc";

  interface Props {
    matterDid: string;
    counterpartyDids: string[];
    candidateDid?: string;
    defaultScope?: "matterIntake" | "externalCounselInvite";
    open: boolean;
    onclose: (res?: { rkey: string; result: ConflictResult; wallId?: string }) => void;
  }
  const { matterDid, counterpartyDids, candidateDid, defaultScope = "matterIntake", open, onclose }: Props = $props();

  let scope = $state<"matterIntake" | "externalCounselInvite">("matterIntake");
  let subject = $state("");
  let cps = $state<string[]>([]);
  let candidate = $state("");
  let busy = $state(false);
  let error = $state("");
  let result = $state<{ rkey: string; result: ConflictResult; conflicts: ConflictFinding[]; wallId?: string } | null>(null);

  const resultColor = $derived(
    result?.result === "clear"              ? "bg-green-100  text-green-800"  :
    result?.result === "disclosureRequired" ? "bg-blue-100   text-blue-800"   :
    result?.result === "waivable"           ? "bg-amber-100  text-amber-800"  :
    result?.result === "blocked"            ? "bg-red-100    text-red-800"    : ""
  );

  $effect(() => {
    if (!open) return;
    scope = defaultScope;
    cps = [...(counterpartyDids ?? [])];
    candidate = candidateDid ?? "";
    subject = "";
    error = "";
    result = null;
  });

  async function submit() {
    error = ""; result = null; busy = true;
    try {
      const r = await runConflictCheck({
        matterDid,
        scanScope: scope,
        counterpartyDids: scope === "matterIntake" ? cps : undefined,
        candidateDid:     scope === "externalCounselInvite" ? candidate : undefined,
        subjectMatter: subject || undefined,
      });
      result = { rkey: r.rkey, result: r.result, conflicts: r.conflicts, wallId: r.wallId };
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    } finally {
      busy = false;
    }
  }

  function done() {
    onclose(result ? { rkey: result.rkey, result: result.result, wallId: result.wallId } : undefined);
  }
</script>

{#if open}
  <div class="fixed inset-0 z-50 flex items-center justify-center p-4">
    <button
      type="button"
      class="absolute inset-0 bg-black/40"
      aria-label="Close conflict check dialog"
      onclick={() => onclose()}
    ></button>
    <div
      class="relative w-full max-w-lg rounded-xl bg-white shadow-xl dark:bg-neutral-900"
      role="dialog"
      aria-modal="true"
      aria-labelledby="conflict-check-title"
    >
      <div class="border-b border-neutral-200 dark:border-neutral-800 px-5 py-3 flex items-center justify-between">
        <h2 id="conflict-check-title" class="text-base font-semibold">Run conflict check</h2>
        <button type="button" class="text-neutral-500 hover:text-neutral-900 dark:hover:text-neutral-100" onclick={() => onclose()}>✕</button>
      </div>

      <div class="p-5 space-y-3">
        <div>
          <label class="text-xs font-medium" for="conflict-check-scope">Scope</label>
          <select id="conflict-check-scope" class="mt-1 w-full rounded border border-neutral-300 dark:border-neutral-700 bg-transparent px-2 py-1 text-sm" bind:value={scope}>
            <option value="matterIntake">matterIntake (counterparty vs firm portfolio)</option>
            <option value="externalCounselInvite">externalCounselInvite (candidate vs counterparties)</option>
          </select>
        </div>

        {#if scope === "matterIntake"}
          <div>
            <label class="text-xs font-medium" for="conflict-check-cps">Counterparty DIDs (one per line)</label>
            <textarea id="conflict-check-cps" class="mt-1 w-full rounded border border-neutral-300 dark:border-neutral-700 bg-transparent px-2 py-1 text-xs font-mono"
              rows="3"
              value={cps.join("\n")}
              oninput={(e) => { cps = (e.currentTarget.value || "").split(/\s+/).filter(Boolean); }}></textarea>
          </div>
        {:else}
          <div>
            <label class="text-xs font-medium" for="conflict-check-candidate">Candidate DID (grantee)</label>
            <input id="conflict-check-candidate" class="mt-1 w-full rounded border border-neutral-300 dark:border-neutral-700 bg-transparent px-2 py-1 text-sm font-mono"
              bind:value={candidate} placeholder="did:etzhayyim:a1b2c3..." />
          </div>
        {/if}

        <div>
          <label class="text-xs font-medium" for="conflict-check-subject">Subject matter</label>
          <input id="conflict-check-subject" class="mt-1 w-full rounded border border-neutral-300 dark:border-neutral-700 bg-transparent px-2 py-1 text-sm" bind:value={subject} />
        </div>

        {#if error}
          <div class="text-xs text-red-600">{error}</div>
        {/if}

        {#if result}
          <div class="rounded border border-neutral-200 dark:border-neutral-800 p-3 space-y-2">
            <div class="flex items-center gap-2">
              <span class="text-xs font-medium">Result</span>
              <span class="text-xs uppercase tracking-wide px-2 py-0.5 rounded {resultColor}">{result.result}</span>
              {#if result.wallId}
                <span class="text-[10px] rounded bg-neutral-100 dark:bg-neutral-800 px-1.5 py-0.5">wall: {result.wallId}</span>
              {/if}
            </div>
            {#if result.conflicts.length}
              <ul class="text-[11px] space-y-1">
                {#each result.conflicts as c}
                  <li class="border-l-2 border-amber-400 pl-2">
                    <strong>{c.kind}</strong>{#if c.partyDid} — <span class="font-mono">{shortDid(c.partyDid)}</span>{/if}
                    {#if c.note} — {c.note}{/if}
                  </li>
                {/each}
              </ul>
            {:else}
              <div class="text-[11px] text-neutral-500 italic">No conflicts found.</div>
            {/if}
          </div>
        {/if}

        <div class="flex items-center justify-end gap-2 pt-2">
          {#if result}
            <button type="button" class="rounded bg-neutral-900 text-white dark:bg-neutral-100 dark:text-neutral-900 px-3 py-1.5 text-sm" onclick={done}>Use result</button>
          {:else}
            <button type="button" class="px-3 py-1.5 text-sm" onclick={() => onclose()}>Cancel</button>
            <button type="button" class="rounded bg-neutral-900 text-white dark:bg-neutral-100 dark:text-neutral-900 px-3 py-1.5 text-sm disabled:opacity-50"
                    disabled={busy || (scope === "externalCounselInvite" && !candidate)}
                    onclick={submit}>{busy ? "Scanning…" : "Run scan"}</button>
          {/if}
        </div>
      </div>
    </div>
  </div>
{/if}
