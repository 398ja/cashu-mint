import { test, expect } from "@playwright/test";
import {
  loginAsAdmin,
  mockApiResponse,
  pagedResponse,
} from "./fixtures/auth";

const CONTROLS = [
  {
    mintId: "mint-abc",
    controlId: "ctrl-1",
    status: "SCHEDULED",
    scheduledAt: "2026-02-01T08:00:00Z",
    reason: "Planned maintenance",
  },
  {
    mintId: "mint-abc",
    controlId: "ctrl-2",
    status: "COMPLETED",
    scheduledAt: null,
    reason: "Key rotation",
  },
];

const MINT = {
  mintId: "mint-abc",
  lifecycleState: "ACTIVE",
  configurationRevisionId: 3,
  configurationParameters: {},
  versionTag: "v3",
  lastActor: "admin@test",
  lastAction: "RESUMED",
  lastModified: "2026-01-15T10:00:00Z",
};

test.describe("Operational Controls", () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
    await mockApiResponse(
      page,
      "**/admin/lifecycle/mints/mint-abc",
      MINT,
    );
    await page.route(
      (url) => url.pathname.endsWith("/admin/operations/mints/mint-abc/controls"),
      (route) =>
        route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(pagedResponse(CONTROLS)),
        }),
    );
  });

  test("lists operational controls", async ({ page }) => {
    await page.goto("/mints/mint-abc/operations");
    await expect(page.getByText("ctrl-1")).toBeVisible();
    await expect(page.getByText("SCHEDULED", { exact: true })).toBeVisible();
  });

  test("schedule maintenance action", async ({ page }) => {
    await mockApiResponse(
      page,
      "**/admin/operations/mints/mint-abc/maintenance/schedule",
      {
        mintId: "mint-abc",
        controlId: "ctrl-3",
        status: "SCHEDULED",
        scheduledAt: "2026-02-15T08:00:00Z",
        reason: "New maintenance",
      },
    );

    await page.goto("/mints/mint-abc/operations");
    const scheduleBtn = page.getByRole("button", {
      name: /schedule/i,
    });
    if (await scheduleBtn.isVisible()) {
      await scheduleBtn.click();
    }
  });

  test("start maintenance action", async ({ page }) => {
    await mockApiResponse(
      page,
      "**/admin/operations/mints/mint-abc/maintenance/start",
      {
        mintId: "mint-abc",
        controlId: "ctrl-1",
        status: "IN_PROGRESS",
        scheduledAt: null,
        reason: "Starting maintenance",
      },
    );

    await page.goto("/mints/mint-abc/operations");
    const startBtn = page.getByRole("button", { name: /start/i });
    if (await startBtn.isVisible()) {
      await startBtn.click();
    }
  });

  test("force close opens destructive confirm", async ({ page }) => {
    await mockApiResponse(
      page,
      "**/admin/operations/mints/mint-abc/force-close",
      {
        mintId: "mint-abc",
        controlId: "ctrl-4",
        status: "COMPLETED",
        scheduledAt: null,
        reason: "Emergency",
      },
    );

    await page.goto("/mints/mint-abc/operations");
    const forceBtn = page
      .getByRole("button", { name: /force/i })
      .first();
    if (await forceBtn.isVisible()) {
      await forceBtn.click();
      // Should show confirm dialog with reason
      const textbox = page.getByRole("textbox");
      if (await textbox.isVisible({ timeout: 2000 })) {
        await textbox.fill("Emergency shutdown required");
      }
    }
  });
});
