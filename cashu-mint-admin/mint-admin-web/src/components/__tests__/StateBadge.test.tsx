import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { axe } from "vitest-axe";
import { StateBadge } from "../StateBadge";

describe("StateBadge", () => {
  // Renders the state text label (not color-only)
  it("displays the state as text", () => {
    render(<StateBadge state="ACTIVE" />);
    expect(screen.getByText("ACTIVE")).toBeInTheDocument();
  });

  // Applies correct styling for known states
  it("applies correct style for CRITICAL state", () => {
    render(<StateBadge state="CRITICAL" />);
    const badge = screen.getByText("CRITICAL");
    expect(badge.className).toContain("text-red-300");
  });

  // Falls back to default style for unknown states
  it("uses default style for unknown state", () => {
    render(<StateBadge state="CUSTOM" />);
    const badge = screen.getByText("CUSTOM");
    expect(badge.className).toContain("text-zinc-300");
  });

  // No accessibility violations
  it("has no accessibility violations", async () => {
    const { container } = render(<StateBadge state="ACTIVE" />);
    const results = await axe(container);
    expect(results).toHaveNoViolations();
  });
});
