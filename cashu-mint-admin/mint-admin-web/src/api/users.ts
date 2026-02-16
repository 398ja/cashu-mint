import { apiGet, apiPost, apiPut } from "./client";
import type { PagedResponse } from "./lifecycle";

export interface UserResponse {
  userId: string;
  displayName: string;
  email: string;
  roles: string[];
  active: boolean;
  message: string | null;
}

export interface CredentialResetResponse {
  userId: string;
  resetToken: string;
  message: string;
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

export function createUser(body: {
  requestedBy: { id: string; displayName: string };
  userId: string;
  displayName: string;
  email: string;
  roles: string[];
}): Promise<UserResponse> {
  return apiPost("/admin/users", body);
}

export function updateUser(
  userId: string,
  body: {
    requestedBy: { id: string; displayName: string };
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
    requestedBy: { id: string; displayName: string };
    roles: string[];
    justification: string;
  },
): Promise<UserResponse> {
  return apiPost(
    `/admin/users/${encodeURIComponent(userId)}/roles`,
    body,
  );
}

export function resetCredentials(
  userId: string,
  body: {
    requestedBy: { id: string; displayName: string };
    reason: string;
  },
): Promise<CredentialResetResponse> {
  return apiPost(
    `/admin/users/${encodeURIComponent(userId)}/reset-credentials`,
    body,
  );
}

export function deactivateUser(
  userId: string,
  body: {
    requestedBy: { id: string; displayName: string };
    reason: string;
  },
): Promise<UserResponse> {
  return apiPost(
    `/admin/users/${encodeURIComponent(userId)}/deactivate`,
    body,
  );
}
