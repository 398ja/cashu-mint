import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ReunlockError } from "@imani/nap-client-web";
import { AuthContext, type AuthContextValue } from "../AuthProvider";
import { LockScreen } from "../LockScreen";

function renderLocked(unlock: AuthContextValue["unlock"]) {
  const value: AuthContextValue = {
    roles: [],
    npub: null,
    authenticated: true,
    loading: false,
    locked: true,
    signIn: async () => {},
    unlock,
    refresh: async () => true,
    logout: () => {},
    hasRole: () => true,
  };
  render(
    <AuthContext.Provider value={value}>
      <LockScreen />
    </AuthContext.Provider>,
  );
}

describe("LockScreen", () => {
  // The passphrase restores signing; nothing here starts a fresh sign-in.
  it("hands the passphrase to the session", async () => {
    const unlock = vi.fn().mockResolvedValue(undefined);
    renderLocked(unlock);

    await userEvent.type(screen.getByLabelText(/passphrase/i), "correct horse");
    await userEvent.click(screen.getByRole("button", { name: /unlock/i }));

    expect(unlock).toHaveBeenCalledWith("correct horse");
  });

  // A mistyped passphrase is a retry, not a lost key.
  it("reports a wrong passphrase without alarming the operator", async () => {
    const unlock = vi
      .fn()
      .mockRejectedValue(new ReunlockError("INVALID_PASSPHRASE", "Incorrect passphrase"));
    renderLocked(unlock);

    await userEvent.type(screen.getByLabelText(/passphrase/i), "nope");
    await userEvent.click(screen.getByRole("button", { name: /unlock/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/unchanged/i);
  });
});
