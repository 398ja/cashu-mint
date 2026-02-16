import type { Page } from "@playwright/test";

/**
 * Simulate admin login by setting session storage tokens and mocking the auth endpoint.
 */
export async function loginAsAdmin(
  page: Page,
  roles: string[] = ["MINT_ADMIN", "USER_ADMIN", "ALERTS_ADMIN", "OPS_ADMIN"],
) {
  await page.addInitScript(
    (r) => {
      sessionStorage.setItem("admin_token", "test-token");
      sessionStorage.setItem("admin_roles", r.join(","));
    },
    roles,
  );

  await page.route("**/admin/auth/me", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ authenticated: true, roles }),
    }),
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
