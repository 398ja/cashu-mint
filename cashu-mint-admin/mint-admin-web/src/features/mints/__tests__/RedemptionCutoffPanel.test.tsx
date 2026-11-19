import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { RedemptionCutoffPanel } from "../RedemptionCutoffPanel";
import type { RedemptionCutoffRequest } from "@/api/redemptionCutoffs";

const api = vi.hoisted(() => ({
  listRedemptionCutoffs: vi.fn(),
  requestRedemptionCutoff: vi.fn(),
  approveRedemptionCutoff: vi.fn(),
  rejectRedemptionCutoff: vi.fn(),
}));
vi.mock("@/api/redemptionCutoffs", () => api);

const MINT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

function pending(overrides: Partial<RedemptionCutoffRequest> = {}): RedemptionCutoffRequest {
  return {
    requestId: "r-1",
    mintId: MINT,
    keysetId: "01ef5b83",
    redeemUntil: "2026-12-03T00:00:00Z",
    reason: "retire",
    requestedBy: "npub1alice",
    requestedAt: "2026-11-19T00:00:00Z",
    status: "PENDING_APPROVAL",
    decidedBy: null,
    decidedAt: null,
    outcome: null,
    ...overrides,
  };
}

function renderPanel(keySets: { keySetId: string; state: string }[]) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <RedemptionCutoffPanel mintId={MINT} keySets={keySets} />
    </QueryClientProvider>,
  );
}

describe("RedemptionCutoffPanel", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    api.listRedemptionCutoffs.mockResolvedValue([]);
  });

  it("offers only archived keysets, never the signing one", () => {
    renderPanel([
      { keySetId: "01f034be", state: "ACTIVE" },
      { keySetId: "01ef5b83", state: "ARCHIVED" },
    ]);
    const options = screen.getAllByRole("option").map((option) => option.textContent);
    expect(options).toContain("01ef5b83");
    expect(options).not.toContain("01f034be");
  });

  it("explains why nothing can be retired when no keyset is archived", () => {
    renderPanel([{ keySetId: "01f034be", state: "ACTIVE" }]);
    expect(screen.queryByRole("button", { name: "Request cutoff" })).toBeNull();
    expect(screen.getByText(/Only an archived keyset/)).toBeTruthy();
  });

  it("submits a dated request with its reason", async () => {
    api.requestRedemptionCutoff.mockResolvedValue(pending());
    renderPanel([{ keySetId: "01ef5b83", state: "ARCHIVED" }]);
    const user = userEvent.setup();
    const submit = screen.getByRole("button", { name: "Request cutoff" });
    expect((submit as HTMLButtonElement).disabled).toBe(true);

    await user.selectOptions(screen.getByLabelText("Archived keyset"), "01ef5b83");
    await user.type(screen.getByLabelText("Stops redeeming at"), "2026-12-03T00:00");
    await user.type(screen.getByLabelText("Reason"), "runbook step 4");
    await user.click(submit);

    await waitFor(() => expect(api.requestRedemptionCutoff).toHaveBeenCalledTimes(1));
    const [mintId, body] = api.requestRedemptionCutoff.mock.calls[0];
    expect(mintId).toBe(MINT);
    expect(body.keysetId).toBe("01ef5b83");
    expect(body.reason).toBe("runbook step 4");
    expect(new Date(body.redeemUntil).toISOString()).toBe(body.redeemUntil);
  });

  it("lets a pending request be approved, and a decided one not", async () => {
    api.listRedemptionCutoffs.mockResolvedValue([
      pending(),
      pending({ requestId: "r-2", status: "APPLIED", decidedBy: "npub1bob" }),
    ]);
    api.approveRedemptionCutoff.mockResolvedValue(pending({ status: "APPROVED" }));
    renderPanel([{ keySetId: "01ef5b83", state: "ARCHIVED" }]);

    const approve = await screen.findAllByRole("button", { name: "Approve" });
    expect(approve).toHaveLength(1);
    await userEvent.setup().click(approve[0]);
    await waitFor(() => expect(api.approveRedemptionCutoff).toHaveBeenCalledWith(MINT, "r-1"));
  });
});
