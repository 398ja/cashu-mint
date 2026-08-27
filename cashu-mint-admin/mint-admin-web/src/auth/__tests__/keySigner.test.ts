import { describe, expect, it } from "vitest";
import { IDLE_LOCK_MS, InvalidKeyError, toPrivateKeyHex } from "../keySigner";

// The key of secret 1 -- its npub is the one the e2e fixtures sign in as.
const NSEC = "nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsmhltgl";
const HEX = "0000000000000000000000000000000000000000000000000000000000000001";

describe("toPrivateKeyHex", () => {
  // What an Operator holds is an nsec; the signer wants hex.
  it("accepts an nsec and raw hex alike", () => {
    expect(toPrivateKeyHex(NSEC)).toBe(HEX);
    expect(toPrivateKeyHex(`  ${HEX.toUpperCase()}  `)).toBe(HEX);
  });

  // A public key pasted into the private key box must not be enrolled as one.
  it("refuses anything that is not a private key", () => {
    expect(() => toPrivateKeyHex("npub1qqqqq")).toThrow(InvalidKeyError);
    expect(() => toPrivateKeyHex("hunter2")).toThrow(InvalidKeyError);
    expect(() => toPrivateKeyHex("")).toThrow(InvalidKeyError);
  });
});

// One prompt, not two: the key must not outlive the session it signs for, nor
// die before it. 900s is nap.session-idle-ttl-seconds in nap-defaults.properties.
it("evicts the key exactly when the server drops the session", () => {
  expect(IDLE_LOCK_MS).toBe(900 * 1000);
});
