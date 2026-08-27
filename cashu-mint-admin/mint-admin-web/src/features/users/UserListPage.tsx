import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import {
  createUser,
  deactivateUser,
  listUsers,
  reinstateUser,
  type UserResponse,
} from "@/api/users";
import { isNpub } from "./npub";
import { DataTable, type Column } from "@/components/DataTable";
import { SkeletonLoader } from "@/components/SkeletonLoader";
import { ErrorBanner } from "@/components/ErrorBanner";
import { EmptyState } from "@/components/EmptyState";
import { Pagination } from "@/components/Pagination";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import type { ApiRequestError } from "@/api/client";
import { Search } from "lucide-react";

/** SUPER_ADMIN is configuration rather than a grant, so it is not offered here. */
const ASSIGNABLE_ROLES = ["MINT_ADMIN", "USER_ADMIN", "OPS_ADMIN"];

export function UserListPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const [activeFilter, setActiveFilter] = useState<string>("");
  const [pending, setPending] = useState<UserResponse | null>(null);
  const [refusal, setRefusal] = useState<string | null>(null);

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ["users", { q: search, active: activeFilter, page }],
    queryFn: () =>
      listUsers({
        q: search || undefined,
        active: activeFilter === "" ? undefined : activeFilter === "true",
        page,
        size: 20,
      }),
  });

  const reload = () =>
    void queryClient.invalidateQueries({ queryKey: ["users"] });

  // Suspension and reinstatement are the same decision in two directions, so they
  // share one dialog and one mutation rather than being duplicated per row.
  const lifecycle = useMutation({
    mutationFn: ({ user, reason }: { user: UserResponse; reason: string }) =>
      user.active
        ? deactivateUser(user.userId, { reason })
        : reinstateUser(user.userId, { reason }),
    onSuccess: () => {
      setPending(null);
      setRefusal(null);
      reload();
    },
    // The API refuses some of what the page offers -- the Super Administrator
    // most of all. Left unsaid, the dialog just sits there having done nothing.
    onError: (e) =>
      setRefusal(
        (e as ApiRequestError).message ?? "The change was refused.",
      ),
  });

  const columns: Column<UserResponse>[] = [
    { key: "displayName", header: "Name", render: (u) => u.displayName },
    {
      key: "npub",
      header: "Identity",
      render: (u) => (
        <span className="font-mono text-xs truncate block max-w-[16rem]" title={u.npub ?? ""}>
          {u.npub ?? "—"}
        </span>
      ),
    },
    {
      key: "roles",
      header: "Roles",
      render: (u) => (
        <div className="flex flex-wrap gap-1">
          {u.roles.map((r) => (
            <span
              key={r}
              className="text-xs bg-zinc-800 border border-zinc-700 rounded px-1.5 py-0.5"
            >
              {r}
            </span>
          ))}
        </div>
      ),
    },
    {
      key: "active",
      header: "Status",
      render: (u) => (
        <span className={u.active ? "text-emerald-400" : "text-amber-400"}>
          {u.active ? "Active" : "Suspended"}
        </span>
      ),
    },
    {
      key: "actions",
      header: "",
      render: (u) =>
        u.configurationAnchored ? (
          <span className="text-xs text-zinc-500">Configuration-anchored</span>
        ) : (
          <button
            onClick={(e) => {
              e.stopPropagation();
              setPending(u);
            }}
            className={`px-2 py-1 text-xs rounded border ${
              u.active
                ? "border-red-800 text-red-400 hover:bg-red-950"
                : "border-zinc-700 text-zinc-300 hover:bg-zinc-800"
            }`}
          >
            {u.active ? "Suspend" : "Reinstate"}
          </button>
        ),
    },
  ];

  return (
    <div className="space-y-4 max-w-5xl">
      <h1 className="text-xl font-semibold text-zinc-100">Operators</h1>

      <AddOperatorForm onAdded={reload} />

      <div className="flex items-center gap-3">
        <div className="relative flex-1 max-w-xs">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-zinc-500" />
          <input
            value={search}
            onChange={(e) => {
              setSearch(e.target.value);
              setPage(0);
            }}
            placeholder="Search operators..."
            aria-label="Search operators"
            className="w-full rounded border border-zinc-700 bg-zinc-800 pl-9 pr-3 py-2 text-sm text-zinc-100 placeholder:text-zinc-500 focus:border-zinc-600 focus:outline-none"
          />
        </div>
        <select
          value={activeFilter}
          onChange={(e) => {
            setActiveFilter(e.target.value);
            setPage(0);
          }}
          aria-label="Filter by status"
          className="rounded border border-zinc-700 bg-zinc-800 px-3 py-2 text-sm text-zinc-100 focus:border-zinc-600 focus:outline-none"
        >
          <option value="">All</option>
          <option value="true">Active</option>
          <option value="false">Suspended</option>
        </select>
      </div>

      {isLoading && <SkeletonLoader rows={8} />}
      {error && (
        <ErrorBanner
          code={(error as ApiRequestError).code}
          message={(error as ApiRequestError).message}
          onRetry={() => void refetch()}
        />
      )}

      {data && data.items.length === 0 && (
        <EmptyState title="No operators found" />
      )}

      {data && data.items.length > 0 && (
        <>
          <DataTable
            columns={columns}
            data={data.items}
            keyExtractor={(u) => u.userId}
            onRowClick={(u) => navigate(`/users/${u.userId}`)}
          />
          <Pagination
            page={data.page}
            totalPages={data.totalPages}
            totalItems={data.totalItems}
            onPageChange={setPage}
          />
        </>
      )}

      <ConfirmDialog
        open={pending !== null}
        onOpenChange={(open) => {
          if (!open) {
            setPending(null);
            setRefusal(null);
          }
        }}
        title={pending?.active ? "Suspend Operator" : "Reinstate Operator"}
        description={
          refusal ??
          (pending?.active
            ? `${pending.displayName} loses access to the admin as soon as this is confirmed.`
            : `${pending?.displayName} regains the access their roles carry.`)
        }
        confirmLabel={pending?.active ? "Suspend" : "Reinstate"}
        destructive={pending?.active}
        requireReason
        loading={lifecycle.isPending}
        onConfirm={(reason) =>
          pending && lifecycle.mutate({ user: pending, reason })
        }
      />
    </div>
  );
}

