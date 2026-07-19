import { defineConfig, devices } from "@playwright/test";

/**
 * E2E smoke tests for lawfirm.etzhayyim.com Phase A–D.
 *
 * Usage:
 *   cd 60-apps/etzhayyim-project-lawfirm/appview/etzhayyim-wasm-lawfirm-lf1rm8k0/e2e
 *   pnpm install
 *   LAWFIRM_BASE_URL=https://lawfirm.etzhayyim.com \
 *   LAWFIRM_AUTH_BEARER=<session-jwt-from-etzhayyim-auth-login> \
 *   LAWFIRM_FIRM_DID=did:etzhayyim:aaaa...    \
 *   LAWFIRM_CLIENT_DID=did:etzhayyim:bbbb...  \
 *   LAWFIRM_BENGOSHI_DID=did:etzhayyim:cccc.. \
 *   LAWFIRM_EXTERNAL_DID=did:etzhayyim:dddd.. \
 *   pnpm test
 *
 * For local dev against wrangler preview, use LAWFIRM_BASE_URL=http://localhost:8787.
 */

export default defineConfig({
  testDir: "./tests",
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI ? [["github"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: process.env.LAWFIRM_BASE_URL ?? "https://lawfirm.etzhayyim.com",
    trace: "on-first-retry",
    extraHTTPHeaders: process.env.LAWFIRM_AUTH_BEARER
      ? { Authorization: `Bearer ${process.env.LAWFIRM_AUTH_BEARER}` }
      : undefined,
  },
  projects: [
    { name: "api",    use: { ...devices["Desktop Chrome"] }, testMatch: /.*\.api\.spec\.ts/ },
    { name: "ui",     use: { ...devices["Desktop Chrome"] }, testMatch: /.*\.ui\.spec\.ts/ },
  ],
});
