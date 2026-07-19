<script lang="ts">
  import { inviteExternalCounsel, shortDid, didDepth } from "../xrpc";

  interface Props {
    matterDid: string;
    counterpartyOrgDids: string[];
    open: boolean;
    onclose: () => void;
  }
  const { matterDid, counterpartyOrgDids, open, onclose }: Props = $props();

  let granteeDid = $state("");
  let granteeHandle = $state("");
  let role = $state<"coCounsel" | "local" | "advisory" | "reviewer">("coCounsel");
  let capRead = $state(true);
  let capComment = $state(true);
  let capUpload = $state(false);
  let capPropose = $state(false);
  let capSign = $state(false);
  let capSchedule = $state(false);
  let expiresInDays = $state(30);
  let message = $state("");
  let conflictDetected = $derived(
    granteeDid.startsWith("did:etzhayyim:") &&
    counterpartyOrgDids.some((cp) => cp === granteeDid || granteeDid.startsWith(cp + ":"))
  );
  let grantDidValid = $derived(didDepth(granteeDid) === 1);
  let busy = $state(false);
  let error = $state("");
  let success = $state<{ grantDid: string; materialHashProof: string } | null>(null);

  async function submit() {
    error = ""; success = null;
    if (!grantDidValid) { error = "granteeDid must be a depth-1 did:etzhayyim root"; return; }
    if (conflictDetected) { error = "ConflictDetected: grantee overlaps with matter counterparties"; return; }
    const caps: string[] = [];
    if (capRead) caps.push("read");
    if (capComment) caps.push("comment");
    if (capUpload) caps.push("uploadDocument");
    if (capPropose) caps.push("propose");
    if (capSign) caps.push("sign");
    if (capSchedule) caps.push("scheduleHearing");
    const expiresAt = new Date(Date.now() + expiresInDays * 86400_000).toISOString();
    busy = true;
    try {
      const resp = await inviteExternalCounsel({
        matterDid, granteeDid, granteeHandle: granteeHandle || undefined,
        role, capabilities: caps, expiresAt, message: message || undefined,
      });
      success = { grantDid: resp.grantDid, materialHashProof: resp.materialHashProof };
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    } finally {
      busy = false;
    }
  }
</script>

