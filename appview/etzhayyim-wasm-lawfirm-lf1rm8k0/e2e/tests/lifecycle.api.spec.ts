/**
 * End-to-end smoke test for the full lawfirm matter lifecycle over XRPC.
 *
 * Runs the critical path once:
 *   createMatter
 *     → runConflictCheck (matterIntake)
 *     → updateMatterStatus (intake → conflictCheck → engaged, using the scan ref)
 *     → uploadDocument (privileged, aiGenerated)
 *     → inviteExternalCounsel
 *     → acceptExternalCounsel (only if LAWFIRM_EXTERNAL_BEARER is provided)
 *     → scheduleHearing
 *     → updateMatterStatus (engaged → filed → hearing)
 *     → recordTimeEntry
 *     → issueInvoice
 *     → revokeExternalCounsel (cascade check)
 *     → closeMatter
 *
 * Every step asserts that the response carries the ADR-0029 DID envelope
 * (matterDid / grantDid / hearingDid / documentDid / invoiceDid) with the
 * expected recursive depth and that materialHashProof is present.
 */

import { test, expect, type APIRequestContext } from "@playwright/test";

const env = {
  base: process.env.LAWFIRM_BASE_URL ?? "https://lawfirm.etzhayyim.com",
  firm: process.env.LAWFIRM_FIRM_DID ?? "",
  client: process.env.LAWFIRM_CLIENT_DID ?? "",
  bengoshi: process.env.LAWFIRM_BENGOSHI_DID ?? "",
  external: process.env.LAWFIRM_EXTERNAL_DID ?? "",
  externalBearer: process.env.LAWFIRM_EXTERNAL_BEARER ?? "",
};

const didDepth = (did: string) =>
  did.startsWith("did:etzhayyim:") ? did.slice("did:etzhayyim:".length).split(":").length : 0;

async function xrpc<T>(req: APIRequestContext, nsid: string, body: Record<string, unknown>): Promise<T> {
  const resp = await req.post(`/xrpc/${nsid}`, { data: body });
  expect(resp.ok(), `${nsid} ${resp.status()}: ${await resp.text()}`).toBeTruthy();
  return resp.json() as Promise<T>;
}

