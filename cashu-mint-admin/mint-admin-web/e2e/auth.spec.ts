import { test, expect } from "@playwright/test";
import {
  TEST_NPUB,
  TEST_NSEC,
  installSigner,
  loginAsAdmin,
  mockApiResponse,
  mockAuthRoutes,
} from "./fixtures/auth";

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

  test("signs in with a browser extension and lands on the dashboard", async ({
    page,
  }) => {
    await loginAsAdmin(page, ["MINT_ADMIN"], { resume: false });
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
    await page.getByRole("button", { name: /sign in with extension/i }).click();

    await expect(page).toHaveURL(/dashboard/);
    // Signed in as the Operator's own identity, and it says so.
    await expect(page.getByTitle(TEST_NPUB)).toBeVisible();
  });

  test("tells the operator to install a signer when none is available", async ({
    page,
  }) => {
    await page.goto("/login");
    await expect(page.getByText(/no signing extension found/i)).toBeVisible();
  });

  test("refuses an npub the mint does not accept", async ({ page }) => {
    await installSigner(page);
    await page.route("**/api/v1/auth/session", (route) =>
      route.fulfill({ status: 401, contentType: "application/json", body: "{}" }),
    );
    await page.route("**/api/v1/auth/init", (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          challenge_id: "challenge-id",
          challenge: "challenge",
          auth_url: "http://localhost:3173/api/v1/auth/complete",
          auth_method: "POST",
          issued_at: 0,
          expires_at: 0,
        }),
      }),
    );
    await page.route("**/api/v1/auth/complete", (route) =>
      route.fulfill({ status: 401, contentType: "application/json", body: "{}" }),
    );

    await page.goto("/login");
    await page.getByRole("button", { name: /sign in with extension/i }).click();

    await expect(page.getByRole("alert")).toContainText(/operator profile/i);
    await expect(page).toHaveURL(/login/);
  });

  test("signs out back to the login page", async ({ page }) => {
    await loginAsAdmin(page, ["MINT_ADMIN"]);
    await mockApiResponse(page, "**/admin/dashboard/summary", {
      mintsByState: {},
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
    await page.route("**/api/v1/auth/logout", (route) =>
      route.fulfill({ status: 204, body: "" }),
    );

    await page.goto("/dashboard");
    await page.getByRole("button", { name: /sign out/i }).click();

    await expect(page).toHaveURL(/login/);
  });

  test("shows access denied when user lacks required role", async ({
    page,
  }) => {
    await loginAsAdmin(page, ["MINT_ADMIN"]);
    await page.goto("/users");
    await expect(page.getByText(/access denied|not authorized/i)).toBeVisible();
  });

  test("enrols an encrypted key and signs in with it again after a reload", async ({
    page,
  }) => {
    // No extension in this browser: the key in the page is the only signer.
    await mockAuthRoutes(page, ["MINT_ADMIN"], { resume: false });
    await mockApiResponse(page, "**/admin/dashboard/summary", {
      mintsByState: {},
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
    await page.getByLabel(/private key/i).fill(TEST_NSEC);
    await page.getByLabel(/passphrase/i).fill("correct horse battery");
    await page.getByRole("button", { name: /encrypt key and sign in/i }).click();

    await expect(page).toHaveURL(/dashboard/);
    // The identity the mint reports is the enrolled key's own npub.
    await expect(page.getByTitle(TEST_NPUB)).toBeVisible();

    // A reload drops the in-memory session; the encrypted key stays behind, so
    // the Operator is asked for a passphrase rather than for a key again.
    await page.reload();
    await expect(page).toHaveURL(/login/);
    await expect(page.getByLabel(/private key/i)).toHaveCount(0);

    await page.getByLabel(/passphrase/i).fill("wrong passphrase");
    await page.getByRole("button", { name: /sign in with stored key/i }).click();
    await expect(page.getByRole("alert")).toContainText(/unchanged/i);
    await expect(page).toHaveURL(/login/);

    // The failed attempt left the record readable, so the right passphrase works.
    await page.getByLabel(/passphrase/i).fill("correct horse battery");
    await page.getByRole("button", { name: /sign in with stored key/i }).click();
    await expect(page).toHaveURL(/dashboard/);
    await expect(page.getByTitle(TEST_NPUB)).toBeVisible();
  });
});
