<script lang="ts">
  import { issueInvoice } from "../xrpc";

  interface Props {
    matterDid: string;
    defaultCurrency?: string;
    open: boolean;
    onclose: () => void;
  }
  const { matterDid, defaultCurrency = "JPY", open, onclose }: Props = $props();

  const isoDate = (d: Date) => d.toISOString().slice(0, 10);
  const today = new Date();
  const firstOfMonth = new Date(today.getFullYear(), today.getMonth(), 1);

  let fromDate = $state(isoDate(firstOfMonth));
  let toDate = $state(isoDate(today));
  let flatFeeAmount = $state(0);
  let flatFeeNote = $state("");
  let expenseRows = $state<Array<{ description: string; amount: number }>>([]);
  let taxRatePct = $state(10);
  let discountAmount = $state(0);
  let dueInDays = $state(30);
  let invoiceNumber = $state("");
  let busy = $state(false);
  let error = $state("");
  let success = $state<{ invoiceDid: string; total: number; currency: string; billed: number } | null>(null);

  function addExpense()  { expenseRows = [...expenseRows, { description: "", amount: 0 }]; }
  function removeExpense(i: number) { expenseRows = expenseRows.filter((_, idx) => idx !== i); }

  async function submit() {
    error = ""; success = null; busy = true;
    try {
      const r = await issueInvoice({
        matterDid,
        period: {
          from: new Date(fromDate + "T00:00:00Z").toISOString(),
          to:   new Date(toDate   + "T23:59:59Z").toISOString(),
        },
        flatFeeAmount: flatFeeAmount || undefined,
        flatFeeNote:   flatFeeNote || undefined,
        expenses:      expenseRows.filter((r) => r.description && r.amount > 0),
        taxRate:       taxRatePct / 100,
        discountAmount: discountAmount || undefined,
        dueInDays,
        invoiceNumber: invoiceNumber || undefined,
      });
      success = { invoiceDid: r.invoiceDid, total: r.total, currency: r.currency, billed: r.timeEntriesBilled };
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
      aria-label="Close invoice dialog"
      onclick={onclose}
    ></button>
    <div
      class="relative w-full max-w-xl rounded-xl bg-white shadow-xl dark:bg-neutral-900"
      role="dialog"
      aria-modal="true"
      aria-labelledby="issue-invoice-title"
    >
      <div class="border-b border-neutral-200 dark:border-neutral-800 px-5 py-3 flex items-center justify-between">
        <h2 id="issue-invoice-title" class="text-base font-semibold">Issue invoice</h2>
        <button type="button" class="text-neutral-500 hover:text-neutral-900 dark:hover:text-neutral-100" onclick={onclose}>✕</button>
      </div>

      {#if success}
        <div class="p-5 space-y-2">
          <div class="text-sm text-green-700 dark:text-green-400">Invoice generated.</div>
          <div class="font-mono text-xs break-all rounded bg-neutral-100 dark:bg-neutral-800 p-2">{success.invoiceDid}</div>
          <div class="text-xs">{success.billed} time entry/entries billed · total <strong>{success.total.toLocaleString()} {success.currency}</strong></div>
          <button type="button" class="mt-3 w-full rounded bg-neutral-900 text-white dark:bg-neutral-100 dark:text-neutral-900 px-3 py-2 text-sm" onclick={onclose}>Done</button>
        </div>
      {:else}
        <div class="p-5 space-y-3">
          <div class="grid grid-cols-2 gap-2">
            <label class="text-xs font-medium">From
              <input type="date" class="mt-1 w-full rounded border border-neutral-300 dark:border-neutral-700 bg-transparent px-2 py-1 text-sm" bind:value={fromDate} />
            </label>
            <label class="text-xs font-medium">To
              <input type="date" class="mt-1 w-full rounded border border-neutral-300 dark:border-neutral-700 bg-transparent px-2 py-1 text-sm" bind:value={toDate} />
            </label>
          </div>

          <div>
            <label class="text-xs font-medium" for="invoice-number">Invoice number (optional)</label>
            <input id="invoice-number" class="mt-1 w-full rounded border border-neutral-300 dark:border-neutral-700 bg-transparent px-2 py-1 text-sm" bind:value={invoiceNumber} placeholder="INV-2026-04-001" />
          </div>

          <div>
            <div class="text-xs font-medium">Flat fee</div>
            <div class="mt-1 grid grid-cols-[120px_1fr] gap-2">
              <input type="number" min="0" step="0.01" class="rounded border border-neutral-300 dark:border-neutral-700 bg-transparent px-2 py-1 text-sm" bind:value={flatFeeAmount} />
              <input class="rounded border border-neutral-300 dark:border-neutral-700 bg-transparent px-2 py-1 text-sm" placeholder="note" bind:value={flatFeeNote} />
            </div>
          </div>

          <div>
            <div class="flex items-center justify-between">
              <div class="text-xs font-medium">Expenses</div>
              <button type="button" class="text-[10px] text-neutral-500 hover:text-neutral-900 dark:hover:text-neutral-100" onclick={addExpense}>+ add line</button>
            </div>
            <div class="mt-1 space-y-1">
              {#each expenseRows as e, i}
                <div class="grid grid-cols-[1fr_120px_20px] gap-1">
                  <input class="rounded border border-neutral-300 dark:border-neutral-700 bg-transparent px-2 py-1 text-xs" placeholder="description" bind:value={e.description} />
                  <input type="number" min="0" step="0.01" class="rounded border border-neutral-300 dark:border-neutral-700 bg-transparent px-2 py-1 text-xs" bind:value={e.amount} />
                  <button type="button" class="text-neutral-400 hover:text-red-600" onclick={() => removeExpense(i)}>✕</button>
                </div>
              {/each}
              {#if expenseRows.length === 0}
                <div class="text-[10px] text-neutral-500 italic">none</div>
              {/if}
            </div>
          </div>

          <div class="grid grid-cols-3 gap-2">
            <label class="text-xs font-medium">Tax %
              <input type="number" min="0" max="100" step="0.01" class="mt-1 w-full rounded border border-neutral-300 dark:border-neutral-700 bg-transparent px-2 py-1 text-sm" bind:value={taxRatePct} />
            </label>
            <label class="text-xs font-medium">Discount
              <input type="number" min="0" step="0.01" class="mt-1 w-full rounded border border-neutral-300 dark:border-neutral-700 bg-transparent px-2 py-1 text-sm" bind:value={discountAmount} />
            </label>
            <label class="text-xs font-medium">Due in (days)
              <input type="number" min="1" max="180" class="mt-1 w-full rounded border border-neutral-300 dark:border-neutral-700 bg-transparent px-2 py-1 text-sm" bind:value={dueInDays} />
            </label>
          </div>

          {#if error}
            <div class="text-xs text-red-600">{error}</div>
          {/if}

          <div class="flex items-center justify-end gap-2 pt-2">
            <button type="button" class="px-3 py-1.5 text-sm" onclick={onclose}>Cancel</button>
            <button
              type="button"
              class="rounded bg-neutral-900 text-white dark:bg-neutral-100 dark:text-neutral-900 px-3 py-1.5 text-sm disabled:opacity-50"
              disabled={busy}
              onclick={submit}
            >
              {busy ? "Issuing…" : "Issue invoice"}
            </button>
          </div>
          <div class="text-[10px] text-neutral-500">
            Server pulls approved time entries in period → mints invoiceDid (did:etzhayyim doc kind, cid = SHA-256 of invoice content) → flips timeEntry.status='billed'.
          </div>
        </div>
      {/if}
    </div>
  </div>
{/if}
