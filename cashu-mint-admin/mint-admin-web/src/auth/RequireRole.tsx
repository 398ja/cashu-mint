import type { ReactNode } from "react";
import { useAuth } from "./useAuth";

interface RequireRoleProps {
  /** Gate on a role. Mutually exclusive with `permission`. */
  role?: string;
  /** Gate on a permission, for a page more than one role may reach. */
  permission?: string;
  children: ReactNode;
}

export function RequireRole({ role, permission, children }: RequireRoleProps) {
  const { hasRole, hasPermission, roles } = useAuth();
  const granted = permission ? hasPermission(permission) : hasRole(role ?? "");

  if (!granted) {
    return (
      <div className="flex items-center justify-center min-h-[60vh]">
        <div className="rounded-lg border border-zinc-800 bg-zinc-900 p-8 text-center max-w-md">
          <h2 className="text-lg font-semibold text-zinc-100 mb-2">
            Access Denied
          </h2>
          <p className="text-sm text-zinc-400">
            This page requires the{" "}
            <span className="font-mono text-amber-400">
              {permission ?? role}
            </span>{" "}
            {permission ? "permission" : "role"}.
          </p>
          <p className="text-sm text-zinc-500 mt-2">
            Your current roles:{" "}
            {roles.length > 0
              ? roles.map((r) => (
                  <span
                    key={r}
                    className="inline-block font-mono text-xs bg-zinc-800 rounded px-1.5 py-0.5 ml-1"
                  >
                    {r}
                  </span>
                ))
              : "none"}
          </p>
        </div>
      </div>
    );
  }

  return <>{children}</>;
}
