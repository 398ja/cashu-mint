import { apiGet, apiPost } from "./client";
import type { PagedResponse } from "./lifecycle";

export interface AlertActionResponse {
  alertId: string;
  mintId: string;
  severity: string;
  summary: string;
  acknowledged: boolean;
  silenced: boolean;
  silenceMinutes: number | null;
  escalations: string[];
  message: string | null;
}

export function listAlerts(params: {
  severity?: string;
  acknowledged?: boolean;
  silenced?: boolean;
  mintId?: string;
  page?: number;
  size?: number;
}): Promise<PagedResponse<AlertActionResponse>> {
  return apiGet("/admin/alerts", params);
}

export function getAlert(alertId: string): Promise<AlertActionResponse> {
  return apiGet(`/admin/alerts/${encodeURIComponent(alertId)}`);
}

export function createAlert(body: {
  mintId: string;
  alertId: string;
  severity: string;
  summary: string;
  labels: Record<string, string>;
}): Promise<AlertActionResponse> {
  return apiPost("/admin/alerts", body);
}

interface ActorDto {
  id: string;
  displayName: string;
}

export function acknowledgeAlert(
  alertId: string,
  body: { requestedBy: ActorDto; reason: string },
): Promise<AlertActionResponse> {
  return apiPost(
    `/admin/alerts/${encodeURIComponent(alertId)}/acknowledge`,
    body,
  );
}

export function silenceAlert(
  alertId: string,
  body: { requestedBy: ActorDto; durationMinutes: number; reason: string },
): Promise<AlertActionResponse> {
  return apiPost(
    `/admin/alerts/${encodeURIComponent(alertId)}/silence`,
    body,
  );
}

export function unsilenceAlert(
  alertId: string,
  body: { requestedBy: ActorDto; reason: string },
): Promise<AlertActionResponse> {
  return apiPost(
    `/admin/alerts/${encodeURIComponent(alertId)}/unsilence`,
    body,
  );
}

export function escalateAlert(
  alertId: string,
  body: { requestedBy: ActorDto; policyId: string; reason: string },
): Promise<AlertActionResponse> {
  return apiPost(
    `/admin/alerts/${encodeURIComponent(alertId)}/escalate`,
    body,
  );
}
