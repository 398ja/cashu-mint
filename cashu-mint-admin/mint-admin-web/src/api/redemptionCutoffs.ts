import { apiGet, apiPost } from "./client";

/** A request to retire an archived keyset for redemption on a date (cashu-mint#551). */
export interface RedemptionCutoffRequest {
  requestId: string;
  mintId: string;
  keysetId: string;
  redeemUntil: string;
  reason: string;
  requestedBy: string;
  requestedAt: string;
  status: "PENDING_APPROVAL" | "REJECTED" | "APPROVED" | "APPLIED" | "FAILED";
  decidedBy: string | null;
  decidedAt: string | null;
  outcome: string | null;
}

const base = (mintId: string) =>
  `/admin/operations/mints/${encodeURIComponent(mintId)}/redemption-cutoffs`;

export function listRedemptionCutoffs(mintId: string): Promise<RedemptionCutoffRequest[]> {
  return apiGet(base(mintId));
}

export function requestRedemptionCutoff(
  mintId: string,
  body: { keysetId: string; redeemUntil: string; reason: string },
): Promise<RedemptionCutoffRequest> {
  return apiPost(base(mintId), body);
}

export function approveRedemptionCutoff(
  mintId: string,
  requestId: string,
): Promise<RedemptionCutoffRequest> {
  return apiPost(`${base(mintId)}/${encodeURIComponent(requestId)}/approve`, {});
}

export function rejectRedemptionCutoff(
  mintId: string,
  requestId: string,
  reason: string,
): Promise<RedemptionCutoffRequest> {
  return apiPost(`${base(mintId)}/${encodeURIComponent(requestId)}/reject`, { reason });
}
