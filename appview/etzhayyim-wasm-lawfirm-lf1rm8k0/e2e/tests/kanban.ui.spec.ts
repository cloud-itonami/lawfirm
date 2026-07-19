/**
 * UI smoke: loads the Kanban board and the matter detail page, asserts the
 * 4-tab header renders, the 10 matter status columns exist, the ADR-0029
 * banner is visible, and the invite-counsel modal opens.
 */

import { test, expect } from "@playwright/test";

const MATTER_COLUMNS = [
  "intake", "conflictCheck", "engaged", "filed",
  "hearing", "trial", "judgment", "appeal",
  "execution", "closed",
] as const;

test("Kanban board renders 10 status columns + 4-tab header", async ({ page }) => {
  await page.goto("/");
  await expect(page.locator("text=Matter board")).toBeVisible();
  for (const s of MATTER_COLUMNS) {
    await expect(page.locator(`h2:has-text("${s}")`).first()).toBeVisible();
  }
  for (const tab of ["Live", "Talk", "Vibes", "Provider"]) {
    await expect(page.locator(`nav a:has-text("${tab}")`)).toBeVisible();
  }
  await expect(page.locator("text=ADR-0029")).toBeVisible();
});

test("Invite counsel dialog opens + conflict detection visible", async ({ page }) => {
  const firmDid = process.env.LAWFIRM_FIRM_DID;
  const matterRkey = process.env.LAWFIRM_SMOKE_MATTER_RKEY;
  test.skip(!firmDid || !matterRkey, "LAWFIRM_FIRM_DID + LAWFIRM_SMOKE_MATTER_RKEY required");

  await page.goto(`/m/${matterRkey}?firm=${encodeURIComponent(firmDid!)}`);
  await expect(page.locator("text=External counsel")).toBeVisible();
  await page.click("text=+ Invite counsel");
  await expect(page.locator('h2:has-text("Invite external counsel")')).toBeVisible();

  // Typing a depth-1 DID that overlaps with a counterparty should trigger the
  // client-side conflict warning.
  await page.fill('input[placeholder*="did:etzhayyim"]', firmDid!);
  await expect(page.locator("text=Conflict")).toBeVisible({ timeout: 2_000 });

  await page.click("text=Cancel");
});

test("Status transition bar respects lifecycle graph", async ({ page }) => {
  const firmDid = process.env.LAWFIRM_FIRM_DID;
  const matterRkey = process.env.LAWFIRM_SMOKE_MATTER_RKEY;
  test.skip(!firmDid || !matterRkey, "LAWFIRM_FIRM_DID + LAWFIRM_SMOKE_MATTER_RKEY required");

  await page.goto(`/m/${matterRkey}?firm=${encodeURIComponent(firmDid!)}`);
  await expect(page.locator("text=Lifecycle")).toBeVisible();
  // closed / archived must NOT be offered via the transition bar (closeMatter owns that).
  const bar = page.locator("div:has-text('Lifecycle')").first();
  await expect(bar.locator("text=closed")).toHaveCount(0);
  await expect(bar.locator("text=archived")).toHaveCount(0);
});
