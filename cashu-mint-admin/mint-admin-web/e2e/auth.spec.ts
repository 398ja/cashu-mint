import { test, expect } from "@playwright/test";
import { loginAsAdmin, mockApiResponse } from "./fixtures/auth";

test.describe("Authentication", () => {
  test("shows login page when not authenticated", async ({ page }) => {
    await page.goto("/login");
    await expect(page.getByRole("heading", { name: /cashu mint admin/i })).toBeVisible();
    await expect(page.getByText(/sign in with your nostr key/i)).toBeVisible();
  });

  test("redirects to dashboard when a session is already open", async ({ page }) => {
    await loginAsAdmin(page, ["MINT_ADMIN"]);
    await mockApiResponse(page, "**/admin/dashboard/summary", {
      mintsByState: { ACTIVE: 2 },
      alertsBySeverity: {},
      activeControls: 0,
    });
    await mockApiResponse(page, "**/admin/audit/events**", {
      items: [],
      page: 0,
      size: 5,
      totalItems: 0,
      totalPages: 0,
    });

    await page.goto("/login");

    await expect(page).toHaveURL(/dashboard/);
  });

  test("shows expired session message when redirected with expired param", async ({
    page,
  }) => {
    await page.goto("/login?expired=true");
    await expect(page.getByText(/session.*expired/i)).toBeVisible();
  });

  test("shows access denied when user lacks required role", async ({
    page,
  }) => {
    await loginAsAdmin(page, ["MINT_ADMIN"]);
    await page.goto("/users");
    await expect(page.getByText(/access denied|not authorized/i)).toBeVisible();
  });
});
