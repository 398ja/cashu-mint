import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import { ConfirmDialog } from "../ConfirmDialog";

describe("ConfirmDialog", () => {
  const defaultProps = {
    open: true,
    onOpenChange: vi.fn(),
    title: "Confirm Action",
    description: "Are you sure you want to proceed?",
    onConfirm: vi.fn(),
  };

  // Renders dialog with title and description
  it("renders title and description", () => {
    render(<ConfirmDialog {...defaultProps} />);
    expect(screen.getByText("Confirm Action")).toBeInTheDocument();
    expect(
      screen.getByText("Are you sure you want to proceed?"),
    ).toBeInTheDocument();
  });

  // Calls onConfirm when confirm button is clicked
  it("calls onConfirm on confirm click", async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    render(<ConfirmDialog {...defaultProps} onConfirm={onConfirm} />);

    await user.click(screen.getByRole("button", { name: /confirm/i }));
    expect(onConfirm).toHaveBeenCalledWith("");
  });

  // Disables confirm when reason is required but empty
  it("disables confirm when reason required but empty", () => {
    render(<ConfirmDialog {...defaultProps} requireReason />);
    expect(screen.getByRole("button", { name: /confirm/i })).toBeDisabled();
  });

  // Enables confirm when reason is provided
  it("enables confirm when reason is provided", async () => {
    const user = userEvent.setup();
    render(<ConfirmDialog {...defaultProps} requireReason />);

    await user.type(screen.getByRole("textbox"), "Valid reason");
    expect(
      screen.getByRole("button", { name: /confirm/i }),
    ).toBeEnabled();
  });

  // No accessibility violations
  it("has no accessibility violations", async () => {
    const { container } = render(<ConfirmDialog {...defaultProps} />);
    const results = await axe(container);
    expect(results).toHaveNoViolations();
  });
});
