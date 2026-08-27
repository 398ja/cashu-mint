import {
  createContext,
  useCallback,
  useEffect,
  useState,
  type ReactNode,
} from "react";
import { fetchAuthMe } from "@/api/auth";

export interface AuthState {
  roles: string[];
  authenticated: boolean;
  loading: boolean;
}

export interface AuthContextValue extends AuthState {
  refresh: () => Promise<boolean>;
  logout: () => void;
  hasRole: (role: string) => boolean;
}

export const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({
    roles: [],
    authenticated: false,
    loading: true,
  });

  // The session cookie is the credential and the server is the only reader of
  // it, so the browser asks who it is rather than keeping its own copy.
  const refresh = useCallback(async () => {
    const me = await fetchAuthMe();
    setState({ roles: me.roles, authenticated: me.authenticated, loading: false });
    return me.authenticated;
  }, []);

  // The cookie is the credential, so clearing local state alone leaves the operator
  // signed in and the next page load walks them straight back to the dashboard.
  const logout = useCallback(() => {
    void fetch("/api/v1/auth/logout", { method: "POST" }).catch(() => undefined);
    setState({ roles: [], authenticated: false, loading: false });
  }, []);

  const hasRole = useCallback(
    (role: string) =>
      state.roles.some((r) => r.toUpperCase() === role.toUpperCase()),
    [state.roles],
  );

  useEffect(() => {
    refresh().catch(() =>
      setState({ roles: [], authenticated: false, loading: false }),
    );
  }, [refresh]);

  return (
    <AuthContext.Provider value={{ ...state, refresh, logout, hasRole }}>
      {children}
    </AuthContext.Provider>
  );
}
