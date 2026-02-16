import { apiGet, apiPost } from "./client";
import type { PagedResponse } from "./lifecycle";

export interface ConfigurationRevision {
  revisionId: number;
  versionTag: string;
  parameters: Record<string, string>;
}

export interface ConfigurationActionResponse {
  mintId: string;
  appliedRevision: string;
  parameters: Record<string, string>;
  message: string;
}

export function listRevisions(
  mintId: string,
  params: { page?: number; size?: number },
): Promise<PagedResponse<ConfigurationRevision>> {
  return apiGet(
    `/admin/configuration/mints/${encodeURIComponent(mintId)}/revisions`,
    params,
  );
}

export function previewConfiguration(
  mintId: string,
  body: {
    requestedBy: { id: string; displayName: string };
    baseRevisionId: string;
    proposedConfiguration: Record<string, unknown>;
  },
): Promise<ConfigurationActionResponse> {
  return apiPost(
    `/admin/configuration/mints/${encodeURIComponent(mintId)}/preview`,
    body,
  );
}

export function applyConfiguration(
  mintId: string,
  body: {
    requestedBy: { id: string; displayName: string };
    proposedConfiguration: Record<string, unknown>;
    changeSummary: string;
  },
): Promise<ConfigurationActionResponse> {
  return apiPost(
    `/admin/configuration/mints/${encodeURIComponent(mintId)}/apply`,
    body,
  );
}

export function rollbackConfiguration(
  mintId: string,
  body: {
    requestedBy: { id: string; displayName: string };
    targetRevisionId: string;
    reason: string;
  },
): Promise<ConfigurationActionResponse> {
  return apiPost(
    `/admin/configuration/mints/${encodeURIComponent(mintId)}/rollback`,
    body,
  );
}
