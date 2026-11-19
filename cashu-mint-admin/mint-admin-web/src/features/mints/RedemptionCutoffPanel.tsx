import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  approveRedemptionCutoff,
  listRedemptionCutoffs,
  rejectRedemptionCutoff,
  requestRedemptionCutoff,
  type RedemptionCutoffRequest,
} from "@/api/redemptionCutoffs";
import type { ApiRequestError } from "@/api/client";
import { ErrorBanner } from "@/components/ErrorBanner";
import { formatTimestamp } from "@/lib/format";

interface KeySetRow {
  keySetId: string;
  state: string;
}

/**
 * Retire archived keysets for redemption on an announced date (cashu-mint#551).
 *
 * One Operator requests, a different Operator approves; only then is the mint told. From the
 * date on the mint refuses the keyset's proofs and stops advertising it, and a date that has
 * passed cannot be moved, so the panel says so before anyone submits.
 */
export function RedemptionCutoffPanel({
  mintId,
  keySets,
}: {
  mintId: string;
  keySets: KeySetRow[];
}) {
  const queryClient = useQueryClient();
  const archived = keySets.filter((keySet) => keySet.state === "ARCHIVED");
  const [keysetId, setKeysetId] = useState("");
  const [date, setDate] = useState("");
  const [reason, setReason] = useState("");

  const { data, error, refetch } = useQuery({
    queryKey: ["redemption-cutoffs", mintId],
    queryFn: () => listRedemptionCutoffs(mintId),
  });

  const refresh = () => void queryClient.invalidateQueries({ queryKey: ["redemption-cutoffs", mintId] });

  const request = useMutation({
    mutationFn: () =>
      requestRedemptionCutoff(mintId, {
        keysetId,
        redeemUntil: new Date(date).toISOString(),
        reason,
      }),
    onSuccess: () => {
      setReason("");
      refresh();
    },
  });
  const approve = useMutation({
    mutationFn: (requestId: string) => approveRedemptionCutoff(mintId, requestId),
    onSuccess: refresh,
  });
  const reject = useMutation({
    mutationFn: (requestId: string) => rejectRedemptionCutoff(mintId, requestId, "rejected in mint-admin"),
    onSuccess: refresh,
  });

  const failure = (request.error ?? approve.error ?? reject.error) as ApiRequestError | null;
  const canSubmit = keysetId !== "" && date !== "" && reason.trim() !== "" && !request.isPending;

  return (
    <section aria-labelledby="redemption-cutoff-heading" className="space-y-4">
      <div>
        <h2 id="redemption-cutoff-heading" className="text-lg font-semibold text-zinc-100">
          Redemption cutoff
        </h2>
        <p className="text-sm text-zinc-500 mt-1">
          From the date on, the mint refuses this keyset&apos;s proofs and stops advertising it.
          Holders who have not swapped by then lose that value. A date that has passed is final.
          Needs a second Operator&apos;s approval.
        </p>
      </div>

      {failure && <ErrorBanner code={failure.code} message={failure.message} />}
      {error && (
        <ErrorBanner
          code={(error as ApiRequestError).code}
          message={(error as ApiRequestError).message}
          onRetry={() => void refetch()}
        />
      )}

      {archived.length === 0 ? (
        <p className="text-sm text-zinc-500">
          Only an archived keyset can be retired for redemption. The signing keyset never can.
        </p>
      ) : (
        <form
          className="grid gap-3 sm:grid-cols-3"
          onSubmit={(event) => {
            event.preventDefault();
            if (canSubmit) request.mutate();
          }}
        >
          <label className="text-xs text-zinc-400">
            Archived keyset
            <select
              aria-label="Archived keyset"
              value={keysetId}
              onChange={(event) => setKeysetId(event.target.value)}
              className="mt-1 w-full rounded border border-zinc-700 bg-zinc-950 px-2 py-1.5 font-mono text-sm text-zinc-200"
            >
              <option value="">Choose…</option>
              {archived.map((keySet) => (
                <option key={keySet.keySetId} value={keySet.keySetId}>
                  {keySet.keySetId}
                </option>
              ))}
            </select>
          </label>
          <label className="text-xs text-zinc-400">
            Stops redeeming at
            <input
              aria-label="Stops redeeming at"
              type="datetime-local"
              value={date}
              onChange={(event) => setDate(event.target.value)}
              className="mt-1 w-full rounded border border-zinc-700 bg-zinc-950 px-2 py-1.5 text-sm text-zinc-200"
            />
          </label>
          <label className="text-xs text-zinc-400">
            Reason
            <input
              aria-label="Reason"
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              className="mt-1 w-full rounded border border-zinc-700 bg-zinc-950 px-2 py-1.5 text-sm text-zinc-200"
            />
          </label>
          <div className="sm:col-span-3">
            <button
              type="submit"
              disabled={!canSubmit}
              className="rounded bg-red-700 px-3 py-1.5 text-sm font-medium text-white disabled:opacity-40"
            >
              Request cutoff
            </button>
          </div>
        </form>
      )}

      {data && data.length > 0 && (
        <ul className="rounded-lg border border-zinc-800 bg-zinc-900 divide-y divide-zinc-800">
          {data.map((item) => (
            <CutoffRow
              key={item.requestId}
              item={item}
              onApprove={() => approve.mutate(item.requestId)}
              onReject={() => reject.mutate(item.requestId)}
            />
          ))}
        </ul>
      )}
    </section>
  );
}

function CutoffRow({
  item,
  onApprove,
  onReject,
}: {
  item: RedemptionCutoffRequest;
  onApprove: () => void;
  onReject: () => void;
}) {
  return (
    <li className="px-4 py-3 text-sm">
      <div className="flex items-center justify-between gap-4">
        <div className="min-w-0">
          <p className="font-mono text-zinc-200 truncate">{item.keysetId}</p>
          <p className="text-xs text-zinc-500 mt-1">
            Stops redeeming {formatTimestamp(item.redeemUntil)} · requested by {item.requestedBy}
            {item.decidedBy ? ` · decided by ${item.decidedBy}` : ""}
          </p>
          {item.outcome && <p className="text-xs text-zinc-400 mt-1">{item.outcome}</p>}
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <span className="rounded-full border border-zinc-700 px-2.5 py-1 text-xs text-zinc-300">
            {item.status.replace("_", " ").toLowerCase()}
          </span>
          {item.status === "PENDING_APPROVAL" && (
            <>
              <button type="button" onClick={onApprove} className="text-xs text-emerald-300 hover:text-emerald-200">
                Approve
              </button>
              <button type="button" onClick={onReject} className="text-xs text-zinc-400 hover:text-zinc-200">
                Reject
              </button>
            </>
          )}
        </div>
      </div>
    </li>
  );
}
