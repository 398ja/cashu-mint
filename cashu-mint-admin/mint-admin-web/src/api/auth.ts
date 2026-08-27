import { generateCorrelationId } from "@/lib/correlation";

export interface AuthMeResponse {
  authenticated: boolean;
  roles: string[];
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
  if (!response.ok) return { authenticated: false, roles: [], npub: null };
  const body = (await response.json()) as {
    roles?: string[];
    principal?: { npub?: string };
  };
  return {
    authenticated: true,
    roles: body.roles ?? [],
    npub: body.principal?.npub ?? null,
  };
}
