import { describe, expect, it } from "vitest";
import { isNpub } from "../npub";

describe("isNpub", () => {
  // The identity an Operator is added by, in the form they were given it.
  it("accepts an npub", () => {
    expect(
      isNpub("npub10xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqpkge6d"),
    ).toBe(true);
  });

  // Surrounding whitespace comes free with a paste and is not a typo.
  it("accepts a pasted npub with whitespace", () => {
    expect(
      isNpub("  npub10xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqpkge6d "),
    ).toBe(true);
  });

  // The checksum is the whole point: a single wrong character is a different key.
  it("rejects an npub with a mistyped character", () => {
    expect(
      isNpub("npub10xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqpkge6q"),
    ).toBe(false);
  });

  // A private key pasted into an identity field must never reach the server.
  it("rejects an nsec", () => {
    expect(
      isNpub("nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsmhltgl"),
    ).toBe(false);
  });

  // Anything that is not bech32 at all fails before the prefix is even read.
  it("rejects gibberish", () => {
    expect(isNpub("alice")).toBe(false);
    expect(isNpub("")).toBe(false);
  });
});
