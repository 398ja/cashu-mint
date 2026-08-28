import { apiGet, apiPost, apiPut } from "./client";

export interface PagedResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface MintDetail {
  mintId: string;
  lifecycleState: string;
  configurationRevisionId: number;
  configurationParameters: Record<string, string>;
  versionTag: string;
  lastActor: string;
  lastAction: string;
  lastModified: string;
}

export interface LifecycleActionResponse {
  operation: string;
  mintId: string;
  previousState: string | null;
  currentState: string;
  versionTag: string;
  changed: boolean;
  message: string;
}

export function listMints(params: {
  state?: string;
  q?: string;
  page?: number;
  size?: number;
}): Promise<PagedResponse<MintDetail>> {
  return apiGet("/admin/lifecycle/mints", params);
}

export function getMint(mintId: string): Promise<MintDetail> {
  return apiGet(`/admin/lifecycle/mints/${encodeURIComponent(mintId)}`);
}

export function createMint(body: {
  mintId: string;
  metadata: { displayName: string; description: string; tags: string[] };
  configuration: Record<string, unknown>;
}): Promise<LifecycleActionResponse> {
  return apiPost("/admin/lifecycle/mints", body);
}

export function updateMint(
  mintId: string,
  body: {
    metadata: { displayName: string; description: string; tags: string[] };
    configuration: Record<string, unknown>;
    revisionId: string;
  },
): Promise<LifecycleActionResponse> {
  return apiPut(`/admin/lifecycle/mints/${encodeURIComponent(mintId)}`, body);
}

export function pauseMint(
  mintId: string,
  body: { reason: string; correlationId?: string },
): Promise<LifecycleActionResponse> {
  return apiPost(
    `/admin/lifecycle/mints/${encodeURIComponent(mintId)}/pause`,
    body,
  );
}

export function resumeMint(
  mintId: string,
  body: { reason: string; correlationId?: string },
): Promise<LifecycleActionResponse> {
  return apiPost(
    `/admin/lifecycle/mints/${encodeURIComponent(mintId)}/resume`,
    body,
  );
}

export function retireMint(
  mintId: string,
  body: { reason: string; correlationId?: string },
): Promise<LifecycleActionResponse> {
  return apiPost(
    `/admin/lifecycle/mints/${encodeURIComponent(mintId)}/retire`,
    body,
  );
}

/** A keyset the shared vault holds for a mint. Never carries key material. */
export interface KeySet {
  keySetId: string;
  unit: string;
  /** SIGNING or ARCHIVED. Archived keysets still verify and redeem (ADR-0004). */
  state: string;
  createdAt: string;
}

export function listKeySets(
  mintId: string,
  page = 0,
): Promise<PagedResponse<KeySet>> {
  return apiGet(
    `/admin/lifecycle/mints/${encodeURIComponent(mintId)}/keysets`,
    { page },
  );
}
