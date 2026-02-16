import { useState } from "react";
import { useParams, Link } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getMintHealth, acknowledgeMintHealth } from "@/api/health";
import { StateBadge } from "@/components/StateBadge";
import { SkeletonLoader } from "@/components/SkeletonLoader";
import { ErrorBanner } from "@/components/ErrorBanner";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import type { ApiRequestError } from "@/api/client";
import { ArrowLeft } from "lucide-react";

const ACTOR = { id: "ce114fe1-944a-43c2-b9c2-b1e21b83e0ae", displayName: "Web Operator" };

export function HealthPage() {
  const { mintId } = useParams<{ mintId: string }>();
  const queryClient = useQueryClient();
  const [showAck, setShowAck] = useState(false);
  const [result, setResult] = useState<string | null>(null);

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ["health", mintId],
    queryFn: () => getMintHealth(mintId!),
    enabled: !!mintId,
    refetchInterval: 15_000,
  });

  const ackMutation = useMutation({
    mutationFn: (reason: string) =>
      acknowledgeMintHealth(mintId!, { requestedBy: ACTOR, reason }),
    onSuccess: (res) => {
      setResult(res.message ?? "Health acknowledged");
      setShowAck(false);
      void queryClient.invalidateQueries({ queryKey: ["health", mintId] });
    },
  });

  return (
    <div className="space-y-6 max-w-3xl">
      <Link
        to={`/mints/${mintId}`}
        className="inline-flex items-center gap-1 text-sm text-zinc-400 hover:text-zinc-200"
      >
        <ArrowLeft className="h-4 w-4" /> Back to Mint
      </Link>

      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold text-zinc-100">
          Health — <span className="font-mono">{mintId}</span>
        </h1>
        <button
          onClick={() => setShowAck(true)}
          className="px-3 py-1.5 text-sm border border-zinc-700 text-zinc-300 hover:bg-zinc-800 rounded"
        >
          Acknowledge
        </button>
      </div>

      {result && (
        <div className="rounded-lg border border-emerald-900/50 bg-emerald-950/30 p-3 text-sm text-emerald-300">
          {result}
        </div>
      )}

      {isLoading && <SkeletonLoader rows={4} />}
      {error && (
        <ErrorBanner
          code={(error as ApiRequestError).code}
          message={(error as ApiRequestError).message}
          onRetry={() => void refetch()}
        />
      )}

      {data && (
        <>
          <div className="flex items-center gap-3">
            <StateBadge state={data.status} />
            {data.uptime && (
              <span className="text-sm text-zinc-400">
                Uptime: {data.uptime}
              </span>
            )}
            {data.version && (
              <span className="text-sm text-zinc-500">v{data.version}</span>
            )}
          </div>

          {data.message && (
            <p className="text-sm text-zinc-300">{data.message}</p>
          )}

          {data.checks && Object.keys(data.checks).length > 0 && (
            <div className="rounded-lg border border-zinc-800 bg-zinc-900 divide-y divide-zinc-800">
              {Object.entries(data.checks).map(([name, status]) => (
                <div
                  key={name}
                  className="flex items-center justify-between px-4 py-3"
                >
                  <span className="text-sm text-zinc-400">{name}</span>
                  <StateBadge state={status} />
                </div>
              ))}
            </div>
          )}
        </>
      )}

      <ConfirmDialog
        open={showAck}
        onOpenChange={setShowAck}
        title="Acknowledge Health Status"
        description={`Acknowledge the current health status of mint "${mintId}".`}
        confirmLabel="Acknowledge"
        requireReason
        loading={ackMutation.isPending}
        onConfirm={(reason) => ackMutation.mutate(reason)}
      />
    </div>
  );
}
