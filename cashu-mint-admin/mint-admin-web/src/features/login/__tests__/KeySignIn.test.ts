import { describe, expect, it } from "vitest";
import { AuthRequestError, ReunlockError } from "@imani/nap-client-web";
import { describeKeySignInError } from "../KeySignIn";
import { describeUnlockError } from "@/auth/LockScreen";
import { InvalidKeyError } from "@/auth/keySigner";

describe("describeKeySignInError", () => {
  // A mistyped passphrase must not read as "your key is gone".
  it("says the stored key survives a wrong passphrase", () => {
    expect(describeKeySignInError(new Error("Invalid passphrase")))
      .toMatch(/unchanged/i);
  });

  // The key box and the passphrase box fail for different reasons.
  it("passes on what was wrong with the key", () => {
    expect(describeKeySignInError(new InvalidKeyError("That is not a private key.")))
      .toMatch(/not a private key/i);
  });

  // Past the store, this is an ordinary handshake failure.
  it("falls back to the handshake wording", () => {
    expect(describeKeySignInError(new AuthRequestError("complete", 401)))
      .toMatch(/operator profile/i);
  });
});

describe("describeUnlockError", () => {
  // Re-entering the passphrase is the recovery, so say which one failed.
  it("distinguishes a wrong passphrase from a missing key", () => {
    expect(describeUnlockError(new ReunlockError("INVALID_PASSPHRASE", "x")))
      .toMatch(/unchanged/i);
    expect(describeUnlockError(new ReunlockError("NO_STORED_KEY", "x")))
      .toMatch(/sign in again/i);
  });
});
