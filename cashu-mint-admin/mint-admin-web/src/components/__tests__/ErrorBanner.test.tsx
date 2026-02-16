import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import { ErrorBanner } from "../ErrorBanner";

describe("ErrorBanner", () => {
  // Renders error message with alert role
  it("renders with role=alert", () => {
    render(<ErrorBanner message="Something went wrong" />);
    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.getByText("Something went wrong")).toBeInTheDocument();
  });

  // Displays error code when provided
  it("displays error code", () => {
    render(<ErrorBanner code="ERR_404" message="Not found" />);
    expect(screen.getByText("ERR_404")).toBeInTheDocument();
  });

  // Retry button calls onRetry
  it("calls onRetry when retry button is clicked", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    render(<ErrorBanner message="Error" onRetry={onRetry} />);

    await user.click(screen.getByRole("button", { name: /retry/i }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  // Hides retry button when onRetry is not provided
  it("hides retry button when no onRetry", () => {
    render(<ErrorBanner message="Error" />);
    expect(screen.queryByRole("button")).toBeNull();
  });

  // No accessibility violations
  it("has no accessibility violations", async () => {
    const { container } = render(
      <ErrorBanner code="ERR_500" message="Server error" onRetry={() => {}} />,
    );
    const results = await axe(container);
    expect(results).toHaveNoViolations();
  });
});
