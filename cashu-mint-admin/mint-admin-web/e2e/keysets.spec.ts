import { test, expect } from "@playwright/test";
import { loginAsAdmin, mockApiResponse, pagedResponse } from "./fixtures/auth";

const KEYSETS_URL = "**/admin/lifecycle/mints/mint-abc/keysets**";

test.describe("Keysets", () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  // The reason the page exists: after a Rotation the new keyset signs and the
  // old one still redeems, and the Operator can see both without a DB client.
  test("badges the signing keyset and says the archived one still redeems", async ({
    page,
  }) => {
    await mockApiResponse(
      page,
      KEYSETS_URL,
      pagedResponse([
        {
          keySetId: "00ffd73b2eaa1d3f",
          unit: "sat",
          state: "SIGNING",
          createdAt: "2026-08-28T10:00:00Z",
        },
        {
          keySetId: "009a1bb5c7de4028",
          unit: "sat",
          state: "ARCHIVED",
          createdAt: "2026-05-02T09:00:00Z",
        },
      ]),
    );

    await page.goto("/mints/mint-abc/keysets");

    await expect(page.getByText("Signing")).toBeVisible();
    await expect(page.getByText("Archived · still redeems")).toBeVisible();
    // Full ids, for pasting into a support conversation.
    await expect(page.getByText("00ffd73b2eaa1d3f")).toBeVisible();
    await expect(page.getByText("009a1bb5c7de4028")).toBeVisible();
  });

  // A vault that cannot be read must never look like a mint whose keysets are gone.
  test("shows an error with a retry rather than the empty state when the read fails", async ({
    page,
  }) => {
    await mockApiResponse(
      page,
      KEYSETS_URL,
      { code: "vault_unavailable", message: "Could not read keysets from the vault" },
      502,
    );

    await page.goto("/mints/mint-abc/keysets");

    await expect(page.getByRole("alert")).toContainText(/vault/i);
    await expect(page.getByRole("button", { name: /retry/i })).toBeVisible();
    await expect(page.getByText(/not been provisioned/i)).toHaveCount(0);
  });

  // What an Operator taking a backup came for: the denominations of a keyset and the
  // vault paths to back up -- for an archived keyset too, since past key material is
  // exactly what a recovery needs.
  test("reveals the denominations of an archived keyset as vault paths", async ({
    page,
  }) => {
    await mockApiResponse(
      page,
      KEYSETS_URL,
      pagedResponse([
        {
          keySetId: "009a1bb5c7de4028",
          unit: "sat",
          state: "ARCHIVED",
          createdAt: "2026-05-02T09:00:00Z",
        },
      ]),
    );
    // Registered last so it wins over the keysets pattern, which also matches this URL.
    await mockApiResponse(
      page,
      "**/keysets/009a1bb5c7de4028/denominations",
      [
        { amount: 1, vaultPath: "cashu/keys/mint-abc/009a1bb5c7de4028/1" },
        { amount: 2, vaultPath: "cashu/keys/mint-abc/009a1bb5c7de4028/2" },
      ],
    );

    await page.goto("/mints/mint-abc/keysets");

    // Closed until asked: the keys are not fetched to render a list nobody opened.
    await expect(page.getByText("cashu/keys/mint-abc/009a1bb5c7de4028/1")).toHaveCount(0);

    await page.getByRole("button", { name: /denominations/i }).click();

    await expect(page.getByText("cashu/keys/mint-abc/009a1bb5c7de4028/1")).toBeVisible();
    await expect(page.getByText("cashu/keys/mint-abc/009a1bb5c7de4028/2")).toBeVisible();
    // The page says where the key is, and that it is not the key.
    await expect(page.getByText(/stay in HashiCorp Vault/i)).toBeVisible();
  });

  // Same rule as the listing: an unreadable vault must never read as "no keys".
  test("shows an error rather than an empty denomination list when the read fails", async ({
    page,
  }) => {
    await mockApiResponse(
      page,
      KEYSETS_URL,
      pagedResponse([
        {
          keySetId: "00ffd73b2eaa1d3f",
          unit: "sat",
          state: "SIGNING",
          createdAt: "2026-08-28T10:00:00Z",
        },
      ]),
    );
    await mockApiResponse(
      page,
      "**/keysets/00ffd73b2eaa1d3f/denominations",
      { code: "vault_unavailable", message: "Could not read keysets from the vault" },
      502,
    );

    await page.goto("/mints/mint-abc/keysets");
    await page.getByRole("button", { name: /denominations/i }).click();

    await expect(page.getByRole("alert")).toContainText(/vault/i);
    await expect(page.getByText(/holds no keys/i)).toHaveCount(0);
  });

  test("says plainly when a mint has no keysets", async ({ page }) => {
    await mockApiResponse(page, KEYSETS_URL, pagedResponse([]));

    await page.goto("/mints/mint-abc/keysets");

    await expect(page.getByText(/not been provisioned/i)).toBeVisible();
  });
});
