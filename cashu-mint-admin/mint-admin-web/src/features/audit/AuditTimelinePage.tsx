import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { listAuditEvents } from "@/api/audit";
import { StateBadge } from "@/components/StateBadge";
import { SkeletonLoader } from "@/components/SkeletonLoader";
import { ErrorBanner } from "@/components/ErrorBanner";
import { EmptyState } from "@/components/EmptyState";
import { Pagination } from "@/components/Pagination";
import { formatTimestamp } from "@/lib/format";
import type { ApiRequestError } from "@/api/client";
import { Search } from "lucide-react";

export function AuditTimelinePage() {
  const [page, setPage] = useState(0);
  const [mintFilter, setMintFilter] = useState("");
  const [actorFilter, setActorFilter] = useState("");

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: [
      "audit-events",
      { mintId: mintFilter, actor: actorFilter, page },
    ],
    queryFn: () =>
      listAuditEvents({
        mintId: mintFilter || undefined,
        actor: actorFilter || undefined,
        page,
        size: 30,
      }),
  });

  return (
    <div className="space-y-4 max-w-5xl">
      <h1 className="text-xl font-semibold text-zinc-100">Audit Log</h1>

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
        <input
          type="text"
          value={actorFilter}
          onChange={(e) => {
            setActorFilter(e.target.value);
            setPage(0);
          }}
          placeholder="Filter by actor..."
          className="rounded border border-zinc-700 bg-zinc-800 px-3 py-2 text-sm text-zinc-100 placeholder:text-zinc-500 focus:border-zinc-600 focus:outline-none"
        />
      </div>

      {isLoading && <SkeletonLoader rows={10} />}
      {error && (
        <ErrorBanner
          code={(error as ApiRequestError).code}
          message={(error as ApiRequestError).message}
          onRetry={() => void refetch()}
        />
      )}
      {data && data.items.length === 0 && (
        <EmptyState title="No audit events found" />
      )}
      {data && data.items.length > 0 && (
        <>
          <div className="space-y-2">
            {data.items.map((event) => (
              <div
                key={`${event.mintId}-${event.sequence}`}
                className="rounded-lg border border-zinc-800 bg-zinc-900 px-4 py-3 flex items-center justify-between"
              >
                <div className="flex items-center gap-3 min-w-0">
                  <StateBadge state={event.action} />
                  <span className="text-sm font-mono text-zinc-300 truncate">
                    {event.mintId}
                  </span>
                  <span className="text-sm text-zinc-500">
                    by {event.actor}
                  </span>
                  {event.configurationRevisionId != null && (
                    <span className="text-xs text-zinc-600">
                      rev {event.configurationRevisionId}
                    </span>
                  )}
                </div>
                <span className="text-xs text-zinc-500 shrink-0 ml-4">
                  {formatTimestamp(event.timestamp)}
                </span>
              </div>
            ))}
          </div>
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
