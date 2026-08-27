import { test, expect } from "@playwright/test";
import {
  loginAsAdmin,
  mockApiResponse,
  pagedResponse,
  TEST_NPUB,
} from "./fixtures/auth";

const SUPER_ADMIN = {
  userId: "00000000-0000-0000-0000-00000000000a",
  npub: TEST_NPUB,
  displayName: "Super Administrator",
  email: null,
  roles: ["SUPER_ADMIN"],
  active: true,
  message: null,
  configurationAnchored: true,
};

const USERS = [
  SUPER_ADMIN,
  {
    userId: "user-1",
    npub: "npub1zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyfqrfha0z",
    displayName: "Alice Admin",
    email: "alice@test.com",
    roles: ["MINT_ADMIN"],
    active: true,
    message: null,
    configurationAnchored: false,
  },
  {
    userId: "user-2",
    npub: "npub1zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyfqrfha0z",
    displayName: "Bob Operator",
    email: "bob@test.com",
    roles: ["OPS_ADMIN"],
    active: false,
    message: null,
    configurationAnchored: false,
  },
];

/** The listing, under both the bare and the query-string form the page uses. */
async function mockListing(page: import("@playwright/test").Page, users = USERS) {
  await mockApiResponse(page, "**/admin/users?**", pagedResponse(users));
  await mockApiResponse(page, "**/admin/users", pagedResponse(users));
}

/**
 * A listing that answers with the Operator's new state once `changed()` is true,
 * so the refetch after a lifecycle call sees what the server would have stored.
 */
async function mockLifecycleListing(
  page: import("@playwright/test").Page,
  userId: string,
  active: boolean,
  changed: () => boolean,
) {
  const respond = (route: import("@playwright/test").Route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(
        pagedResponse(
          changed()
            ? USERS.map((u) => (u.userId === userId ? { ...u, active } : u))
            : USERS,
        ),
      ),
    });
  await page.route("**/admin/users?**", respond);
  await page.route("**/admin/users", respond);
}

