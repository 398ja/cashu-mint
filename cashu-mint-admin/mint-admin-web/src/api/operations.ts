import { apiGet, apiPost } from "./client";
import type { PagedResponse } from "./lifecycle";

export interface OperationalControlResponse {
  mintId: string;
  controlId: string;
  controlType: string;
  status: string;
  scheduledAt: string | null;
  /** Human-readable description of the action; the API field is `message`. */
  message: string | null;
  /** What the control did, e.g. which keyset a rotation replaced. Null until it completes. */
  outcome: string | null;
}

export function listControls(
  mintId: string,
  params: { page?: number; size?: number },
): Promise<PagedResponse<OperationalControlResponse>> {
  return apiGet(
    `/admin/operations/mints/${encodeURIComponent(mintId)}/controls`,
    params,
  );
}

interface MaintenanceBody {
  reason: string;
  durationMinutes?: number;
}

export function scheduleMaintenance(
  mintId: string,
  body: MaintenanceBody,
): Promise<OperationalControlResponse> {
  return apiPost(
    `/admin/operations/mints/${encodeURIComponent(mintId)}/maintenance/schedule`,
    body,
  );
}

export function startMaintenance(
  mintId: string,
  body: MaintenanceBody,
): Promise<OperationalControlResponse> {
  return apiPost(
    `/admin/operations/mints/${encodeURIComponent(mintId)}/maintenance/start`,
    body,
  );
}

export function completeMaintenance(
  mintId: string,
  body: MaintenanceBody,
): Promise<OperationalControlResponse> {
  return apiPost(
    `/admin/operations/mints/${encodeURIComponent(mintId)}/maintenance/complete`,
    body,
  );
}

export function rotateKeys(
  mintId: string,
  body: MaintenanceBody,
): Promise<OperationalControlResponse> {
  return apiPost(
    `/admin/operations/mints/${encodeURIComponent(mintId)}/keys/rotate`,
    body,
  );
}

export function forceClose(
  mintId: string,
  body: MaintenanceBody,
): Promise<OperationalControlResponse> {
  return apiPost(
    `/admin/operations/mints/${encodeURIComponent(mintId)}/force-close`,
    body,
  );
}
