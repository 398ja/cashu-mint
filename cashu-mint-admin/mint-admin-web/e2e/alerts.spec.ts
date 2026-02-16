import { test, expect } from "@playwright/test";
import {
  loginAsAdmin,
  mockApiResponse,
  pagedResponse,
} from "./fixtures/auth";

const ALERTS = [
  {
    alertId: "alert-1",
    mintId: "mint-abc",
    severity: "CRITICAL",
    summary: "Mint unreachable",
    acknowledged: false,
    silenced: false,
    silenceMinutes: null,
    escalations: [],
    message: null,
  },
  {
    alertId: "alert-2",
    mintId: "mint-xyz",
    severity: "WARNING",
    summary: "High latency detected",
    acknowledged: true,
    silenced: false,
    silenceMinutes: null,
    escalations: [],
    message: null,
  },
];

test.describe("Alert Management", () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test("lists alerts with severity and mint info", async ({ page }) => {
    await mockApiResponse(page, "**/admin/alerts?**", pagedResponse(ALERTS));
    await mockApiResponse(page, "**/admin/alerts", pagedResponse(ALERTS));

    await page.goto("/alerts");
    await expect(page.getByText("Mint unreachable")).toBeVisible();
    await expect(page.getByText("High latency detected")).toBeVisible();
  });

  test("shows alert detail with action buttons", async ({ page }) => {
    await mockApiResponse(page, "**/admin/alerts/alert-1", ALERTS[0]);

    await page.goto("/alerts/alert-1");
    await expect(page.getByText("Mint unreachable")).toBeVisible();
    await expect(page.getByText("CRITICAL").first()).toBeVisible();
  });

  test("acknowledge alert action", async ({ page }) => {
    await mockApiResponse(page, "**/admin/alerts/alert-1", ALERTS[0]);
    await mockApiResponse(
      page,
      "**/admin/alerts/alert-1/acknowledge",
      { ...ALERTS[0], acknowledged: true, message: "Acknowledged" },
    );

    await page.goto("/alerts/alert-1");
    const ackBtn = page.getByRole("button", { name: /acknowledge/i });
    if (await ackBtn.isVisible()) {
      await ackBtn.click();
    }
  });

  test("silence alert action", async ({ page }) => {
    await mockApiResponse(page, "**/admin/alerts/alert-1", ALERTS[0]);
    await mockApiResponse(
      page,
      "**/admin/alerts/alert-1/silence",
      { ...ALERTS[0], silenced: true, silenceMinutes: 60, message: "Silenced" },
    );

    await page.goto("/alerts/alert-1");
    const silenceBtn = page.getByRole("button", { name: /silence/i });
    if (await silenceBtn.isVisible()) {
      await silenceBtn.click();
    }
  });

  test("escalate alert action", async ({ page }) => {
    await mockApiResponse(page, "**/admin/alerts/alert-1", ALERTS[0]);
    await mockApiResponse(
      page,
      "**/admin/alerts/alert-1/escalate",
      {
        ...ALERTS[0],
        escalations: ["policy-1"],
        message: "Escalated",
      },
    );

    await page.goto("/alerts/alert-1");
    const escalateBtn = page.getByRole("button", { name: /escalate/i });
    if (await escalateBtn.isVisible()) {
      await escalateBtn.click();
    }
  });
});
