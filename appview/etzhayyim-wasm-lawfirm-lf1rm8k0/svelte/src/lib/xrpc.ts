/**
 * Typed XRPC client for lawfirm app procedures.
 *
 * All lawfirm commands proxy through the PDS XRPC gateway at atproto.etzhayyim.com.
 * Session JWT is pulled from the appshellv2 auth store.
 *
 * Base NSIDs:
 *   com.etzhayyim.apps.lawfirm.{createMatter,listMatters,recordTimeEntry,registerLawfirm,
 *     inviteExternalCounsel,acceptExternalCounsel,revokeExternalCounsel,
 *     uploadDocument,scheduleHearing,closeMatter}
 */

const XRPC_SERVICE = "https://atproto.etzhayyim.com";

type XrpcOptions = {
  bearerToken?: string;
  timeout?: number;
};

function getSessionJwt(): string {
  if (typeof localStorage === "undefined") return "";
  for (const key of ["atproto:session", "etzhayyim:session"]) {
    const raw = localStorage.getItem(key);
    if (!raw) continue;
    try {
      const session = JSON.parse(raw) as { accessJwt?: string; accessToken?: string; jwt?: string };
      const jwt = session.accessJwt ?? session.accessToken ?? session.jwt ?? "";
      if (jwt) return jwt;
    } catch {
      if (raw.startsWith("eyJ")) return raw;
    }
  }
  return "";
}

async function xrpc<T>(method: "GET" | "POST", nsid: string, body?: unknown, opts: XrpcOptions = {}): Promise<T> {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), opts.timeout ?? 30_000);
  try {
    const url = new URL(`/xrpc/${nsid}`, XRPC_SERVICE);
    const init: RequestInit = {
      method,
      headers: {
        ...(method === "POST" ? { "content-type": "application/json" } : {}),
        ...((opts.bearerToken ?? getSessionJwt()) ? { authorization: `Bearer ${opts.bearerToken ?? getSessionJwt()}` } : {}),
      },
      signal: controller.signal,
    };
    if (method === "GET" && body && typeof body === "object") {
      for (const [key, value] of Object.entries(body as Record<string, unknown>)) {
        if (value === undefined || value === null) continue;
        url.searchParams.set(key, String(value));
      }
    } else if (method === "POST") {
      init.body = JSON.stringify(body ?? {});
    }
    const res = await fetch(url, init);
    const data = await res.json().catch((_err) => ({}));
    if (!res.ok) throw new Error((data as { message?: string; error?: string }).message ?? (data as { error?: string }).error ?? `${nsid} failed (${res.status})`);
    return data as T;
  } finally {
    window.clearTimeout(timeout);
  }
}

function atProcedure<T = unknown>(nsid: string, body?: unknown, opts?: XrpcOptions): Promise<T> {
  return xrpc<T>("POST", nsid, body, opts);
}

function atQuery<T = unknown>(nsid: string, params?: Record<string, unknown>, opts?: XrpcOptions): Promise<T> {
  return xrpc<T>("GET", nsid, params, opts);
}

export type MatterStatus =
  | "intake" | "conflictCheck" | "engaged" | "filed" | "hearing"
  | "trial" | "judgment" | "appeal" | "execution" | "closed" | "archived";

export const MATTER_COLUMNS: MatterStatus[] = [
  "intake", "conflictCheck", "engaged", "filed",
  "hearing", "trial", "judgment", "appeal",
  "execution", "closed",
];

export interface Matter {
  firmDid: string;
  matterDid: string;
  matterRkey: string;
  matterNumber?: string;
  matterType: string;
  clientDid: string;
  leadBengoshiDid: string;
  coCounselDids?: string[];
  counterpartyDids?: string[];
  jurisdiction?: string;
  procedureCode?: string;
  subjectMatter?: string;
  feeStructure?: string;
  estimatedFee?: number;
  currency?: string;
  confidentiality?: string;
  status: MatterStatus;
  openedAt: string;
  closedAt?: string;
  nextHearingAt?: string;
  privilegedDocCount?: number;
  billableHoursTotal?: number;
}

export interface ExternalCounselGrant {
  matterDid: string;
  grantDid: string;
  granteeDid: string;
  granteeHandle?: string;
  granteeOrgDid?: string;
  inviterDid: string;
  role: "coCounsel" | "local" | "advisory" | "reviewer";
  capabilities: string[];
  scope: string;
  status: "invited" | "accepted" | "revoked" | "expired";
  expiresAt: string;
  createdAt: string;
  acceptedAt?: string;
  revokedAt?: string;
  revokeReason?: string;
}

export interface Hearing {
  matterDid: string;
  hearingDid: string;
  hearingType: string;
  scheduledAt: string;
  status: "scheduled" | "inProgress" | "completed" | "postponed" | "cancelled";
  courtId?: string;
  judgeDid?: string;
  location?: string;
  outcome?: string;
}

