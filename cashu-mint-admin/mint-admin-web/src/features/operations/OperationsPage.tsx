import { useState } from "react";
import { useParams, Link } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  listControls,
  scheduleMaintenance,
  startMaintenance,
  completeMaintenance,
  rotateKeys,
  forceClose,
  type OperationalControlResponse,
} from "@/api/operations";
import { DataTable, type Column } from "@/components/DataTable";
import { StateBadge } from "@/components/StateBadge";
import { SkeletonLoader } from "@/components/SkeletonLoader";
import { ErrorBanner } from "@/components/ErrorBanner";
import { EmptyState } from "@/components/EmptyState";
import { Pagination } from "@/components/Pagination";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { formatTimestamp } from "@/lib/format";
import type { ApiRequestError } from "@/api/client";
import { ArrowLeft } from "lucide-react";

type OpAction =
  | "schedule"
  | "start"
  | "complete"
  | "rotate-keys"
  | "force-close";

const COLUMNS: Column<OperationalControlResponse>[] = [
  {
    key: "controlId",
    header: "Control ID",
    render: (c) => <span className="font-mono text-xs">{c.controlId}</span>,
  },
  {
    key: "status",
    header: "Status",
    render: (c) => <StateBadge state={c.status} />,
  },
  {
    key: "scheduledAt",
    header: "Scheduled",
    render: (c) => (
      <span className="text-xs">{formatTimestamp(c.scheduledAt)}</span>
    ),
  },
  {
    key: "reason",
    header: "Reason",
    render: (c) => c.reason ?? "—",
  },
];

export function OperationsPage() {
  const { mintId } = useParams<{ mintId: string }>();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [action, setAction] = useState<OpAction | null>(null);
  const [result, setResult] = useState<string | null>(null);

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ["controls", mintId, page],
    queryFn: () => listControls(mintId!, { page, size: 20 }),
    enabled: !!mintId,
  });

  const mutation = useMutation({
    mutationFn: async ({
      op,
      reason,
    }: {
      op: OpAction;
      reason: string;
    }) => {
      const body = { reason };
      switch (op) {
        case "schedule":
          return scheduleMaintenance(mintId!, body);
        case "start":
          return startMaintenance(mintId!, body);
        case "complete":
          return completeMaintenance(mintId!, body);
        case "rotate-keys":
          return rotateKeys(mintId!, body);
        case "force-close":
          return forceClose(mintId!, body);
      }
    },
    onSuccess: (res) => {
      setResult(
        `Control ${res.controlId}: ${res.status}`,
      );
      setAction(null);
      void queryClient.invalidateQueries({
        queryKey: ["controls", mintId],
      });
    },
  });

  const actionButtons: { label: string; op: OpAction; destructive: boolean }[] =
    [
      { label: "Schedule Maintenance", op: "schedule", destructive: false },
      { label: "Start Maintenance", op: "start", destructive: false },
      { label: "Complete Maintenance", op: "complete", destructive: false },
      { label: "Rotate Keys", op: "rotate-keys", destructive: true },
      { label: "Force Close", op: "force-close", destructive: true },
    ];

  return (
    <div className="space-y-4 max-w-5xl">
      <Link
        to={`/mints/${mintId}`}
        className="inline-flex items-center gap-1 text-sm text-zinc-400 hover:text-zinc-200"
      >
        <ArrowLeft className="h-4 w-4" /> Back to Mint
      </Link>

      <div className="flex items-center justify-between flex-wrap gap-2">
        <h1 className="text-xl font-semibold text-zinc-100">
          Operations — <span className="font-mono">{mintId}</span>
        </h1>
        <div className="flex flex-wrap gap-2">
          {actionButtons.map((btn) => (
            <button
              key={btn.op}
              onClick={() => setAction(btn.op)}
              className={`px-3 py-1.5 text-sm rounded ${
                btn.destructive
                  ? "border border-red-800 text-red-400 hover:bg-red-950"
                  : "border border-zinc-700 text-zinc-300 hover:bg-zinc-800"
              }`}
            >
              {btn.label}
            </button>
          ))}
        </div>
      </div>

      {result && (
        <div className="rounded-lg border border-emerald-900/50 bg-emerald-950/30 p-3 text-sm text-emerald-300">
          {result}
        </div>
      )}
      {mutation.error && (
        <ErrorBanner
          code={(mutation.error as ApiRequestError).code}
          message={(mutation.error as ApiRequestError).message}
        />
      )}

      {isLoading && <SkeletonLoader rows={6} />}
      {error && (
        <ErrorBanner
          code={(error as ApiRequestError).code}
          message={(error as ApiRequestError).message}
          onRetry={() => void refetch()}
        />
      )}
      {data && data.items.length === 0 && (
        <EmptyState title="No operational controls" />
      )}
      {data && data.items.length > 0 && (
        <>
          <DataTable
            columns={COLUMNS}
            data={data.items}
            keyExtractor={(c) => c.controlId}
          />
          <Pagination
            page={data.page}
            totalPages={data.totalPages}
            totalItems={data.totalItems}
            onPageChange={setPage}
          />
        </>
      )}

      {action && (
        <ConfirmDialog
          open={!!action}
          onOpenChange={(open) => {
            if (!open) setAction(null);
          }}
          title={
            actionButtons.find((b) => b.op === action)?.label ?? "Action"
          }
          description={`Execute ${actionButtons.find((b) => b.op === action)?.label.toLowerCase()} on mint "${mintId}".`}
          confirmLabel="Execute"
          destructive={
            actionButtons.find((b) => b.op === action)?.destructive ?? false
          }
          requireReason
          loading={mutation.isPending}
          onConfirm={(reason) => mutation.mutate({ op: action, reason })}
        />
      )}
    </div>
  );
}
