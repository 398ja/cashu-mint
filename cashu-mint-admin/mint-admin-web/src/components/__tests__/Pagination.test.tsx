import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import { Pagination } from "../Pagination";

describe("Pagination", () => {
  // Returns null when there is only one page
  it("renders nothing for single page", () => {
    const { container } = render(
      <Pagination
        page={0}
        totalPages={1}
        totalItems={5}
        onPageChange={vi.fn()}
      />,
    );
    expect(container.innerHTML).toBe("");
  });

  // Shows page info and navigation buttons
  it("renders page info and buttons", () => {
    render(
      <Pagination
        page={1}
        totalPages={3}
        totalItems={60}
        onPageChange={vi.fn()}
      />,
    );
    expect(screen.getByText("Page 2 of 3")).toBeInTheDocument();
    expect(screen.getByText("60 total items")).toBeInTheDocument();
  });

  // Disables previous button on first page
  it("disables previous button on first page", () => {
    render(
      <Pagination
        page={0}
        totalPages={3}
        totalItems={60}
        onPageChange={vi.fn()}
      />,
    );
    expect(screen.getByLabelText("Previous page")).toBeDisabled();
  });

  // Disables next button on last page
  it("disables next button on last page", () => {
    render(
      <Pagination
        page={2}
        totalPages={3}
        totalItems={60}
        onPageChange={vi.fn()}
      />,
    );
    expect(screen.getByLabelText("Next page")).toBeDisabled();
  });

  // Calls onPageChange with correct page numbers
  it("navigates pages correctly", async () => {
    const user = userEvent.setup();
    const onPageChange = vi.fn();
    render(
      <Pagination
        page={1}
        totalPages={3}
        totalItems={60}
        onPageChange={onPageChange}
      />,
    );

    await user.click(screen.getByLabelText("Previous page"));
    expect(onPageChange).toHaveBeenCalledWith(0);

    await user.click(screen.getByLabelText("Next page"));
    expect(onPageChange).toHaveBeenCalledWith(2);
  });

  // No accessibility violations
  it("has no accessibility violations", async () => {
    const { container } = render(
      <Pagination
        page={1}
        totalPages={3}
        totalItems={60}
        onPageChange={vi.fn()}
      />,
    );
    const results = await axe(container);
    expect(results).toHaveNoViolations();
  });
});
