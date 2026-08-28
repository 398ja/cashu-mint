import { useEffect, useRef, useState } from "react";
import { useParams, Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { listDenominations, listKeySets } from "@/api/lifecycle";
import { SkeletonLoader } from "@/components/SkeletonLoader";
import { ErrorBanner } from "@/components/ErrorBanner";
import { EmptyState } from "@/components/EmptyState";
import { Pagination } from "@/components/Pagination";
import { formatTimestamp } from "@/lib/format";
import type { ApiRequestError } from "@/api/client";
import { ArrowLeft, Check, ChevronDown, ChevronRight, Copy } from "lucide-react";

export function MintKeySetsPage() {
  const { mintId } = useParams<{ mintId: string }>();
  const [page, setPage] = useState(0);
  const [expanded, setExpanded] = useState<string | null>(null);

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ["keysets", mintId, page],
    queryFn: () => listKeySets(mintId!, page),
    enabled: !!mintId,
  });

  return (
    <div className="space-y-6 max-w-3xl">
      <Link
        to={`/mints/${mintId}`}
        className="inline-flex items-center gap-1 text-sm text-zinc-400 hover:text-zinc-200"
      >
        <ArrowLeft className="h-4 w-4" /> Back to Mint
      </Link>

      {/* Named as the vault's view rather than the mint's: the admin reads only the
          vault, so an operator must not read this page as what the mint advertises. */}
      <div>
        <h1 className="text-xl font-semibold text-zinc-100">
          Keysets in the shared vault
        </h1>
        <p className="text-sm text-zinc-500 font-mono mt-1">{mintId}</p>
      </div>

      {isLoading && <SkeletonLoader rows={3} />}

      {/* A vault the mint could not read is an error, never an empty list: an
          Operator opening this page after a Rotation would read the empty state
          as key material lost. */}
      {error && (
        <ErrorBanner
          code={(error as ApiRequestError).code}
          message={(error as ApiRequestError).message}
          onRetry={() => void refetch()}
        />
      )}

      {data && !error && data.items.length === 0 && (
        <EmptyState
          title="No keysets in the vault"
          description="This mint has not been provisioned yet, so there is nothing for it to sign with."
        />
      )}

      {data && data.items.length > 0 && (
        <>
          <div className="rounded-lg border border-zinc-800 bg-zinc-900 divide-y divide-zinc-800">
            {data.items.map((keySet) => (
              <div key={keySet.keySetId} className="px-4 py-3">
                <div className="flex items-center justify-between gap-4">
                  <div className="min-w-0">
                    <CopyableId value={keySet.keySetId} />
                    <p className="text-xs text-zinc-500 mt-1">
                      {keySet.unit} · {formatTimestamp(keySet.createdAt)}
                    </p>
                  </div>
                  <div className="flex items-center gap-3 shrink-0">
                    <KeySetBadge state={keySet.state} />
                    <button
                      type="button"
                      aria-expanded={expanded === keySet.keySetId}
                      onClick={() =>
                        setExpanded(expanded === keySet.keySetId ? null : keySet.keySetId)
                      }
                      className="flex items-center gap-1 text-xs text-zinc-400 hover:text-zinc-200"
                    >
                      {expanded === keySet.keySetId ? (
                        <ChevronDown className="h-3.5 w-3.5" />
                      ) : (
                        <ChevronRight className="h-3.5 w-3.5" />
                      )}
                      Denominations
                    </button>
                  </div>
                </div>
                {expanded === keySet.keySetId && (
                  <Denominations mintId={mintId!} keySetId={keySet.keySetId} />
                )}
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

/**
 * The denominations of one keyset, loaded only once an Operator asks for them: a mint
 * serving many powers of two would otherwise fetch every key row to render a list nobody
 * opened. Archived keysets answer the same way -- past key material is what a recovery
 * needs, and ADR-0004 keeps those keys redeeming indefinitely.
 */
function Denominations({ mintId, keySetId }: { mintId: string; keySetId: string }) {
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ["denominations", mintId, keySetId],
    queryFn: () => listDenominations(mintId, keySetId),
  });

  if (isLoading) return <div className="mt-3"><SkeletonLoader rows={2} /></div>;

  // Same rule as the keyset listing: an unreadable vault must never render as "no keys".
  if (error)
    return (
      <div className="mt-3">
        <ErrorBanner
          code={(error as ApiRequestError).code}
          message={(error as ApiRequestError).message}
          onRetry={() => void refetch()}
        />
      </div>
    );

  if (!data?.length)
    return (
      <p className="mt-3 text-xs text-zinc-500">
        The vault holds no keys for this keyset.
      </p>
    );

  return (
    <div className="mt-3 rounded border border-zinc-800 bg-zinc-950/50">
      {/* The path, never the key: reading it needs vault credentials the browser
          does not hold, which is what makes it safe to show an Operator taking a backup. */}
      <p className="px-3 pt-2 text-xs text-zinc-500">
        Private keys stay in HashiCorp Vault. These are the paths to back up.
      </p>
      <table className="w-full text-xs">
        <thead className="text-zinc-500">
          <tr>
            <th className="px-3 py-2 text-left font-medium">Amount</th>
            <th className="px-3 py-2 text-left font-medium">Vault path</th>
          </tr>
        </thead>
        <tbody className="font-mono text-zinc-300">
          {data.map((denomination) => (
            <tr key={denomination.vaultPath} className="border-t border-zinc-800/60">
              <td className="px-3 py-1.5 whitespace-nowrap">{denomination.amount}</td>
              <td className="px-3 py-1.5 break-all">{denomination.vaultPath}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/* "Archived" alone confirms the fear that brings an Operator here after a
   Rotation. An archived keyset refuses to sign but redeems forever (ADR-0004),
   and the badge has to say so. */
function KeySetBadge({ state }: { state: string }) {
  const archived = state === "ARCHIVED";
  return (
    <span
      className={`shrink-0 rounded-full px-2.5 py-1 text-xs font-medium ${
        archived
          ? "border border-zinc-700 text-zinc-400"
          : "border border-emerald-800 bg-emerald-950/40 text-emerald-300"
      }`}
    >
      {archived ? "Archived · still redeems" : "Signing"}
    </span>
  );
}

function CopyableId({ value }: { value: string }) {
  const [copied, setCopied] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);

  useEffect(() => () => clearTimeout(timer.current), []);

  // The tick only appears once the write resolved: over plain http there is no
  // clipboard at all, and a tick claiming a copy that never happened sends the
  // Operator to paste a stale keyset id into a support conversation.
  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
    } catch {
      return;
    }
    setCopied(true);
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setCopied(false), 1500);
  }

  return (
    <button
      type="button"
      aria-label={`Copy keyset id ${value}`}
      onClick={() => void copy()}
      className="flex items-center gap-2 text-sm font-mono text-zinc-200 hover:text-zinc-50"
    >
      <span className="truncate">{value}</span>
      {copied ? (
        <Check className="h-3.5 w-3.5 shrink-0 text-emerald-400" />
      ) : (
        <Copy className="h-3.5 w-3.5 shrink-0 text-zinc-500" />
      )}
    </button>
  );
}
