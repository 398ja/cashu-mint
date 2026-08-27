import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { useAuth } from "@/auth/useAuth";
import { LockScreen } from "@/auth/LockScreen";
import {
  LayoutDashboard,
  Server,
  Users,
  ScrollText,
  LogOut,
  Shield,
} from "lucide-react";

interface NavItem {
  label: string;
  to: string;
  icon: React.ReactNode;
  role?: string;
  permission?: string;
}

const NAV_ITEMS: NavItem[] = [
  {
    label: "Dashboard",
    to: "/dashboard",
    icon: <LayoutDashboard className="h-4 w-4" />,
  },
  {
    label: "Mints",
    to: "/mints",
    icon: <Server className="h-4 w-4" />,
    role: "MINT_ADMIN",
  },
  {
    label: "Users",
    to: "/users",
    icon: <Users className="h-4 w-4" />,
    permission: "users:manage",
  },
  {
    label: "Audit Log",
    to: "/audit",
    icon: <ScrollText className="h-4 w-4" />,
  },
];

export function Layout() {
  const { hasRole, hasPermission, roles, npub, locked, logout } = useAuth();
  const navigate = useNavigate();

  const visibleItems = NAV_ITEMS.filter(
    (item) =>
      (!item.role || hasRole(item.role)) &&
      (!item.permission || hasPermission(item.permission)),
  );

  return (
    <div className="flex h-screen">
      {locked && <LockScreen />}
      <nav className="w-56 shrink-0 border-r border-zinc-800 bg-zinc-900/50 flex flex-col">
        <div className="flex items-center gap-2 px-4 py-4 border-b border-zinc-800">
          <Shield className="h-5 w-5 text-zinc-400" />
          <span className="font-semibold text-sm text-zinc-200">
            Mint Admin
          </span>
        </div>

        <div className="flex-1 py-2 space-y-0.5 px-2">
          {visibleItems.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                `flex items-center gap-2.5 rounded-md px-3 py-2 text-sm transition-colors ${
                  isActive
                    ? "bg-zinc-800 text-zinc-100"
                    : "text-zinc-400 hover:text-zinc-200 hover:bg-zinc-800/50"
                }`
              }
            >
              {item.icon}
              {item.label}
            </NavLink>
          ))}
        </div>

        <div className="border-t border-zinc-800 p-3">
          {npub && (
            <div
              className="text-xs text-zinc-300 mb-1 px-1 truncate"
              title={npub}
            >
              {npub}
            </div>
          )}
          <div className="text-xs text-zinc-500 mb-2 px-1">
            {roles.join(", ")}
          </div>
          <button
            onClick={() => {
              logout();
              navigate("/login", { replace: true });
            }}
            className="flex items-center gap-2 w-full rounded-md px-3 py-2 text-sm text-zinc-400 hover:text-zinc-200 hover:bg-zinc-800/50 transition-colors"
          >
            <LogOut className="h-4 w-4" />
            Sign Out
          </button>
        </div>
      </nav>

      <main className="flex-1 overflow-y-auto p-6">
        <Outlet />
      </main>
    </div>
  );
}