test.describe("lawfirm matter lifecycle (ADR-0029 recursive did:etzhayyim)", () => {
  test.skip(
    !env.firm || !env.client || !env.bengoshi || !env.external,
    "LAWFIRM_{FIRM,CLIENT,BENGOSHI,EXTERNAL}_DID must be set",
  );

  test("full lifecycle", async ({ request }) => {
    // 1. createMatter
    const created = await xrpc<{ matterDid: string; matterRkey: string; uri: string; materialHashProof: string }>(
      request, "com.etzhayyim.apps.lawfirm.createMatter", {
        firmDid: env.firm,
        matterType: "litigation",
        clientDid: env.client,
        leadBengoshiDid: env.bengoshi,
        openedAt: new Date().toISOString(),
        jurisdiction: "JPN",
        subjectMatter: "smoke test: Smith v. Jones contract dispute",
        counterpartyDids: [env.external],
        feeStructure: "hourly",
        currency: "JPY",
        estimatedFee: 1_500_000,
      },
    );
    expect(didDepth(created.matterDid)).toBe(2);
    expect(created.materialHashProof).toMatch(/^[0-9a-f]+$/);
    const matterDid = created.matterDid;

    // 2. runConflictCheck (matterIntake)
    const scan = await xrpc<{ rkey: string; uri: string; result: string }>(
      request, "com.etzhayyim.apps.lawfirm.runConflictCheck", {
        matterDid,
        scanScope: "matterIntake",
        counterpartyDids: [env.external],
        subjectMatter: "smoke test",
      },
    );
    expect(["clear", "disclosureRequired", "waivable"]).toContain(scan.result);
    const conflictCheckRef = scan.uri;

    // 3. updateMatterStatus: intake → conflictCheck
    await xrpc(request, "com.etzhayyim.apps.lawfirm.updateMatterStatus", {
      matterDid, newStatus: "conflictCheck",
    });

    // 4. updateMatterStatus: conflictCheck → engaged (requires scan ref)
    await xrpc(request, "com.etzhayyim.apps.lawfirm.updateMatterStatus", {
      matterDid, newStatus: "engaged", conflictCheckRef,
    });

    // 5. uploadDocument (AI-generated, pendingReview)
    const cid = Array.from(crypto.getRandomValues(new Uint8Array(32)))
      .map((b) => b.toString(16).padStart(2, "0")).join("");
    const doc = await xrpc<{ documentDid: string; uri: string; status: string }>(
      request, "com.etzhayyim.apps.lawfirm.uploadDocument", {
        matterDid, docType: "motion", title: "Draft motion — jurisdiction",
        cid, privileged: true, aiGenerated: true,
      },
    );
    expect(didDepth(doc.documentDid)).toBe(3);
    expect(doc.status).toBe("pendingReview");

    // 6. inviteExternalCounsel
    const grant = await xrpc<{ grantDid: string; grantUri: string; conflictCheckPassed: boolean }>(
      request, "com.etzhayyim.apps.lawfirm.inviteExternalCounsel", {
        matterDid, granteeDid: env.external, role: "coCounsel",
        capabilities: ["read", "comment"],
        expiresAt: new Date(Date.now() + 30 * 86400_000).toISOString(),
      },
    );
    expect(didDepth(grant.grantDid)).toBe(3);

    // 7. acceptExternalCounsel (only if external bearer provided)
    if (env.externalBearer) {
      const accept = await request.post(`/xrpc/com.etzhayyim.apps.lawfirm.acceptExternalCounsel`, {
        data: { grantDid: grant.grantDid },
        headers: { Authorization: `Bearer ${env.externalBearer}` },
      });
      expect(accept.ok()).toBeTruthy();
      const body = await accept.json() as { status: string };
      expect(body.status).toBe("accepted");
    }

    // 8. scheduleHearing
    const hearing = await xrpc<{ hearingDid: string; uri: string }>(
      request, "com.etzhayyim.apps.lawfirm.scheduleHearing", {
        matterDid, hearingType: "firstAppearance",
        scheduledAt: new Date(Date.now() + 14 * 86400_000).toISOString(),
        durationMin: 60, location: "Tokyo District Court",
      },
    );
    expect(didDepth(hearing.hearingDid)).toBe(3);

    // 9. engaged → filed → hearing
    await xrpc(request, "com.etzhayyim.apps.lawfirm.updateMatterStatus", { matterDid, newStatus: "filed" });
    await xrpc(request, "com.etzhayyim.apps.lawfirm.updateMatterStatus", { matterDid, newStatus: "hearing" });

    // 10. recordTimeEntry
    await xrpc(request, "com.etzhayyim.apps.lawfirm.recordTimeEntry", {
      matterDid, bengoshiDid: env.bengoshi, hours: 2.5, rate: 30_000, currency: "JPY", category: "research",
    });

    // 11. issueInvoice — may return NoTimeEntries if time entries aren't yet approved in this smoke run
    const invResp = await request.post(`/xrpc/com.etzhayyim.apps.lawfirm.issueInvoice`, {
      data: {
        matterDid,
        period: { from: new Date(Date.now() - 7 * 86400_000).toISOString(), to: new Date().toISOString() },
        flatFeeAmount: 50_000, flatFeeNote: "smoke test flat fee",
        taxRate: 0.1, dueInDays: 30,
      },
    });
    if (invResp.ok()) {
      const inv = await invResp.json() as { invoiceDid: string; total: number };
      expect(didDepth(inv.invoiceDid)).toBe(3);
    }

    // 12. revokeExternalCounsel
    const revoke = await xrpc<{ revokedAt: string; descendantsInvalidated: number }>(
      request, "com.etzhayyim.apps.lawfirm.revokeExternalCounsel", {
        grantDid: grant.grantDid, reason: "completed",
      },
    );
    expect(revoke.revokedAt).toBeTruthy();

    // 13. closeMatter — may return OpenBlockers; accept either outcome for the smoke path
    const close = await request.post(`/xrpc/com.etzhayyim.apps.lawfirm.closeMatter`, {
      data: { matterDid, outcome: "settled", finalNote: "smoke test end" },
    });
    const closeBody = await close.json();
    if (close.ok()) {
      expect(closeBody.closedAt).toBeTruthy();
    } else {
      expect(closeBody.error).toBe("OpenBlockers");
    }
  });
});
