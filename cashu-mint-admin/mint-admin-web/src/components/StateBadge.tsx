const STATE_STYLES: Record<string, string> = {
  PROVISIONING: "bg-indigo-950 text-indigo-300 border-indigo-800",
  PROVISIONED: "bg-blue-950 text-blue-300 border-blue-800",
  PROVISION_FAILED: "bg-red-950 text-red-300 border-red-800",
  ACTIVE: "bg-emerald-950 text-emerald-300 border-emerald-800",
  SUSPENDED: "bg-amber-950 text-amber-300 border-amber-800",
  DECOMMISSIONED: "bg-zinc-800 text-zinc-400 border-zinc-700",
  CRITICAL: "bg-red-950 text-red-300 border-red-800",
  WARNING: "bg-amber-950 text-amber-300 border-amber-800",
  INFO: "bg-blue-950 text-blue-300 border-blue-800",
  UP: "bg-emerald-950 text-emerald-300 border-emerald-800",
  DOWN: "bg-red-950 text-red-300 border-red-800",
  UNKNOWN: "bg-zinc-800 text-zinc-400 border-zinc-700",
};

const DEFAULT_STYLE = "bg-zinc-800 text-zinc-300 border-zinc-700";

interface StateBadgeProps {
  state: string;
  className?: string;
}

export function StateBadge({ state, className = "" }: StateBadgeProps) {
  const style = STATE_STYLES[state.toUpperCase()] ?? DEFAULT_STYLE;
  return (
    <span
      className={`inline-flex items-center text-xs font-medium px-2 py-0.5 rounded border ${style} ${className}`}
    >
      {state}
    </span>
  );
}