export interface LegalDocument {
  matterDid: string;
  documentDid: string;
  docType: string;
  title: string;
  cid: string;
  version: number;
  authorDid: string;
  approverBengoshiDid?: string;
  privileged: boolean;
  status: "draft" | "pendingReview" | "approved" | "filed" | "superseded" | "withdrawn";
  createdAt: string;
  updatedAt: string;
}

export type ConflictResult = "clear" | "disclosureRequired" | "waivable" | "blocked";

export interface ConflictFinding {
  kind: "priorRepresentation" | "adverseParty" | "ownershipChain" | "bengoshiConflict" | "firmConflict" | "other";
  partyDid?: string;
  priorMatterDid?: string;
  note?: string;
}

export interface Invoice {
  matterDid: string;
  invoiceDid: string;
  firmDid: string;
  clientDid: string;
  invoiceNumber?: string;
  subtotal: number;
  taxAmount?: number;
  discountAmount?: number;
  total: number;
  currency: string;
  issuedAt: string;
  dueAt?: string;
  paidAt?: string;
  status: "draft" | "sent" | "partiallyPaid" | "paid" | "overdue" | "void";
}

export type NextStatus =
  | "conflictCheck" | "engaged" | "filed" | "hearing"
  | "trial" | "judgment" | "appeal" | "execution"
  | "conciliated" | "withdrawn";

/** Client-side mirror of src/app.ts MATTER_TRANSITIONS (keep in sync). */
export const MATTER_TRANSITIONS: Record<string, NextStatus[]> = {
  intake:         ["conflictCheck", "engaged", "withdrawn"],
  conflictCheck:  ["engaged", "withdrawn"],
  engaged:        ["filed"],
  filed:          ["hearing", "judgment", "withdrawn"],
  hearing:        ["trial", "judgment", "conciliated"],
  trial:          ["judgment"],
  judgment:       ["appeal", "execution"],
  appeal:         ["judgment"],
  execution:      [],
};

// ── Procedures ──────────────────────────────────────────────────────────

export async function createMatter(input: {
  firmDid: string;
  matterType: string;
  clientDid: string;
  leadBengoshiDid: string;
  openedAt: string;
  jurisdiction?: string;
  procedureCode?: string;
  subjectMatter?: string;
  matterNumber?: string;
  estimatedFee?: number;
  currency?: string;
  feeStructure?: string;
  confidentiality?: string;
  coCounselDids?: string[];
  counterpartyDids?: string[];
}): Promise<{ matterDid: string; matterRkey: string; uri: string; materialHashProof: string }> {
  return atProcedure("com.etzhayyim.apps.lawfirm.createMatter", input);
}

export async function listMatters(input: {
  firmDid?: string;
  status?: MatterStatus;
  limit?: number;
  offset?: number;
}): Promise<{ items: Matter[]; offset: number; limit: number; total: number }> {
  return atQuery("com.etzhayyim.apps.lawfirm.listMatters", input);
}

export async function inviteExternalCounsel(input: {
  matterDid: string;
  granteeDid: string;
  granteeHandle?: string;
  role: "coCounsel" | "local" | "advisory" | "reviewer";
  capabilities: string[];
  expiresAt: string;
  message?: string;
}): Promise<{ grantDid: string; grantUri: string; conflictCheckPassed: boolean; materialHashProof: string }> {
  return atProcedure("com.etzhayyim.apps.lawfirm.inviteExternalCounsel", input);
}

export async function acceptExternalCounsel(input: {
  grantDid: string;
  granteeSignalPubkey?: string;
}): Promise<{ grantDid: string; status: "accepted"; acceptedAt: string; wrappedDocumentKeyCount: number }> {
  return atProcedure("com.etzhayyim.apps.lawfirm.acceptExternalCounsel", input);
}

export async function revokeExternalCounsel(input: {
  grantDid: string;
  reason: "completed" | "conflict" | "breach" | "request" | "expired" | "other";
  noteToGrantee?: string;
}): Promise<{ grantDid: string; revokedAt: string; descendantsInvalidated: number }> {
  return atProcedure("com.etzhayyim.apps.lawfirm.revokeExternalCounsel", input);
}

export async function uploadDocument(input: {
  matterDid: string;
  docType: string;
  title: string;
  cid: string;
  privileged: boolean;
  authorDid?: string;
  aiGenerated?: boolean;
  supersedesDocumentDid?: string;
}): Promise<{ documentDid: string; uri: string; status: string; materialHashProof: string }> {
  return atProcedure("com.etzhayyim.apps.lawfirm.uploadDocument", input);
}

