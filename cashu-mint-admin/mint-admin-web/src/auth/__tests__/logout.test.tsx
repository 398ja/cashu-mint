import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { AuthProvider } from "../AuthProvider";
import { useAuth } from "../useAuth";

const { clear } = vi.hoisted(() => ({ clear: vi.fn(() => Promise.resolve()) }));
vi.mock("@/auth/keySigner", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/auth/keySigner")>()),
  adminKeyStore: { clear, hasKey: () => Promise.resolve(true) },
}));
vi.mock("@/api/auth", () => ({
  fetchAuthMe: () =>
    Promise.resolve({ authenticated: true, roles: ["OPS_ADMIN"], permissions: [], npub: "npub1x" }),
}));

function SignOut() {
  const { logout, authenticated } = useAuth();
  return (
    <button onClick={logout}>{authenticated ? "Sign out" : "Signed out"}</button>
  );
}

describe("sign-out", () => {
  // A key left behind offers the next person a passphrase box for someone
  // else's key, with no way to sign in with their own.
  it("wipes the enrolled key from this browser", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 204 })));
    render(
      <AuthProvider>
        <SignOut />
      </AuthProvider>,
    );

    await userEvent.click(await screen.findByRole("button", { name: "Sign out" }));

    await waitFor(() => expect(clear).toHaveBeenCalled());
    expect(await screen.findByRole("button", { name: "Signed out" })).toBeVisible();
  });
});
