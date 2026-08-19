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
  login: (token: string) => Promise<boolean>;
  logout: () => void;
  hasRole: (role: string) => boolean;
}

export const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({
    token: sessionStorage.getItem("admin_token"),
    roles: [],
    authenticated: !!sessionStorage.getItem("admin_token"),
    loading: true,
  });

  // Roles are whatever the server says this credential holds; the caller does
  // not get to assert them, here or anywhere else. See ADR-0005.
  const login = useCallback(async (token: string) => {
    sessionStorage.setItem("admin_token", token);
    try {
      const me = await fetchAuthMe();
      setState({
        token,
        roles: me.roles,
        authenticated: me.authenticated,
        loading: false,
      });
      return me.authenticated;
    } catch {
      sessionStorage.removeItem("admin_token");
      setState({ token: null, roles: [], authenticated: false, loading: false });
      return false;
    }
  }, []);

  const logout = useCallback(() => {
    sessionStorage.removeItem("admin_token");
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
        setState({
          token: state.token,
          roles: me.roles,
          authenticated: me.authenticated,
          loading: false,
        });
      })
      .catch(() => {
        sessionStorage.removeItem("admin_token");
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