export async function scheduleHearing(input: {
  matterDid: string;
  hearingType: string;
  scheduledAt: string;
  courtId?: string;
  judgeDid?: string;
  durationMin?: number;
  location?: string;
  attendees?: string[];
  agenda?: string;
}): Promise<{ hearingDid: string; uri: string; saibanJikenRef: string; materialHashProof: string }> {
  return atProcedure("com.etzhayyim.apps.lawfirm.scheduleHearing", input);
}

export async function closeMatter(input: {
  matterDid: string;
  outcome: string;
  finalNote?: string;
  archiveBlob?: boolean;
}): Promise<{ matterDid?: string; closedAt?: string; grantsRevoked?: number; openBlockers?: string[] }> {
  return atProcedure("com.etzhayyim.apps.lawfirm.closeMatter", input);
}

export async function runConflictCheck(input: {
  matterDid: string;
  scanScope: "matterIntake" | "externalCounselInvite" | "periodicAudit";
  counterpartyDids?: string[];
  candidateDid?: string;
  subjectMatter?: string;
}): Promise<{
  rkey: string;
  uri: string;
  result: ConflictResult;
  conflicts: ConflictFinding[];
  wallId?: string;
  scannedAt: string;
}> {
  return atProcedure("com.etzhayyim.apps.lawfirm.runConflictCheck", input);
}

export async function issueInvoice(input: {
  matterDid: string;
  period: { from: string; to: string };
  includeTimeEntryRefs?: string[];
  flatFeeAmount?: number;
  flatFeeNote?: string;
  expenses?: Array<{ description: string; amount: number }>;
  taxRate?: number;
  discountAmount?: number;
  dueInDays?: number;
  invoiceNumber?: string;
}): Promise<{
  invoiceDid: string;
  uri: string;
  subtotal: number;
  taxAmount: number;
  total: number;
  currency: string;
  timeEntriesBilled: number;
  dueAt: string;
  materialHashProof: string;
}> {
  return atProcedure("com.etzhayyim.apps.lawfirm.issueInvoice", input);
}

export async function updateMatterStatus(input: {
  matterDid: string;
  newStatus: NextStatus;
  reason?: string;
  conflictCheckRef?: string;
}): Promise<{
  matterDid: string;
  previousStatus: string;
  newStatus: string;
  updatedAt: string;
}> {
  return atProcedure("com.etzhayyim.apps.lawfirm.updateMatterStatus", input);
}

// ── Derived views (graph MVs) ───────────────────────────────────────────

export async function listGrants(matterDid: string, includeRevoked = false): Promise<ExternalCounselGrant[]> {
  // Phase D: reads view_lawfirm_external_counsel_access via XRPC procedure.
  return atProcedure("com.etzhayyim.apps.lawfirm.listGrants", { matterDid, includeRevoked }).then(
    (r: any) => (r?.items ?? []) as ExternalCounselGrant[],
    () => [] as ExternalCounselGrant[],
  );
}

export async function listHearings(matterDid: string): Promise<Hearing[]> {
  return atQuery("com.etzhayyim.apps.lawfirm.listHearings", { matterDid }).then(
    (r: any) => (r?.items ?? []) as Hearing[],
    () => [] as Hearing[],
  );
}

export async function listDocuments(matterDid: string): Promise<LegalDocument[]> {
  return atQuery("com.etzhayyim.apps.lawfirm.listDocuments", { matterDid }).then(
    (r: any) => (r?.items ?? []) as LegalDocument[],
    () => [] as LegalDocument[],
  );
}

export async function listInvoices(matterDid: string): Promise<(Invoice & { ageingBucket?: string; daysOverdue?: number })[]> {
  return atProcedure("com.etzhayyim.apps.lawfirm.listInvoices", { matterDid }).then(
    (r: any) => (r?.items ?? []) as Invoice[],
    () => [] as Invoice[],
  );
}

export async function listConflictChecks(matterDid: string): Promise<Array<{
  rkey: string;
  result: ConflictResult;
  scanScope: string;
  scannedAt: string;
  conflictsCount?: number;
  wallId?: string;
}>> {
  return atProcedure("com.etzhayyim.apps.lawfirm.listConflictChecks", { matterDid }).then(
    (r: any) => (r?.items ?? []),
    () => [],
  );
}

// ── DID helpers (ADR-0029) ─────────────────────────────────────────────

export const didDepth = (did: string): number =>
  did.startsWith("did:etzhayyim:") ? did.slice("did:etzhayyim:".length).split(":").length : 0;

export const shortDid = (did: string, segs = 1): string => {
  if (!did.startsWith("did:etzhayyim:")) return did;
  const parts = did.slice("did:etzhayyim:".length).split(":");
  const take = parts.slice(-segs).map((p) => `${p.slice(0, 6)}…`).join(":");
  return `did:etzhayyim:…:${take}`;
};

export const isCurrentCaller = (_did: string): boolean => {
  // Stub: replace with real comparison against session.accountDid.
  return Boolean(getSessionJwt());
};
