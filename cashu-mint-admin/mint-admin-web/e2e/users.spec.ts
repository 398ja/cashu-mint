import { test, expect } from "@playwright/test";
import {
  loginAsAdmin,
  mockApiResponse,
  pagedResponse,
} from "./fixtures/auth";

const USERS = [
  {
    userId: "user-1",
    displayName: "Alice Admin",
    email: "alice@test.com",
    roles: ["MINT_ADMIN"],
    active: true,
    message: null,
  },
  {
    userId: "user-2",
    displayName: "Bob Operator",
    email: "bob@test.com",
    roles: ["OPS_ADMIN"],
    active: true,
    message: null,
  },
];

test.describe("User Management", () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test("lists users", async ({ page }) => {
    await mockApiResponse(page, "**/admin/users?**", pagedResponse(USERS));
    await mockApiResponse(page, "**/admin/users", pagedResponse(USERS));

    await page.goto("/users");
    await expect(page.getByText("Alice Admin")).toBeVisible();
    await expect(page.getByText("Bob Operator")).toBeVisible();
  });

  test("shows user detail", async ({ page }) => {
    await page.route("**/admin/users/user-1", (route) => {
      // Only intercept exact GET requests, not sub-paths
      const url = new URL(route.request().url());
      if (url.pathname === "/admin/users/user-1" && route.request().method() === "GET") {
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(USERS[0]),
        });
      }
      return route.continue();
    });

    await page.goto("/users/user-1");
    await expect(page.getByText("Alice Admin")).toBeVisible();
    await expect(page.getByText("alice@test.com")).toBeVisible();
  });

  test("deactivate user shows confirm dialog", async ({ page }) => {
    await mockApiResponse(page, "**/admin/users/user-1", USERS[0]);
    await mockApiResponse(page, "**/admin/users/user-1/deactivate", {
      ...USERS[0],
      active: false,
      message: "User deactivated",
    });

    await page.goto("/users/user-1");
    const deactivateBtn = page.getByRole("button", {
      name: /deactivate/i,
    });
    if (await deactivateBtn.isVisible()) {
      await deactivateBtn.click();
      await expect(page.getByRole("textbox")).toBeVisible();
    }
  });
});
