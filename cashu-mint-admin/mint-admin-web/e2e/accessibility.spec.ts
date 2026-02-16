import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import {
  loginAsAdmin,
  mockApiResponse,
  pagedResponse,
} from "./fixtures/auth";

test.describe("Accessibility @a11y", () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);

    await mockApiResponse(page, "**/admin/dashboard/summary", {
      mintsByState: { ACTIVE: 2, SUSPENDED: 1 },
      alertsBySeverity: { WARNING: 3 },
      activeControls: 1,
    });
    await mockApiResponse(page, "**/admin/audit/events**", {
      items: [],
      page: 0,
      size: 5,
      totalItems: 0,
      totalPages: 0,
    });
    await mockApiResponse(
      page,
      "**/admin/lifecycle/mints?**",
      pagedResponse([
        {
          mintId: "mint-1",
          lifecycleState: "ACTIVE",
          configurationRevisionId: 1,
          configurationParameters: {},
          versionTag: "v1",
          lastActor: "admin",
          lastAction: "CREATED",
          lastModified: "2026-01-01T00:00:00Z",
        },
      ]),
    );
    await mockApiResponse(
      page,
      "**/admin/lifecycle/mints",
      pagedResponse([
        {
          mintId: "mint-1",
          lifecycleState: "ACTIVE",
          configurationRevisionId: 1,
          configurationParameters: {},
          versionTag: "v1",
          lastActor: "admin",
          lastAction: "CREATED",
          lastModified: "2026-01-01T00:00:00Z",
        },
      ]),
    );
    await mockApiResponse(
      page,
      "**/admin/users**",
      pagedResponse([
        {
          userId: "user-1",
          displayName: "Test User",
          email: "test@test.com",
          roles: ["MINT_ADMIN"],
          active: true,
          message: null,
        },
      ]),
    );
    await mockApiResponse(
      page,
      "**/admin/alerts**",
      pagedResponse([
        {
          alertId: "alert-1",
          mintId: "mint-1",
          severity: "WARNING",
          summary: "Test alert",
          acknowledged: false,
          silenced: false,
          silenceMinutes: null,
          escalations: [],
          message: null,
        },
      ]),
    );
  });

  test("dashboard page has no critical accessibility violations", async ({
    page,
  }) => {
    await page.goto("/dashboard");
    await page.waitForLoadState("networkidle");

    const results = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa"])
      .analyze();

    expect(results.violations.filter((v) => v.impact === "critical")).toEqual(
      [],
    );
  });

  test("mints page has no critical accessibility violations", async ({
    page,
  }) => {
    await page.goto("/mints");
    await page.waitForLoadState("networkidle");

    const results = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa"])
      .analyze();

    expect(results.violations.filter((v) => v.impact === "critical")).toEqual(
      [],
    );
  });

  test("users page has no critical accessibility violations", async ({
    page,
  }) => {
    await page.goto("/users");
    await page.waitForLoadState("networkidle");

    const results = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa"])
      .analyze();

    expect(results.violations.filter((v) => v.impact === "critical")).toEqual(
      [],
    );
  });

  test("alerts page has no critical accessibility violations", async ({
    page,
  }) => {
    await page.goto("/alerts");
    await page.waitForLoadState("networkidle");

    const results = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa"])
      .analyze();

    expect(results.violations.filter((v) => v.impact === "critical")).toEqual(
      [],
    );
  });

  test("audit page has no critical accessibility violations", async ({
    page,
  }) => {
    await page.goto("/audit");
    await page.waitForLoadState("networkidle");

    const results = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa"])
      .analyze();

    expect(results.violations.filter((v) => v.impact === "critical")).toEqual(
      [],
    );
  });
});
