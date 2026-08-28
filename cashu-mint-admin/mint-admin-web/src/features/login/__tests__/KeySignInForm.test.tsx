import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { AuthContext, type AuthContextValue } from "@/auth/AuthProvider";
import { KeySignIn } from "../KeySignIn";

const save = vi.fn();
vi.mock("@/auth/keySigner", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/auth/keySigner")>()),
  adminKeyStore: {
    hasKey: () => Promise.resolve(false),
    save: (...args: unknown[]) => {
      save(...args);
      return Promise.resolve();
    },
    loadKey: () => Promise.resolve("a".repeat(64)),
  },
}));

function renderEnrolment() {
  const value = {
    roles: [], permissions: [], npub: null, authenticated: false, loading: false,
    locked: false, signIn: vi.fn(), unlock: vi.fn(), refresh: async () => false,
    logout: () => {}, hasRole: () => false, hasPermission: () => false,
  } as unknown as AuthContextValue;
  render(
    <AuthContext.Provider value={value}>
      <KeySignIn onSignedIn={() => {}} />
    </AuthContext.Provider>,
  );
}

describe("KeySignIn enrolment", () => {
  // A key encrypted under a mistyped passphrase is a key nobody can open again,
  // so the mismatch has to stop the save rather than be discovered later.
  it("refuses to store the key when the two passphrases disagree", async () => {
    renderEnrolment();
    await screen.findByLabelText(/confirm passphrase/i);

    await userEvent.type(screen.getByLabelText(/^private key$/i), "b".repeat(64));
    await userEvent.type(screen.getByLabelText(/^passphrase$/i), "correct horse");
    await userEvent.type(screen.getByLabelText(/confirm passphrase/i), "correct hores");
    await userEvent.click(screen.getByRole("button", { name: /encrypt key/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/do not match/i);
    expect(save).not.toHaveBeenCalled();
  });
});
