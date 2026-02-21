import { apiGet, apiPost } from "./client";

export interface HealthCheckResponse {
  mintId: string;
  status: string;
  uptime: string | null;
  version: string | null;
  checks: Record<string, string>;
  message: string | null;
}

export function getMintHealth(mintId: string): Promise<HealthCheckResponse> {
  return apiGet(`/admin/health/mints/${encodeURIComponent(mintId)}`);
}

export function acknowledgeMintHealth(
  mintId: string,
  body: { requestedBy: { id: string; displayName: string }; reason: string },
): Promise<HealthCheckResponse> {
  return apiPost(
    `/admin/health/mints/${encodeURIComponent(mintId)}/acknowledge`,
    body,
  );
}
