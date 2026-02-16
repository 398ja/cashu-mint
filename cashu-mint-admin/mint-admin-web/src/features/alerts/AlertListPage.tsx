import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import { listAlerts, type AlertActionResponse } from "@/api/alerts";
import { DataTable, type Column } from "@/components/DataTable";
import { StateBadge } from "@/components/StateBadge";
import { SkeletonLoader } from "@/components/SkeletonLoader";
import { ErrorBanner } from "@/components/ErrorBanner";
import { EmptyState } from "@/components/EmptyState";
import { Pagination } from "@/components/Pagination";
import type { ApiRequestError } from "@/api/client";
import { Search } from "lucide-react";

const COLUMNS: Column<AlertActionResponse>[] = [
  {
    key: "alertId",
    header: "Alert ID",
    render: (a) => <span className="font-mono text-xs">{a.alertId}</span>,
  },
  {
    key: "severity",
    header: "Severity",
    render: (a) => <StateBadge state={a.severity} />,
  },
  { key: "summary", header: "Summary", render: (a) => a.summary },
  {
    key: "mintId",
    header: "Mint",
    render: (a) => (
      <span className="font-mono text-xs">{a.mintId}</span>
    ),
  },
  {
    key: "status",
    header: "Status",
    render: (a) => (
      <div className="flex gap-1.5 text-xs">
        {a.acknowledged && (
          <span className="text-emerald-400">Acknowledged</span>
        )}
        {a.silenced && <span className="text-amber-400">Silenced</span>}
        {!a.acknowledged && !a.silenced && (
          <span className="text-zinc-500">Open</span>
        )}
      </div>
    ),
  },
];

export function AlertListPage() {
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const [severity, setSeverity] = useState("");
  const [mintFilter, setMintFilter] = useState("");

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ["alerts", { severity, mintId: mintFilter, page }],
    queryFn: () =>
      listAlerts({
        severity: severity || undefined,
        mintId: mintFilter || undefined,
        page,
        size: 20,
      }),
  });

  return (
    <div className="space-y-4 max-w-5xl">
      <h1 className="text-xl font-semibold text-zinc-100">Alerts</h1>

      <div className="flex items-center gap-3">
        <div className="relative flex-1 max-w-xs">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-zinc-500" />
          <input
            type="text"
            value={mintFilter}
            onChange={(e) => {
              setMintFilter(e.target.value);
              setPage(0);
            }}
            placeholder="Filter by mint ID..."
            className="w-full rounded border border-zinc-700 bg-zinc-800 pl-9 pr-3 py-2 text-sm text-zinc-100 placeholder:text-zinc-500 focus:border-zinc-600 focus:outline-none"
          />
        </div>
        <select
          value={severity}
          onChange={(e) => {
            setSeverity(e.target.value);
            setPage(0);
          }}
          aria-label="Filter by severity"
          className="rounded border border-zinc-700 bg-zinc-800 px-3 py-2 text-sm text-zinc-100 focus:border-zinc-600 focus:outline-none"
        >
          <option value="">All Severities</option>
          <option value="CRITICAL">Critical</option>
          <option value="WARNING">Warning</option>
          <option value="INFO">Info</option>
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
        <EmptyState title="No alerts found" />
      )}
      {data && data.items.length > 0 && (
        <>
          <DataTable
            columns={COLUMNS}
            data={data.items}
            keyExtractor={(a) => a.alertId}
            onRowClick={(a) => navigate(`/alerts/${a.alertId}`)}
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
