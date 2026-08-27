import { describe, expect, it } from "vitest";
import { AuthRequestError, Nip07Error } from "@imani/nap-client-web";
import { describeSignInError } from "../LoginPage";

describe("describeSignInError", () => {
  // Four ways an extension fails, four things for the Operator to do about it.
  it("tells the operator what their extension did", () => {
    expect(describeSignInError(new Nip07Error("NOT_AVAILABLE", "x"))).toMatch(/install one/i);
    expect(describeSignInError(new Nip07Error("DECLINED", "x"))).toMatch(/declined/i);
    expect(describeSignInError(new Nip07Error("TIMEOUT", "x"))).toMatch(/in time/i);
    expect(describeSignInError(new Nip07Error("PROVIDER_ERROR", "x"))).toMatch(/could not complete/i);
  });

  // A refused key is the one failure an Operator can act on: ask to be provisioned.
  it("names the provisioning route when the mint refuses the key", () => {
    expect(describeSignInError(new AuthRequestError("complete", 401)))
      .toMatch(/operator profile/i);
  });

  // Rate limiting says "later", not "no", so it must not read as a refusal.
  it("treats a retryable failure as retryable", () => {
    expect(describeSignInError(new AuthRequestError("init", 429))).toMatch(/try again/i);
    expect(describeSignInError(new TypeError("network"))).toMatch(/try again/i);
  });
});