{#if open}
  <div class="fixed inset-0 z-50 flex items-center justify-center p-4">
    <button
      type="button"
      class="absolute inset-0 bg-black/40"
      aria-label="Close external counsel dialog"
      onclick={onclose}
    ></button>
    <div
      class="relative w-full max-w-lg rounded-xl bg-white shadow-xl dark:bg-neutral-900"
      role="dialog"
      aria-modal="true"
      aria-labelledby="invite-counsel-title"
    >
      <div class="border-b border-neutral-200 dark:border-neutral-800 px-5 py-3 flex items-center justify-between">
        <h2 id="invite-counsel-title" class="text-base font-semibold">Invite external counsel</h2>
        <button type="button" class="text-neutral-500 hover:text-neutral-900 dark:hover:text-neutral-100" onclick={onclose}>✕</button>
      </div>

      {#if success}
        <div class="p-5 space-y-2">
          <div class="text-sm text-green-700 dark:text-green-400">Grant issued.</div>
          <div class="font-mono text-xs break-all rounded bg-neutral-100 dark:bg-neutral-800 p-2">
            {success.grantDid}
          </div>
          <div class="text-[10px] text-neutral-500">
            materialHashProof: <span class="font-mono">{success.materialHashProof.slice(0, 48)}…</span>
          </div>
          <div class="text-xs text-neutral-600 dark:text-neutral-400">
            A consent.request has been sent to <span class="font-mono">{shortDid(granteeDid)}</span> via AT Protocol DM.
          </div>
          <button type="button" class="mt-3 w-full rounded bg-neutral-900 text-white dark:bg-neutral-100 dark:text-neutral-900 px-3 py-2 text-sm" onclick={onclose}>
            Done
          </button>
        </div>
      {:else}
        <div class="p-5 space-y-3">
          <div>
            <label class="text-xs font-medium" for="invite-counsel-grantee-did">Grantee DID (did:etzhayyim root)</label>
            <input id="invite-counsel-grantee-did" class="w-full mt-1 rounded border border-neutral-300 dark:border-neutral-700 bg-transparent px-2 py-1 text-sm font-mono"
              placeholder="did:etzhayyim:a1b2c3d4e5f6a1b2c3d4e5f6"
              bind:value={granteeDid} />
            {#if granteeDid && !grantDidValid}
              <div class="text-[10px] text-red-600 mt-1">DID must be depth 1 (24-hex root).</div>
            {/if}
            {#if conflictDetected}
              <div class="text-[10px] text-red-600 mt-1">⚠ Conflict: grantee overlaps with counterparty org on this matter.</div>
            {/if}
          </div>
          <div>
            <label class="text-xs font-medium" for="invite-counsel-handle">Handle (display)</label>
            <input id="invite-counsel-handle" class="w-full mt-1 rounded border border-neutral-300 dark:border-neutral-700 bg-transparent px-2 py-1 text-sm"
              placeholder="jdoe-otherfirm.etzhayyim.com" bind:value={granteeHandle} />
          </div>
          <div>
            <label class="text-xs font-medium" for="invite-counsel-role">Role</label>
            <select id="invite-counsel-role" class="w-full mt-1 rounded border border-neutral-300 dark:border-neutral-700 bg-transparent px-2 py-1 text-sm" bind:value={role}>
              <option value="coCounsel">co-counsel</option>
              <option value="local">local counsel</option>
              <option value="advisory">advisory</option>
              <option value="reviewer">reviewer</option>
            </select>
          </div>
          <fieldset>
            <legend class="text-xs font-medium">Capabilities</legend>
            <div class="mt-1 grid grid-cols-2 gap-1 text-xs">
              <label><input type="checkbox" bind:checked={capRead}/> read</label>
              <label><input type="checkbox" bind:checked={capComment}/> comment</label>
              <label><input type="checkbox" bind:checked={capUpload}/> uploadDocument</label>
              <label><input type="checkbox" bind:checked={capPropose}/> propose</label>
              <label><input type="checkbox" bind:checked={capSign}/> sign</label>
              <label><input type="checkbox" bind:checked={capSchedule}/> scheduleHearing</label>
            </div>
          </fieldset>
          <div>
            <label class="text-xs font-medium" for="invite-counsel-expires">Expires in (days) — matter scope only (ethical wall)</label>
            <input id="invite-counsel-expires" type="number" min="1" max="365"
              class="w-full mt-1 rounded border border-neutral-300 dark:border-neutral-700 bg-transparent px-2 py-1 text-sm"
              bind:value={expiresInDays} />
          </div>
          <div>
            <label class="text-xs font-medium" for="invite-counsel-message">Message</label>
            <textarea id="invite-counsel-message" class="w-full mt-1 rounded border border-neutral-300 dark:border-neutral-700 bg-transparent px-2 py-1 text-sm"
              rows="2" bind:value={message}></textarea>
          </div>

          {#if error}
            <div class="text-xs text-red-600">{error}</div>
          {/if}

          <div class="flex items-center justify-end gap-2 pt-2">
            <button type="button" class="px-3 py-1.5 text-sm" onclick={onclose}>Cancel</button>
            <button
              type="button"
              class="rounded bg-neutral-900 text-white dark:bg-neutral-100 dark:text-neutral-900 px-3 py-1.5 text-sm disabled:opacity-50"
              disabled={busy || !grantDidValid || conflictDetected}
              onclick={submit}
            >
              {busy ? "Issuing grant…" : "Mint grant DID"}
            </button>
          </div>
        </div>
      {/if}
    </div>
  </div>
{/if}
