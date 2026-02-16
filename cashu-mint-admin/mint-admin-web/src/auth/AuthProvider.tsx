import {
  createContext,
  useCallback,
  useEffect,
  useState,
  type ReactNode,
} from "react";
import { fetchAuthMe } from "@/api/auth";

export interface AuthState {
  token: string | null;
  roles: string[];
  authenticated: boolean;
  loading: boolean;
}

export interface AuthContextValue extends AuthState {
  login: (token: string, roles: string) => Promise<boolean>;
  logout: () => void;
  hasRole: (role: string) => boolean;
}

export const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({
    token: sessionStorage.getItem("admin_token"),
    roles: (sessionStorage.getItem("admin_roles") ?? "")
      .split(",")
      .filter(Boolean),
    authenticated: !!sessionStorage.getItem("admin_token"),
    loading: true,
  });

  const login = useCallback(async (token: string, roles: string) => {
    sessionStorage.setItem("admin_token", token);
    sessionStorage.setItem("admin_roles", roles);
    try {
      const me = await fetchAuthMe();
      const resolvedRoles = me.roles.length > 0 ? me.roles : roles.split(",").filter(Boolean);
      sessionStorage.setItem("admin_roles", resolvedRoles.join(","));
      setState({
        token,
        roles: resolvedRoles,
        authenticated: me.authenticated,
        loading: false,
      });
      return me.authenticated;
    } catch {
      sessionStorage.removeItem("admin_token");
      sessionStorage.removeItem("admin_roles");
      setState({ token: null, roles: [], authenticated: false, loading: false });
      return false;
    }
  }, []);

  const logout = useCallback(() => {
    sessionStorage.removeItem("admin_token");
    sessionStorage.removeItem("admin_roles");
    setState({ token: null, roles: [], authenticated: false, loading: false });
  }, []);

  const hasRole = useCallback(
    (role: string) =>
      state.roles.some((r) => r.toUpperCase() === role.toUpperCase()),
    [state.roles],
  );

  useEffect(() => {
    if (!state.token) {
      setState((s) => ({ ...s, loading: false }));
      return;
    }
    fetchAuthMe()
      .then((me) => {
        const roles = me.roles.length > 0
          ? me.roles
          : (sessionStorage.getItem("admin_roles") ?? "").split(",").filter(Boolean);
        setState({
          token: state.token,
          roles,
          authenticated: me.authenticated,
          loading: false,
        });
      })
      .catch(() => {
        sessionStorage.removeItem("admin_token");
        sessionStorage.removeItem("admin_roles");
        setState({ token: null, roles: [], authenticated: false, loading: false });
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <AuthContext.Provider value={{ ...state, login, logout, hasRole }}>
      {children}
    </AuthContext.Provider>
  );
}
