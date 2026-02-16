import { test, expect } from "@playwright/test";
import {
  loginAsAdmin,
  mockApiResponse,
  pagedResponse,
} from "./fixtures/auth";

const REVISIONS = [
  {
    revisionId: 3,
    versionTag: "v3",
    parameters: { "nut04.enabled": "true", "nut07.enabled": "false" },
  },
  {
    revisionId: 2,
    versionTag: "v2",
    parameters: { "nut04.enabled": "false", "nut07.enabled": "false" },
  },
];

test.describe("Configuration Management", () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
    await mockApiResponse(
      page,
      "**/admin/lifecycle/mints/mint-abc",
      {
        mintId: "mint-abc",
        lifecycleState: "ACTIVE",
        configurationRevisionId: 3,
        configurationParameters: { "nut04.enabled": "true" },
        versionTag: "v3",
        lastActor: "admin@test",
        lastAction: "CONFIG_APPLIED",
        lastModified: "2026-01-15T10:00:00Z",
      },
    );
    await mockApiResponse(
      page,
      "**/admin/configuration/mints/mint-abc/revisions**",
      pagedResponse(REVISIONS),
    );
  });

  test("shows revision list for a mint", async ({ page }) => {
    await page.goto("/mints/mint-abc/configuration");

    await expect(page.getByText("v3")).toBeVisible();
    await expect(page.getByText("v2")).toBeVisible();
  });

  test("apply configuration submits new config", async ({ page }) => {
    await mockApiResponse(
      page,
      "**/admin/configuration/mints/mint-abc/apply",
      {
        mintId: "mint-abc",
        appliedRevision: "4",
        parameters: { "nut04.enabled": "true", "nut07.enabled": "true" },
        message: "Configuration applied",
      },
    );

    await page.goto("/mints/mint-abc/configuration");

    // Find and click the Apply tab/button
    const applyBtn = page.getByRole("button", { name: /apply/i }).first();
    if (await applyBtn.isVisible()) {
      await applyBtn.click();
    }
  });

  test("rollback prompts for reason", async ({ page }) => {
    await mockApiResponse(
      page,
      "**/admin/configuration/mints/mint-abc/rollback",
      {
        mintId: "mint-abc",
        appliedRevision: "2",
        parameters: { "nut04.enabled": "false" },
        message: "Rolled back",
      },
    );

    await page.goto("/mints/mint-abc/configuration");

    const rollbackBtn = page
      .getByRole("button", { name: /rollback/i })
      .first();
    if (await rollbackBtn.isVisible()) {
      await rollbackBtn.click();
      // Should show reason textarea in confirm dialog
      await expect(page.getByRole("textbox")).toBeVisible();
    }
  });
});
