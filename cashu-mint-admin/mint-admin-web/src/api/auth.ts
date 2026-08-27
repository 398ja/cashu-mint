import { generateCorrelationId } from "@/lib/correlation";

export interface AuthMeResponse {
  authenticated: boolean;
  roles: string[];
  /** What the roles add up to. The server resolves it; the browser never derives it. */
  permissions: string[];
  npub: string | null;
}

/**
 * Reads the NAP session. Deliberately not routed through the shared client:
 * an absent session is the answer here, not the failure that sends the browser
 * back to the login page.
 */
export async function fetchAuthMe(): Promise<AuthMeResponse> {
  const response = await fetch("/api/v1/auth/session", {
    headers: { "X-Correlation-Id": generateCorrelationId() },
  });
  if (!response.ok)
    return { authenticated: false, roles: [], permissions: [], npub: null };
  const body = (await response.json()) as {
    roles?: string[];
    permissions?: string[];
    principal?: { npub?: string };
  };
  return {
    authenticated: true,
    roles: body.roles ?? [],
    permissions: body.permissions ?? [],
    npub: body.principal?.npub ?? null,
  };
}
