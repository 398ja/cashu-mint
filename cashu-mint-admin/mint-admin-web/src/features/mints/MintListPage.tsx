import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { listMints, type MintDetail } from "@/api/lifecycle";
import { DataTable, type Column } from "@/components/DataTable";
import { StateBadge } from "@/components/StateBadge";
import { SkeletonLoader } from "@/components/SkeletonLoader";
import { ErrorBanner } from "@/components/ErrorBanner";
import { EmptyState } from "@/components/EmptyState";
import { Pagination } from "@/components/Pagination";
import { formatTimestamp } from "@/lib/format";
import type { ApiRequestError } from "@/api/client";
import { Search, Plus } from "lucide-react";

const COLUMNS: Column<MintDetail>[] = [
  {
    key: "mintId",
    header: "Mint ID",
    render: (m) => <span className="font-mono text-xs">{m.mintId}</span>,
  },
  {
    key: "state",
    header: "State",
    render: (m) => <StateBadge state={m.lifecycleState} />,
  },
  {
    key: "revision",
    header: "Config Rev",
    render: (m) => m.configurationRevisionId,
  },
  {
    key: "lastAction",
    header: "Last Action",
    render: (m) => m.lastAction,
  },
  {
    key: "lastModified",
    header: "Modified",
    render: (m) => (
      <span className="text-xs">{formatTimestamp(m.lastModified)}</span>
    ),
  },
];

export function MintListPage() {
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const [stateFilter, setStateFilter] = useState("");
  const [search, setSearch] = useState("");

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ["mints", { state: stateFilter, q: search, page }],
    queryFn: () =>
      listMints({ state: stateFilter || undefined, q: search || undefined, page, size: 20 }),
  });

  return (
    <div className="space-y-4 max-w-5xl">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold text-zinc-100">Mints</h1>
        <button
          onClick={() => navigate("/mints/create")}
          className="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium rounded bg-zinc-100 text-zinc-900 hover:bg-zinc-200"
        >
          <Plus className="h-4 w-4" /> Create Mint
        </button>
      </div>

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
            placeholder="Search mints..."
            className="w-full rounded border border-zinc-700 bg-zinc-800 pl-9 pr-3 py-2 text-sm text-zinc-100 placeholder:text-zinc-500 focus:border-zinc-600 focus:outline-none"
          />
        </div>
        <select
          value={stateFilter}
          onChange={(e) => {
            setStateFilter(e.target.value);
            setPage(0);
          }}
          aria-label="Filter by state"
          className="rounded border border-zinc-700 bg-zinc-800 px-3 py-2 text-sm text-zinc-100 focus:border-zinc-600 focus:outline-none"
        >
          <option value="">All States</option>
          <option value="PROVISIONING">Provisioning</option>
          <option value="PROVISIONED">Provisioned</option>
          <option value="PROVISION_FAILED">Provision Failed</option>
          <option value="ACTIVE">Active</option>
          <option value="SUSPENDED">Suspended</option>
          <option value="DECOMMISSIONED">Decommissioned</option>
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
        <EmptyState
          title="No mints found"
          description={
            search || stateFilter
              ? "Try adjusting your filters."
              : "No mints have been created yet."
          }
        />
      )}
      {data && data.items.length > 0 && (
        <>
          <DataTable
            columns={COLUMNS}
            data={data.items}
            keyExtractor={(m) => m.mintId}
            onRowClick={(m) => navigate(`/mints/${m.mintId}`)}
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
