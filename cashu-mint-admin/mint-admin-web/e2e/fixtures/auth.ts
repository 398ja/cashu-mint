import type { Page } from "@playwright/test";

/** The test Operator: the super-admin npub the backend fixtures also use. */
export const TEST_PUBKEY =
  "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";
export const TEST_NPUB =
  "npub10xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqpkge6d";
/** The same Operator's private key, for the in-browser key sign-in. */
export const TEST_NSEC =
  "nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsmhltgl";

/**
 * Inject a NIP-07 provider. The page builds its signer from `window.nostr`, so
 * this is the seam a browser extension would occupy -- planting a session
 * instead would skip the handshake the login page exists to run.
 */
export async function installSigner(page: Page, pubkey: string = TEST_PUBKEY) {
  await page.addInitScript((pk: string) => {
    (window as unknown as { nostr: unknown }).nostr = {
      getPublicKey: async () => pk,
      signEvent: async (event: Record<string, unknown>) => ({
        ...event,
        id: "0".repeat(64),
        pubkey: pk,
        sig: "0".repeat(128),
      }),
    };
  }, pubkey);
}

/**
 * Simulate a signed-in Operator: a signer in the page and a NAP server that
 * accepts it, so both the handshake and the cookie resume work.
 */
export async function loginAsAdmin(
  page: Page,
  roles: string[] = ["MINT_ADMIN", "USER_ADMIN", "ALERTS_ADMIN", "OPS_ADMIN"],
  options: { resume?: boolean } = {},
) {
  await installSigner(page);
  await mockAuthRoutes(page, roles, options);
}

/**
 * A NAP server that accepts the test Operator, with no signer in the page --
 * for the sign-in kinds that bring their own key rather than an extension.
 */
/**
 * What each role carries, mirroring AdminRole in mint-admin-core. The server
 * resolves permissions from roles, so a fixture that skipped them would sign in
 * an Operator the real one never issues.
 *
 * A copy of server knowledge, so AdminRoleWebFixtureContractTest reads this literal
 * and fails the build when the two disagree.
 */
const ROLE_PERMISSIONS: Record<string, string[]> = {
  SUPER_ADMIN: [
    "mint:lifecycle",
    "audit:read",
    "dashboard:read",
    "users:manage",
    "operators:manage",
    "operations:execute",
  ],
  MINT_ADMIN: ["mint:lifecycle", "audit:read", "dashboard:read"],
  USER_ADMIN: ["users:manage", "dashboard:read"],
  OPS_ADMIN: ["operations:execute", "dashboard:read"],
};

function permissionsFor(roles: string[]): string[] {
  return [...new Set(roles.flatMap((r) => ROLE_PERMISSIONS[r] ?? []))];
}

export async function mockAuthRoutes(
  page: Page,
  roles: string[] = ["MINT_ADMIN", "USER_ADMIN", "ALERTS_ADMIN", "OPS_ADMIN"],
  options: { resume?: boolean } = {},
) {
  const principal = { npub: TEST_NPUB, pubkey: TEST_PUBKEY };
  const permissions = permissionsFor(roles);
  const expiresAt = Math.floor(Date.now() / 1000) + 3600;
  const json = (body: unknown) => ({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify(body),
  });

  await page.route("**/api/v1/auth/init", (route) =>
    route.fulfill(
      json({
        challenge_id: "challenge-id",
        challenge: "challenge",
        auth_url: "http://localhost:3173/api/v1/auth/complete",
        auth_method: "POST",
        issued_at: Math.floor(Date.now() / 1000),
        expires_at: expiresAt,
      }),
    ),
  );
  await page.route("**/api/v1/auth/complete", (route) =>
    route.fulfill(
      json({ status: "ok", principal, roles, permissions, expires_at: expiresAt }),
    ),
  );
  // `resume: false` is the browser arriving with no cookie: the login page then
  // offers the handshake instead of walking straight through to the dashboard.
  await page.route("**/api/v1/auth/session", (route) =>
    options.resume === false
      ? route.fulfill({ status: 401, contentType: "application/json", body: "{}" })
      : route.fulfill(
          json({ status: "ok", principal, roles, permissions, expires_at: expiresAt }),
        ),
  );
}

/**
 * Mock any API path with a canned JSON response.
 */
export async function mockApiResponse(
  page: Page,
  urlPattern: string,
  body: unknown,
  status = 200,
) {
  await page.route(urlPattern, (route) =>
    route.fulfill({
      status,
      contentType: "application/json",
      body: JSON.stringify(body),
    }),
  );
}

/**
 * Convenience to build a standard paged response envelope.
 */
export function pagedResponse<T>(items: T[], page = 0, size = 20) {
  return {
    items,
    page,
    size,
    totalItems: items.length,
    totalPages: Math.max(1, Math.ceil(items.length / size)),
  };
}
