import { useState } from "react";
import { useParams, Link } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getUser, deactivateUser } from "@/api/users";
import { SkeletonLoader } from "@/components/SkeletonLoader";
import { ErrorBanner } from "@/components/ErrorBanner";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import type { ApiRequestError } from "@/api/client";
import { ArrowLeft } from "lucide-react";

export function UserDetailPage() {
  const { userId } = useParams<{ userId: string }>();
  const queryClient = useQueryClient();

  const [confirmDeactivate, setConfirmDeactivate] = useState(false);
  const [result, setResult] = useState<string | null>(null);

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ["user", userId],
    queryFn: () => getUser(userId!),
    enabled: !!userId,
  });

  const deactivateMutation = useMutation({
    mutationFn: (reason: string) =>
      deactivateUser(userId!, { reason }),
    onSuccess: (res) => {
      setResult(res.message ?? "User deactivated");
      setConfirmDeactivate(false);
      void queryClient.invalidateQueries({ queryKey: ["user", userId] });
    },
  });

  return (
    <div className="space-y-6 max-w-3xl">
      <Link
        to="/users"
        className="inline-flex items-center gap-1 text-sm text-zinc-400 hover:text-zinc-200"
      >
        <ArrowLeft className="h-4 w-4" /> Back to Users
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
              <h1 className="text-xl font-semibold text-zinc-100">
                {data.displayName || data.userId}
              </h1>
              <p className="text-sm text-zinc-500 font-mono mt-1">
                {data.userId}
              </p>
            </div>
            <div className="flex gap-2">
              {data.active && (
                <button
                  onClick={() => setConfirmDeactivate(true)}
                  className="px-3 py-1.5 text-sm border border-red-800 text-red-400 hover:bg-red-950 rounded"
                >
                  Deactivate
                </button>
              )}
            </div>
          </div>

          {result && (
            <div className="rounded-lg border border-emerald-900/50 bg-emerald-950/30 p-3 text-sm text-emerald-300">
              {result}
            </div>
          )}

          <div className="rounded-lg border border-zinc-800 bg-zinc-900 divide-y divide-zinc-800">
            <Row label="Email" value={data.email} />
            <Row
              label="Status"
              value={data.active ? "Active" : "Inactive"}
            />
            <Row label="Roles" value={data.roles.join(", ")} />
          </div>
        </>
      )}

      <ConfirmDialog
        open={confirmDeactivate}
        onOpenChange={setConfirmDeactivate}
        title="Deactivate User"
        description={`This will deactivate user "${userId}". They will lose access.`}
        confirmLabel="Deactivate"
        destructive
        requireReason
        loading={deactivateMutation.isPending}
        onConfirm={(reason) => deactivateMutation.mutate(reason)}
      />

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