function AddOperatorForm({ onAdded }: { onAdded: () => void }) {
  const [displayName, setDisplayName] = useState("");
  const [npub, setNpub] = useState("");
  const [role, setRole] = useState(ASSIGNABLE_ROLES[0]);
  const [problem, setProblem] = useState<string | null>(null);

  const create = useMutation({
    mutationFn: () =>
      createUser({
        // The account id is the admin's own key, not the Operator's: the npub is
        // what identifies them, and it is stored alongside rather than encoded here.
        userId: crypto.randomUUID(),
        displayName: displayName.trim(),
        npub: npub.trim(),
        roles: [role],
      }),
    onSuccess: () => {
      setDisplayName("");
      setNpub("");
      setProblem(null);
      onAdded();
    },
    onError: (e) =>
      setProblem(
        (e as ApiRequestError).message ?? "The operator could not be added.",
      ),
  });

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        if (!isNpub(npub)) {
          setProblem("That is not a valid npub.");
          return;
        }
        setProblem(null);
        create.mutate();
      }}
      className="rounded-lg border border-zinc-800 bg-zinc-900 p-4 flex flex-wrap items-end gap-3"
    >
      <div className="flex-1 min-w-[12rem]">
        <label
          htmlFor="operator-name"
          className="block text-xs text-zinc-400 mb-1"
        >
          Display name
        </label>
        <input
          id="operator-name"
          value={displayName}
          onChange={(e) => setDisplayName(e.target.value)}
          required
          className="w-full rounded border border-zinc-700 bg-zinc-800 px-3 py-2 text-sm text-zinc-100 focus:border-zinc-600 focus:outline-none"
        />
      </div>
      <div className="flex-[2] min-w-[18rem]">
        <label
          htmlFor="operator-npub"
          className="block text-xs text-zinc-400 mb-1"
        >
          npub
        </label>
        <input
          id="operator-npub"
          value={npub}
          onChange={(e) => setNpub(e.target.value)}
          required
          placeholder="npub1..."
          className="w-full rounded border border-zinc-700 bg-zinc-800 px-3 py-2 text-sm font-mono text-zinc-100 placeholder:text-zinc-600 focus:border-zinc-600 focus:outline-none"
        />
      </div>
      <div>
        <label
          htmlFor="operator-role"
          className="block text-xs text-zinc-400 mb-1"
        >
          Role
        </label>
        <select
          id="operator-role"
          value={role}
          onChange={(e) => setRole(e.target.value)}
          className="rounded border border-zinc-700 bg-zinc-800 px-3 py-2 text-sm text-zinc-100 focus:border-zinc-600 focus:outline-none"
        >
          {ASSIGNABLE_ROLES.map((r) => (
            <option key={r} value={r}>
              {r}
            </option>
          ))}
        </select>
      </div>
      <button
        type="submit"
        disabled={create.isPending}
        className="px-3 py-2 text-sm rounded bg-zinc-100 text-zinc-900 hover:bg-zinc-200 disabled:opacity-50"
      >
        {create.isPending ? "Adding..." : "Add Operator"}
      </button>
      {problem && (
        <p role="alert" className="w-full text-sm text-red-400">
          {problem}
        </p>
      )}
    </form>
  );
}
