import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import { DataTable, type Column } from "../DataTable";

interface Row {
  id: string;
  name: string;
}

const columns: Column<Row>[] = [
  { key: "id", header: "ID", render: (r) => r.id },
  { key: "name", header: "Name", render: (r) => r.name },
];

const data: Row[] = [
  { id: "1", name: "Alpha" },
  { id: "2", name: "Beta" },
];

describe("DataTable", () => {
  // Renders table headers and rows correctly
  it("renders headers and data rows", () => {
    render(
      <DataTable columns={columns} data={data} keyExtractor={(r) => r.id} />,
    );
    expect(screen.getByText("ID")).toBeInTheDocument();
    expect(screen.getByText("Name")).toBeInTheDocument();
    expect(screen.getByText("Alpha")).toBeInTheDocument();
    expect(screen.getByText("Beta")).toBeInTheDocument();
  });

  // Clickable rows respond to Enter and Space key presses
  it("handles keyboard navigation on clickable rows", async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();

    render(
      <DataTable
        columns={columns}
        data={data}
        keyExtractor={(r) => r.id}
        onRowClick={onClick}
      />,
    );

    const rows = screen.getAllByRole("button");
    expect(rows).toHaveLength(2);

    await user.tab();
    await user.keyboard("{Enter}");
    expect(onClick).toHaveBeenCalledWith(data[0]);

    await user.tab();
    await user.keyboard(" ");
    expect(onClick).toHaveBeenCalledWith(data[1]);
  });

  // No critical axe violations
  it("has no accessibility violations", async () => {
    const { container } = render(
      <DataTable columns={columns} data={data} keyExtractor={(r) => r.id} />,
    );
    const results = await axe(container);
    expect(results).toHaveNoViolations();
  });
});