test.describe("Operator Management", () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page, ["SUPER_ADMIN"]);
  });

  // An account id identifies nobody: the listing has to name Operators by
  // something a person can recognise, and say whether they still have access.
  test("lists each Operator by name, identity and status", async ({ page }) => {
    await mockListing(page);

    await page.goto("/users");
    await expect(page.getByText("Alice Admin")).toBeVisible();
    await expect(page.getByText("Bob Operator")).toBeVisible();
    // The signed-in Operator's own npub also shows in the nav, so this asserts
    // on the listing rather than on the page.
    await expect(page.locator("tr", { hasText: "Super Administrator" }))
      .toContainText(TEST_NPUB);
    await expect(page.locator("tr", { hasText: "Bob Operator" })).toContainText(
      "Suspended",
    );
  });

  // The API refuses to suspend the Super Administrator, so offering the control
  // would only ever produce a 403 the Operator could not have predicted.
  test("marks the Super Administrator anchored, with no lifecycle control", async ({
    page,
  }) => {
    await mockListing(page);

    await page.goto("/users");
    const anchored = page.locator("tr", { hasText: "Super Administrator" });
    await expect(anchored.getByText("Configuration-anchored")).toBeVisible();
    await expect(
      anchored.getByRole("button", { name: /suspend|remove|reinstate/i }),
    ).toHaveCount(0);
  });

  // A mistyped key should be answered in the form, not as a 400 from the create call.
  test("refuses a mistyped npub in the browser", async ({ page }) => {
    await mockListing(page);

    await page.goto("/users");
    await page.getByLabel("Display name").fill("Carol Operator");
    await page.getByLabel("npub").fill("npub1notarealkey");
    await page.getByRole("button", { name: "Add Operator" }).click();

    await expect(page.getByRole("alert")).toContainText("not a valid npub");
  });

  // Enrolment is the whole point of the page: name plus npub, and they appear.
  test("adds an Operator and shows them in the list", async ({ page }) => {
    const added = {
      ...USERS[1],
      userId: "user-3",
      displayName: "Carol Operator",
    };
    let created = false;
    await page.route("**/admin/users", async (route) => {
      if (route.request().method() === "POST") {
        created = true;
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(added),
        });
      }
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(pagedResponse(created ? [...USERS, added] : USERS)),
      });
    });
    await page.route("**/admin/users?**", (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(pagedResponse(created ? [...USERS, added] : USERS)),
      }),
    );

    await page.goto("/users");
    await page.getByLabel("Display name").fill("Carol Operator");
    await page.getByLabel("npub").fill(added.npub);
    await page.getByRole("button", { name: "Add Operator" }).click();

    await expect(page.getByText("Carol Operator")).toBeVisible();
  });

  // Suspension is not undoable by accident, so it asks first -- and the row it
  // changed has to show the change without a reload.
  test("suspends an Operator after confirmation, updating status in place", async ({
    page,
  }) => {
    let suspended = false;
    await page.route("**/admin/users/user-1/deactivate", (route) => {
      suspended = true;
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ ...USERS[1], active: false }),
      });
    });
    await mockLifecycleListing(page, "user-1", false, () => suspended);

    await page.goto("/users");
    const alice = page.locator("tr", { hasText: "Alice Admin" });
    await alice.getByRole("button", { name: "Suspend" }).click();

    await expect(page.getByText("Suspend Operator")).toBeVisible();
    await page.getByLabel(/Reason/).fill("Left the team");
    await page.getByRole("button", { name: "Suspend", exact: true }).click();

    await expect(alice.getByText("Suspended")).toBeVisible();
  });

  // Suspension keeps the row rather than deleting it, so reinstatement is a
  // control on the same listing rather than a second enrolment.
  test("reinstates a suspended Operator from the same page", async ({
    page,
  }) => {
    let reinstated = false;
    await page.route("**/admin/users/user-2/reinstate", (route) => {
      reinstated = true;
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ ...USERS[2], active: true }),
      });
    });
    await mockLifecycleListing(page, "user-2", true, () => reinstated);

    await page.goto("/users");
    const bob = page.locator("tr", { hasText: "Bob Operator" });
    await bob.getByRole("button", { name: "Reinstate" }).click();

    await expect(page.getByText("Reinstate Operator")).toBeVisible();
    await page.getByLabel(/Reason/).fill("Back from leave");
    await page.getByRole("button", { name: "Reinstate", exact: true }).click();

    await expect(bob.getByText("Active")).toBeVisible();
  });

  // Operators bring their own Nostr key: there is no secret for the admin to
  // show, reset, or offer once. A control implying otherwise would be a lie.
  test("shows no credential affordance anywhere on the page", async ({
    page,
  }) => {
    await mockListing(page);

    await page.goto("/users");
    await expect(
      page.getByText(/password|credential|reset|copy once/i),
    ).toHaveCount(0);
  });

  test("shows user detail", async ({ page }) => {
    await page.route("**/admin/users/user-1", (route) => {
      const url = new URL(route.request().url());
      if (
        url.pathname === "/admin/users/user-1" &&
        route.request().method() === "GET"
      ) {
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(USERS[1]),
        });
      }
      return route.continue();
    });

    await page.goto("/users/user-1");
    await expect(page.getByText("Alice Admin")).toBeVisible();
    await expect(page.getByText("alice@test.com")).toBeVisible();
  });
});

test.describe("Operator Management, as an Administrator", () => {
  // A MINT_ADMIN holds mint:lifecycle, not users:manage: the page is not theirs
  // to see, and typing its path is not a way around that.
  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page, ["MINT_ADMIN"]);
  });

  // Hiding the entry is presentation; refusing the route is the actual gate.
  test("hides the navigation entry and refuses the page directly", async ({
    page,
  }) => {
    await page.goto("/");
    await expect(
      page.getByRole("navigation").getByRole("link", { name: /users|operators/i }),
    ).toHaveCount(0);

    await page.goto("/users");
    await expect(page.getByText(/Access Denied/i)).toBeVisible();
  });
});
