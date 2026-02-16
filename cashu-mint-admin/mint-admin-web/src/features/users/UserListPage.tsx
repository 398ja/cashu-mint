import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { listUsers, type UserResponse } from "@/api/users";
import { DataTable, type Column } from "@/components/DataTable";
import { SkeletonLoader } from "@/components/SkeletonLoader";
import { ErrorBanner } from "@/components/ErrorBanner";
import { EmptyState } from "@/components/EmptyState";
import { Pagination } from "@/components/Pagination";
import type { ApiRequestError } from "@/api/client";
import { Search } from "lucide-react";

const COLUMNS: Column<UserResponse>[] = [
  {
    key: "userId",
    header: "User ID",
    render: (u) => <span className="font-mono text-xs">{u.userId}</span>,
  },
  { key: "displayName", header: "Name", render: (u) => u.displayName },
  { key: "email", header: "Email", render: (u) => u.email },
  {
    key: "roles",
    header: "Roles",
    render: (u) => (
      <div className="flex flex-wrap gap-1">
        {u.roles.map((r) => (
          <span
            key={r}
            className="text-xs bg-zinc-800 border border-zinc-700 rounded px-1.5 py-0.5"
          >
            {r}
          </span>
        ))}
      </div>
    ),
  },
  {
    key: "active",
    header: "Status",
    render: (u) => (
      <span className={u.active ? "text-emerald-400" : "text-zinc-500"}>
        {u.active ? "Active" : "Inactive"}
      </span>
    ),
  },
];

export function UserListPage() {
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const [activeFilter, setActiveFilter] = useState<string>("");

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: [
      "users",
      { q: search, active: activeFilter, page },
    ],
    queryFn: () =>
      listUsers({
        q: search || undefined,
        active: activeFilter === "" ? undefined : activeFilter === "true",
        page,
        size: 20,
      }),
  });

  return (
    <div className="space-y-4 max-w-5xl">
      <h1 className="text-xl font-semibold text-zinc-100">Users</h1>

      <div className="flex items-center gap-3">
        <div className="relative flex-1 max-w-xs">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-zinc-500" />
          <input
            type="text"
            value={search}
            onChange={(e) => {
              setSearch(e.target.value);
              setPage(0);
            }}
            placeholder="Search users..."
            className="w-full rounded border border-zinc-700 bg-zinc-800 pl-9 pr-3 py-2 text-sm text-zinc-100 placeholder:text-zinc-500 focus:border-zinc-600 focus:outline-none"
          />
        </div>
        <select
          value={activeFilter}
          onChange={(e) => {
            setActiveFilter(e.target.value);
            setPage(0);
          }}
          aria-label="Filter by status"
          className="rounded border border-zinc-700 bg-zinc-800 px-3 py-2 text-sm text-zinc-100 focus:border-zinc-600 focus:outline-none"
        >
          <option value="">All</option>
          <option value="true">Active</option>
          <option value="false">Inactive</option>
        </select>
      </div>

      {isLoading && <SkeletonLoader rows={8} />}
      {error && (
        <ErrorBanner
          code={(error as ApiRequestError).code}
          message={(error as ApiRequestError).message}
          onRetry={() => void refetch()}
        />
      )}
      {data && data.items.length === 0 && (
        <EmptyState title="No users found" />
      )}
      {data && data.items.length > 0 && (
        <>
          <DataTable
            columns={COLUMNS}
            data={data.items}
            keyExtractor={(u) => u.userId}
            onRowClick={(u) => navigate(`/users/${u.userId}`)}
          />
          <Pagination
            page={data.page}
            totalPages={data.totalPages}
            totalItems={data.totalItems}
            onPageChange={setPage}
          />
        </>
      )}
    </div>
  );
}
