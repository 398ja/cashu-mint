import { useState } from "react";
import { useParams, Link } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  getAlert,
  acknowledgeAlert,
  silenceAlert,
  unsilenceAlert,
  escalateAlert,
} from "@/api/alerts";
import { StateBadge } from "@/components/StateBadge";
import { SkeletonLoader } from "@/components/SkeletonLoader";
import { ErrorBanner } from "@/components/ErrorBanner";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import type { ApiRequestError } from "@/api/client";
import { ArrowLeft } from "lucide-react";

type DialogType = "acknowledge" | "silence" | "unsilence" | "escalate" | null;

const ACTOR = { id: "ce114fe1-944a-43c2-b9c2-b1e21b83e0ae", displayName: "Web Operator" };

export function AlertDetailPage() {
  const { alertId } = useParams<{ alertId: string }>();
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState<DialogType>(null);
  const [result, setResult] = useState<string | null>(null);

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ["alert", alertId],
    queryFn: () => getAlert(alertId!),
    enabled: !!alertId,
  });

  const mutation = useMutation({
    mutationFn: async ({
      action,
      reason,
    }: {
      action: DialogType;
      reason: string;
    }) => {
      switch (action) {
        case "acknowledge":
          return acknowledgeAlert(alertId!, { requestedBy: ACTOR, reason });
        case "silence":
          return silenceAlert(alertId!, {
            requestedBy: ACTOR,
            durationMinutes: 60,
            reason,
          });
        case "unsilence":
          return unsilenceAlert(alertId!, { requestedBy: ACTOR, reason });
        case "escalate":
          return escalateAlert(alertId!, {
            requestedBy: ACTOR,
            policyId: "default",
            reason,
          });
        default:
          throw new Error("Unknown action");
      }
    },
    onSuccess: (res) => {
      setResult(res.message ?? "Action completed");
      setDialog(null);
      void queryClient.invalidateQueries({ queryKey: ["alert", alertId] });
      void queryClient.invalidateQueries({ queryKey: ["alerts"] });
    },
  });

  return (
    <div className="space-y-6 max-w-3xl">
      <Link
        to="/alerts"
        className="inline-flex items-center gap-1 text-sm text-zinc-400 hover:text-zinc-200"
      >
        <ArrowLeft className="h-4 w-4" /> Back to Alerts
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
                {data.alertId}
              </h1>
              <div className="flex items-center gap-2 mt-2">
                <StateBadge state={data.severity} />
                {data.acknowledged && (
                  <span className="text-xs text-emerald-400">Acknowledged</span>
                )}
                {data.silenced && (
                  <span className="text-xs text-amber-400">Silenced</span>
                )}
              </div>
            </div>
            <div className="flex gap-2">
              {!data.acknowledged && (
                <button
                  onClick={() => setDialog("acknowledge")}
                  className="px-3 py-1.5 text-sm border border-zinc-700 text-zinc-300 hover:bg-zinc-800 rounded"
                >
                  Acknowledge
                </button>
              )}
              {data.silenced ? (
                <button
                  onClick={() => setDialog("unsilence")}
                  className="px-3 py-1.5 text-sm border border-zinc-700 text-zinc-300 hover:bg-zinc-800 rounded"
                >
                  Unsilence
                </button>
              ) : (
                <button
                  onClick={() => setDialog("silence")}
                  className="px-3 py-1.5 text-sm border border-amber-800 text-amber-400 hover:bg-amber-950 rounded"
                >
                  Silence
                </button>
              )}
              <button
                onClick={() => setDialog("escalate")}
                className="px-3 py-1.5 text-sm border border-red-800 text-red-400 hover:bg-red-950 rounded"
              >
                Escalate
              </button>
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

          <div className="rounded-lg border border-zinc-800 bg-zinc-900 divide-y divide-zinc-800">
            <Row label="Mint" value={data.mintId} />
            <Row label="Severity" value={data.severity} />
            <Row label="Summary" value={data.summary} />
            <Row
              label="Acknowledged"
              value={data.acknowledged ? "Yes" : "No"}
            />
            <Row label="Silenced" value={data.silenced ? "Yes" : "No"} />
            {data.silenceMinutes && (
              <Row
                label="Silence Duration"
                value={`${data.silenceMinutes} min`}
              />
            )}
            {data.escalations.length > 0 && (
              <Row
                label="Escalations"
                value={data.escalations.join(", ")}
              />
            )}
          </div>
        </>
      )}

      {dialog && (
        <ConfirmDialog
          open={!!dialog}
          onOpenChange={(open) => {
            if (!open) setDialog(null);
          }}
          title={`${dialog.charAt(0).toUpperCase() + dialog.slice(1)} Alert`}
          description={`${dialog.charAt(0).toUpperCase() + dialog.slice(1)} alert "${alertId}".`}
          confirmLabel={dialog.charAt(0).toUpperCase() + dialog.slice(1)}
          destructive={dialog === "escalate"}
          requireReason
          loading={mutation.isPending}
          onConfirm={(reason) => mutation.mutate({ action: dialog, reason })}
        />
      )}
    </div>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between px-4 py-3">
      <span className="text-sm text-zinc-400">{label}</span>
      <span className="text-sm text-zinc-200">{value}</span>
    </div>
  );
}
