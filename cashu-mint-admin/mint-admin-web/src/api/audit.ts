import { apiGet } from "./client";
import type { PagedResponse } from "./lifecycle";

export interface AuditEvent {
  mintId: string;
  sequence: number;
  actor: string;
  action: string;
  timestamp: string;
  configurationRevisionId: number | null;
}

export function listAuditEvents(params: {
  mintId?: string;
  actor?: string;
  action?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}): Promise<PagedResponse<AuditEvent>> {
  return apiGet("/admin/audit/events", params);
}
