import { describe, expect, it } from "vitest";

// The five vendored NAP packages must resolve as npm workspaces, and their
// inter-package `@imani/*` imports must resolve to the vendored siblings —
// nap-react pulls the whole chain down to nap-core. Nothing else consumes
// them yet, so this is the only thing that would notice a broken vendor tree.
describe("vendored NAP packages", () => {
  it("resolves every @imani/* package and its inter-package imports", async () => {
    const core = await import("@imani/nap-core");
    const http = await import("@imani/nap-client-http");
    const web = await import("@imani/nap-client-web");
    const nip46 = await import("@imani/nap-client-nip46");
    const react = await import("@imani/nap-react");

    expect(core.sha256Hex).toBeTypeOf("function");
    expect(http.buildAuthCompleteRequest).toBeTypeOf("function");
    expect(web.createNapSession).toBeTypeOf("function");
    // Re-exported from @imani/nap-client-web, so it proves the sibling import.
    expect(nip46.createWebCryptoSecretStore).toBe(web.createWebCryptoSecretStore);
    expect(react.NapProvider).toBeTypeOf("function");
  });
});
