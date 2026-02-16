import { test, expect } from "@playwright/test";
import {
  loginAsAdmin,
  mockApiResponse,
  pagedResponse,
} from "./fixtures/auth";

const MINT = {
  mintId: "mint-abc",
  lifecycleState: "ACTIVE",
  configurationRevisionId: 3,
  configurationParameters: { "nut04.enabled": "true" },
  versionTag: "v3",
  lastActor: "admin@test",
  lastAction: "RESUMED",
  lastModified: "2026-01-15T10:00:00Z",
};

test.describe("Mint Lifecycle", () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test("lists mints and navigates to detail", async ({ page }) => {
    await mockApiResponse(
      page,
      "**/admin/lifecycle/mints?**",
      pagedResponse([MINT]),
    );
    await mockApiResponse(
      page,
      "**/admin/lifecycle/mints?*",
      pagedResponse([MINT]),
    );
    // Also mock the unparameterized URL
    await page.route("**/admin/lifecycle/mints", (route) => {
      if (route.request().method() === "GET") {
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(pagedResponse([MINT])),
        });
      }
      return route.continue();
    });

    await page.goto("/mints");
    await expect(page.getByText("mint-abc")).toBeVisible();
  });

  test("shows mint detail with state-aware actions", async ({ page }) => {
    await mockApiResponse(
      page,
      "**/admin/lifecycle/mints/mint-abc",
      MINT,
    );

    await page.goto("/mints/mint-abc");
    await expect(page.getByText("mint-abc").first()).toBeVisible();
    // Active mint shows the ACTIVE badge and Pause action
    await expect(
      page.getByRole("button", { name: /pause/i }),
    ).toBeVisible();
  });

  test("pause action opens confirm dialog with reason", async ({ page }) => {
    await mockApiResponse(
      page,
      "**/admin/lifecycle/mints/mint-abc",
      MINT,
    );
    await mockApiResponse(
      page,
      "**/admin/lifecycle/mints/mint-abc/pause",
      {
        operation: "PAUSE",
        mintId: "mint-abc",
        previousState: "ACTIVE",
        currentState: "SUSPENDED",
        versionTag: "v4",
        changed: true,
        message: "Mint paused",
      },
    );

    await page.goto("/mints/mint-abc");
    await page.getByRole("button", { name: "Pause" }).click();

    // Confirm dialog should appear with reason input
    await expect(page.getByLabel(/reason/i)).toBeVisible();
    await page.getByLabel(/reason/i).fill("Scheduled maintenance");
    // Dialog confirm button uses the action label
    const dialog = page.locator("[role=dialog]");
    await dialog.getByRole("button", { name: "Pause" }).click();
  });

  test("resume action available for suspended mint", async ({ page }) => {
    const suspended = { ...MINT, lifecycleState: "SUSPENDED" };
    await mockApiResponse(
      page,
      "**/admin/lifecycle/mints/mint-abc",
      suspended,
    );

    await page.goto("/mints/mint-abc");
    await expect(
      page.getByRole("button", { name: /resume/i }),
    ).toBeVisible();
  });

  test("retire action opens destructive confirm dialog", async ({ page }) => {
    await mockApiResponse(
      page,
      "**/admin/lifecycle/mints/mint-abc",
      MINT,
    );

    await page.goto("/mints/mint-abc");
    await page.getByRole("button", { name: /retire/i }).click();

    await expect(page.getByRole("textbox")).toBeVisible();
  });
});
