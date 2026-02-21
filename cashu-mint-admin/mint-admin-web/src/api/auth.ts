import { apiGet } from "./client";

export interface AuthMeResponse {
  authenticated: boolean;
  roles: string[];
}

export function fetchAuthMe(): Promise<AuthMeResponse> {
  return apiGet<AuthMeResponse>("/admin/auth/me");
}
