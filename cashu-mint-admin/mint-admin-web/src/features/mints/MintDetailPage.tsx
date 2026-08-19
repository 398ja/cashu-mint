import { useState } from "react";
import { useParams, Link } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  getMint,
  pauseMint,
  resumeMint,
  retireMint,
} from "@/api/lifecycle";
import { StateBadge } from "@/components/StateBadge";
import { SkeletonLoader } from "@/components/SkeletonLoader";
import { ErrorBanner } from "@/components/ErrorBanner";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { formatTimestamp } from "@/lib/format";
import type { ApiRequestError } from "@/api/client";
import { ArrowLeft, Activity, Wrench } from "lucide-react";
import { useAuth } from "@/auth/useAuth";

interface ActionConfig {
  label: string;
  endpoint: "pause" | "resume" | "retire";
  destructive: boolean;
}

const ACTIONS_BY_STATE: Record<string, ActionConfig[]> = {
  PROVISIONED: [
    { label: "Activate", endpoint: "resume", destructive: false },
    { label: "Retire", endpoint: "retire", destructive: true },
  ],
  ACTIVE: [
    { label: "Pause", endpoint: "pause", destructive: false },
    { label: "Retire", endpoint: "retire", destructive: true },
  ],
  SUSPENDED: [
    { label: "Resume", endpoint: "resume", destructive: false },
    { label: "Retire", endpoint: "retire", destructive: true },
  ],
  DECOMMISSIONED: [],
};

const ACTOR = { id: "ce114fe1-944a-43c2-b9c2-b1e21b83e0ae", displayName: "Web Operator" };

export function MintDetailPage() {
  const { mintId } = useParams<{ mintId: string }>();
  const queryClient = useQueryClient();
  const { hasRole } = useAuth();

  const [confirmAction, setConfirmAction] = useState<ActionConfig | null>(null);
  const [actionResult, setActionResult] = useState<string | null>(null);

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ["mint", mintId],
    queryFn: () => getMint(mintId!),
    enabled: !!mintId,
  });

  const mutation = useMutation({
    mutationFn: async ({
      endpoint,
      reason,
    }: {
      endpoint: string;
      reason: string;
    }) => {
      const body = { requestedBy: ACTOR, reason };
      switch (endpoint) {
        case "pause":
          return pauseMint(mintId!, body);
        case "resume":
          return resumeMint(mintId!, body);
        case "retire":
          return retireMint(mintId!, body);
        default:
          throw new Error(`Unknown endpoint: ${endpoint}`);
      }
    },
    onSuccess: (result) => {
      setActionResult(result.message);
      setConfirmAction(null);
      void queryClient.invalidateQueries({ queryKey: ["mint", mintId] });
      void queryClient.invalidateQueries({ queryKey: ["mints"] });
    },
  });

  const actions = data
    ? (ACTIONS_BY_STATE[data.lifecycleState] ?? [])
    : [];

  return (
    <div className="space-y-6 max-w-3xl">
      <Link
        to="/mints"
        className="inline-flex items-center gap-1 text-sm text-zinc-400 hover:text-zinc-200"
      >
        <ArrowLeft className="h-4 w-4" /> Back to Mints
      </Link>

      {isLoading && <SkeletonLoader rows={6} />}
      {error && (
        <ErrorBanner
          code={(error as ApiRequestError).code}
          message={(error as ApiRequestError).message}
          onRetry={() => void refetch()}
        />
      )}

      {data && (
        <>
          <div className="flex items-center justify-between">
            <div>
              <h1 className="text-xl font-semibold text-zinc-100 font-mono">
                {data.mintId}
              </h1>
              <div className="flex items-center gap-2 mt-2">
                <StateBadge state={data.lifecycleState} />
                <span className="text-xs text-zinc-500">
                  Rev {data.configurationRevisionId}
                </span>
              </div>
            </div>

            <div className="flex items-center gap-2">
              {actions.map((action) => (
                <button
                  key={action.endpoint}
                  onClick={() => setConfirmAction(action)}
                  disabled={mutation.isPending}
                  className={`px-3 py-1.5 text-sm font-medium rounded ${
                    action.destructive
                      ? "border border-red-800 text-red-400 hover:bg-red-950"
                      : "border border-zinc-700 text-zinc-300 hover:bg-zinc-800"
                  } disabled:opacity-50`}
                >
                  {action.label}
                </button>
              ))}
            </div>
          </div>

          {actionResult && (
            <div className="rounded-lg border border-emerald-900/50 bg-emerald-950/30 p-3 text-sm text-emerald-300">
              {actionResult}
            </div>
          )}

          {mutation.error && (
            <ErrorBanner
              code={(mutation.error as ApiRequestError).code}
              message={(mutation.error as ApiRequestError).message}
            />
          )}

          <div className="rounded-lg border border-zinc-800 bg-zinc-900 divide-y divide-zinc-800">
            <DetailRow label="State" value={data.lifecycleState} />
            <DetailRow label="Version Tag" value={data.versionTag} />
            <DetailRow label="Last Actor" value={data.lastActor} />
            <DetailRow label="Last Action" value={data.lastAction} />
            <DetailRow
              label="Last Modified"
              value={formatTimestamp(data.lastModified)}
            />
          </div>

          {Object.keys(data.configurationParameters).length > 0 && (
            <section>
              <h2 className="text-sm font-medium text-zinc-400 uppercase tracking-wider mb-2">
                Configuration Parameters
              </h2>
              <div className="rounded-lg border border-zinc-800 bg-zinc-900 divide-y divide-zinc-800">
                {Object.entries(data.configurationParameters).map(
                  ([key, value]) => (
                    <DetailRow key={key} label={key} value={value} />
                  ),
                )}
              </div>
            </section>
          )}

          {hasRole("MINT_ADMIN") && (
            <div className="flex gap-3">
              <a
                href={import.meta.env.VITE_GRAFANA_URL ?? "http://localhost:3000"}
                target="_blank"
                rel="noreferrer"
                className="inline-flex items-center gap-1.5 text-sm text-zinc-400 hover:text-zinc-200 border border-zinc-800 rounded px-3 py-1.5"
              >
                <Activity className="h-4 w-4" /> Health (Grafana)
              </a>
              {hasRole("OPS_ADMIN") && (
                <Link
                  to={`/mints/${mintId}/operations`}
                  className="inline-flex items-center gap-1.5 text-sm text-zinc-400 hover:text-zinc-200 border border-zinc-800 rounded px-3 py-1.5"
                >
                  <Wrench className="h-4 w-4" /> Operations
                </Link>
              )}
            </div>
          )}
        </>
      )}

      {confirmAction && (
        <ConfirmDialog
          open={!!confirmAction}
          onOpenChange={(open) => {
            if (!open) setConfirmAction(null);
          }}
          title={`${confirmAction.label} Mint`}
          description={`Are you sure you want to ${confirmAction.label.toLowerCase()} mint "${mintId}"?`}
          confirmLabel={confirmAction.label}
          destructive={confirmAction.destructive}
          requireReason
          loading={mutation.isPending}
          onConfirm={(reason) =>
            mutation.mutate({ endpoint: confirmAction.endpoint, reason })
          }
        />
      )}
    </div>
  );
}

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between px-4 py-3">
      <span className="text-sm text-zinc-400">{label}</span>
      <span className="text-sm text-zinc-200 font-mono">{value}</span>
    </div>
  );
}
