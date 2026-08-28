import { test, expect } from "@playwright/test";
import { loginAsAdmin, mockApiResponse } from "./fixtures/auth";

test.describe("Dashboard", () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
    await mockApiResponse(page, "**/admin/dashboard/summary", {
      mintsByState: { ACTIVE: 3, SUSPENDED: 1, PROVISIONED: 2 },
      activeControls: 2,
    });
    await mockApiResponse(page, "**/admin/audit/events**", {
      items: [
        {
          mintId: "mint-1",
          sequence: 1,
          actor: "admin@test",
          action: "MINT_CREATED",
          timestamp: "2026-01-15T10:00:00Z",
          configurationRevisionId: null,
        },
        {
          mintId: "mint-2",
          sequence: 2,
          actor: "admin@test",
          action: "MINT_PAUSED",
          timestamp: "2026-01-15T11:00:00Z",
          configurationRevisionId: null,
        },
      ],
      page: 0,
      size: 5,
      totalItems: 2,
      totalPages: 1,
    });
  });

  test("renders summary cards with correct counts", async ({ page }) => {
    await page.goto("/dashboard");

    // The two summary cards the mint can actually answer for. Alerting was
    // deleted in #364 -- nothing produces a severity, so nothing shows one.
    await expect(page.getByText("Mints by State")).toBeVisible();
    await expect(page.getByRole("link", { name: /Mints by State/ }))
      .toContainText(/ACTIVE3/);
    await expect(page.getByText("Active Controls").locator(".."))
      .toContainText("2");
  });

  test("renders audit timeline entries", async ({ page }) => {
    await page.goto("/dashboard");

    await expect(page.getByText("MINT_CREATED")).toBeVisible();
    await expect(page.getByText("MINT_PAUSED")).toBeVisible();
  });
});
