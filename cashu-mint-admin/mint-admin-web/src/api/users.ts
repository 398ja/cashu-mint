import { apiGet, apiPost, apiPut } from "./client";
import type { PagedResponse } from "./lifecycle";

export interface UserResponse {
  userId: string;
  /** The Operator's Nostr identity. Absent on the answer to a change, which names none. */
  npub: string | null;
  displayName: string;
  email: string;
  roles: string[];
  active: boolean;
  message: string | null;
  /** The Super Administrator, named in configuration: no profile to suspend or remove. */
  configurationAnchored: boolean;
}

export function listUsers(params: {
  active?: boolean;
  role?: string;
  q?: string;
  page?: number;
  size?: number;
}): Promise<PagedResponse<UserResponse>> {
  return apiGet("/admin/users", params);
}

export function getUser(userId: string): Promise<UserResponse> {
  return apiGet(`/admin/users/${encodeURIComponent(userId)}`);
}

export function updateUser(
  userId: string,
  body: {
    displayName: string;
    email: string;
    roles: string[];
  },
): Promise<UserResponse> {
  return apiPut(`/admin/users/${encodeURIComponent(userId)}`, body);
}

export function assignRoles(
  userId: string,
  body: {
    roles: string[];
    justification: string;
  },
): Promise<UserResponse> {
  return apiPost(
    `/admin/users/${encodeURIComponent(userId)}/roles`,
    body,
  );
}

export function deactivateUser(
  userId: string,
  body: {
    reason: string;
  },
): Promise<UserResponse> {
  return apiPost(
    `/admin/users/${encodeURIComponent(userId)}/deactivate`,
    body,
  );
}

export function createUser(body: {
  userId: string;
  displayName: string;
  npub: string;
  roles: string[];
}): Promise<UserResponse> {
  return apiPost("/admin/users", body);
}

export function reinstateUser(
  userId: string,
  body: { reason: string },
): Promise<UserResponse> {
  return apiPost(
    `/admin/users/${encodeURIComponent(userId)}/reinstate`,
    body,
  );
}
