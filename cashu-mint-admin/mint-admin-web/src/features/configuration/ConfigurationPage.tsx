import { useState } from "react";
import { useParams, Link } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  listRevisions,
  applyConfiguration,
  rollbackConfiguration,
  type ConfigurationRevision,
} from "@/api/configuration";
import { DataTable, type Column } from "@/components/DataTable";
import { SkeletonLoader } from "@/components/SkeletonLoader";
import { ErrorBanner } from "@/components/ErrorBanner";
import { EmptyState } from "@/components/EmptyState";
import { Pagination } from "@/components/Pagination";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import type { ApiRequestError } from "@/api/client";
import { ArrowLeft } from "lucide-react";

const ACTOR = { id: "ce114fe1-944a-43c2-b9c2-b1e21b83e0ae", displayName: "Web Operator" };

const COLUMNS: Column<ConfigurationRevision>[] = [
  {
    key: "revisionId",
    header: "Revision",
    render: (r) => <span className="font-mono">{r.revisionId}</span>,
  },
  {
    key: "versionTag",
    header: "Version",
    render: (r) => r.versionTag,
  },
  {
    key: "parameters",
    header: "Parameters",
    render: (r) => (
      <span className="text-xs text-zinc-400">
        {Object.keys(r.parameters).length} keys
      </span>
    ),
  },
];

export function ConfigurationPage() {
  const { mintId } = useParams<{ mintId: string }>();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [rollbackTarget, setRollbackTarget] = useState<string | null>(null);
  const [showApply, setShowApply] = useState(false);
  const [applyParams, setApplyParams] = useState("");
  const [changeSummary, setChangeSummary] = useState("");
  const [result, setResult] = useState<string | null>(null);

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ["config-revisions", mintId, page],
    queryFn: () => listRevisions(mintId!, { page, size: 20 }),
    enabled: !!mintId,
  });

  const applyMutation = useMutation({
    mutationFn: () => {
      let parsed: Record<string, unknown> = {};
      try {
        parsed = JSON.parse(applyParams) as Record<string, unknown>;
      } catch {
        throw new Error("Invalid JSON");
      }
      return applyConfiguration(mintId!, {
        requestedBy: ACTOR,
        proposedConfiguration: parsed,
        changeSummary,
      });
    },
    onSuccess: (res) => {
      setResult(res.message);
      setShowApply(false);
      setApplyParams("");
      setChangeSummary("");
      void queryClient.invalidateQueries({
        queryKey: ["config-revisions", mintId],
      });
    },
  });

  const rollbackMutation = useMutation({
    mutationFn: (reason: string) =>
      rollbackConfiguration(mintId!, {
        requestedBy: ACTOR,
        targetRevisionId: rollbackTarget!,
        reason,
      }),
    onSuccess: (res) => {
      setResult(res.message);
      setRollbackTarget(null);
      void queryClient.invalidateQueries({
        queryKey: ["config-revisions", mintId],
      });
    },
  });

  return (
    <div className="space-y-4 max-w-5xl">
      <Link
        to={`/mints/${mintId}`}
        className="inline-flex items-center gap-1 text-sm text-zinc-400 hover:text-zinc-200"
      >
        <ArrowLeft className="h-4 w-4" /> Back to Mint
      </Link>

      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold text-zinc-100">
          Configuration — <span className="font-mono">{mintId}</span>
        </h1>
        <button
          onClick={() => setShowApply(true)}
          className="px-3 py-1.5 text-sm border border-zinc-700 text-zinc-300 hover:bg-zinc-800 rounded"
        >
          Apply Configuration
        </button>
      </div>

      {result && (
        <div className="rounded-lg border border-emerald-900/50 bg-emerald-950/30 p-3 text-sm text-emerald-300">
          {result}
        </div>
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
        <EmptyState title="No configuration revisions" />
      )}
      {data && data.items.length > 0 && (
        <>
          <DataTable
            columns={[
              ...COLUMNS,
              {
                key: "actions",
                header: "",
                render: (r) => (
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      setRollbackTarget(String(r.revisionId));
                    }}
                    className="text-xs text-zinc-400 hover:text-zinc-200"
                  >
                    Rollback
                  </button>
                ),
              },
            ]}
            data={data.items}
            keyExtractor={(r) => String(r.revisionId)}
          />
          <Pagination
            page={data.page}
            totalPages={data.totalPages}
            totalItems={data.totalItems}
            onPageChange={setPage}
          />
        </>
      )}

      {showApply && (
        <div className="rounded-lg border border-zinc-800 bg-zinc-900 p-4 space-y-3">
          <h3 className="text-sm font-medium text-zinc-300">
            Apply Configuration
          </h3>
          <textarea
            value={applyParams}
            onChange={(e) => setApplyParams(e.target.value)}
            placeholder='{"key": "value"}'
            rows={4}
            className="w-full rounded border border-zinc-700 bg-zinc-800 px-3 py-2 text-sm text-zinc-100 font-mono placeholder:text-zinc-500 focus:border-zinc-600 focus:outline-none"
          />
          <input
            type="text"
            value={changeSummary}
            onChange={(e) => setChangeSummary(e.target.value)}
            placeholder="Change summary..."
            className="w-full rounded border border-zinc-700 bg-zinc-800 px-3 py-2 text-sm text-zinc-100 placeholder:text-zinc-500 focus:border-zinc-600 focus:outline-none"
          />
          {applyMutation.error && (
            <ErrorBanner
              message={(applyMutation.error as ApiRequestError).message ?? applyMutation.error.message}
            />
          )}
          <div className="flex gap-2 justify-end">
            <button
              onClick={() => setShowApply(false)}
              className="px-3 py-1.5 text-sm border border-zinc-700 text-zinc-300 rounded"
            >
              Cancel
            </button>
            <button
              onClick={() => applyMutation.mutate()}
              disabled={!applyParams || !changeSummary || applyMutation.isPending}
              className="px-3 py-1.5 text-sm bg-zinc-100 text-zinc-900 rounded disabled:opacity-50"
            >
              {applyMutation.isPending ? "Applying..." : "Apply"}
            </button>
          </div>
        </div>
      )}

      <ConfirmDialog
        open={!!rollbackTarget}
        onOpenChange={(open) => {
          if (!open) setRollbackTarget(null);
        }}
        title="Rollback Configuration"
        description={`Roll back to revision ${rollbackTarget} for mint "${mintId}".`}
        confirmLabel="Rollback"
        destructive
        requireReason
        loading={rollbackMutation.isPending}
        onConfirm={(reason) => rollbackMutation.mutate(reason)}
      />
    </div>
  );
}
