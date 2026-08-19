import { useQuery } from "@tanstack/react-query";
import { fetchDashboardSummary } from "@/api/dashboard";
import { listAuditEvents } from "@/api/audit";
import { StateBadge } from "@/components/StateBadge";
import { SkeletonLoader } from "@/components/SkeletonLoader";
import { ErrorBanner } from "@/components/ErrorBanner";
import { formatTimestamp } from "@/lib/format";
import { Link } from "react-router-dom";
import { useAuth } from "@/auth/useAuth";
import type { ApiRequestError } from "@/api/client";

export function DashboardPage() {
  const { hasRole } = useAuth();

  const summary = useQuery({
    queryKey: ["dashboard-summary"],
    queryFn: fetchDashboardSummary,
    refetchInterval: 30_000,
  });

  const audit = useQuery({
    queryKey: ["audit-events", { page: 0, size: 10 }],
    queryFn: () => listAuditEvents({ page: 0, size: 10 }),
  });

  return (
    <div className="space-y-6 max-w-5xl">
      <h1 className="text-xl font-semibold text-zinc-100">Dashboard</h1>

      {summary.isLoading && <SkeletonLoader rows={3} />}
      {summary.error && (
        <ErrorBanner
          code={(summary.error as ApiRequestError).code}
          message={(summary.error as ApiRequestError).message}
          onRetry={() => void summary.refetch()}
        />
      )}

      {summary.data && (
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <SummaryCard
            title="Mints by State"
            entries={summary.data.mintsByState}
            linkTo={hasRole("MINT_ADMIN") ? "/mints" : undefined}
          />
          <div className="rounded-lg border border-zinc-800 bg-zinc-900 p-4">
            <h3 className="text-xs font-medium text-zinc-400 uppercase tracking-wider mb-3">
              Active Controls
            </h3>
            <p className="text-3xl font-bold text-zinc-100">
              {summary.data.activeControls}
            </p>
          </div>
        </div>
      )}

      <section>
        <h2 className="text-sm font-medium text-zinc-400 uppercase tracking-wider mb-3">
          Recent Activity
        </h2>
        {audit.isLoading && <SkeletonLoader rows={5} />}
        {audit.error && (
          <ErrorBanner
            code={(audit.error as ApiRequestError).code}
            message={(audit.error as ApiRequestError).message}
            onRetry={() => void audit.refetch()}
          />
        )}
        {audit.data && audit.data.items.length === 0 && (
          <p className="text-sm text-zinc-500">No recent activity.</p>
        )}
        {audit.data && audit.data.items.length > 0 && (
          <div className="space-y-2">
            {audit.data.items.map((event) => (
              <div
                key={`${event.mintId}-${event.sequence}`}
                className="rounded-lg border border-zinc-800 bg-zinc-900 px-4 py-3 flex items-center justify-between text-sm"
              >
                <div className="flex items-center gap-3">
                  <StateBadge state={event.action} />
                  <span className="text-zinc-300">{event.mintId}</span>
                  <span className="text-zinc-500">by {event.actor}</span>
                </div>
                <span className="text-zinc-500 text-xs">
                  {formatTimestamp(event.timestamp)}
                </span>
              </div>
            ))}
            <Link
              to="/audit"
              className="block text-sm text-zinc-400 hover:text-zinc-200 mt-2"
            >
              View all activity &rarr;
            </Link>
          </div>
        )}
      </section>
    </div>
  );
}

function SummaryCard({
  title,
  entries,
  linkTo,
}: {
  title: string;
  entries: Record<string, number>;
  linkTo?: string;
}) {
  const Wrapper = linkTo ? Link : "div";
  return (
    <Wrapper
      to={linkTo ?? ""}
      className="rounded-lg border border-zinc-800 bg-zinc-900 p-4 hover:border-zinc-700 transition-colors block"
    >
      <h3 className="text-xs font-medium text-zinc-400 uppercase tracking-wider mb-3">
        {title}
      </h3>
      <div className="space-y-1.5">
        {Object.entries(entries).map(([key, value]) => (
          <div key={key} className="flex items-center justify-between">
            <StateBadge state={key} />
            <span className="text-lg font-semibold text-zinc-200">
              {value}
            </span>
          </div>
        ))}
        {Object.keys(entries).length === 0 && (
          <p className="text-sm text-zinc-500">None</p>
        )}
      </div>
    </Wrapper>
  );
}
