import {
  createContext,
  useCallback,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { createNapSession } from "@imani/nap-client-web";
import type { KeyStore, NapSession, SessionSigner } from "@imani/nap-client-web";
import { IDLE_LOCK_MS } from "@/auth/keySigner";
import { fetchAuthMe } from "@/api/auth";

export interface AuthState {
  roles: string[];
  npub: string | null;
  authenticated: boolean;
  loading: boolean;
  /** The signing key was evicted after an idle spell; the cookie is still good. */
  locked: boolean;
}

export interface AuthContextValue extends AuthState {
  signIn: (signer: SessionSigner, keyStore?: KeyStore) => Promise<void>;
  unlock: (passphrase: string) => Promise<void>;
  refresh: () => Promise<boolean>;
  logout: () => void;
  hasRole: (role: string) => boolean;
}

const SIGNED_OUT: AuthState = {
  roles: [],
  npub: null,
  authenticated: false,
  loading: false,
  locked: false,
};

export const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ ...SIGNED_OUT, loading: true });

  // The session that did the signing, kept so sign-out ends that one rather than
  // just the cookie -- a NAP session also holds key material for signers that
  // have any, and dropping the reference would leave it live in the page.
  const sessionRef = useRef<NapSession | null>(null);

  // The session cookie is the credential and the server is the only reader of
  // it, so the browser asks who it is rather than keeping its own copy.
  const refresh = useCallback(async () => {
    const me = await fetchAuthMe();
    setState({
      roles: me.roles,
      npub: me.npub,
      authenticated: me.authenticated,
      loading: false,
      locked: false,
    });
    return me.authenticated;
  }, []);

  // Whoever built the signer decides what kind it is; this only runs the
  // handshake with it. Errors propagate: the login page is what knows how to
  // word a refusal, and a failed sign-in must not look like a signed-in state.
  const signIn = useCallback(async (signer: SessionSigner, keyStore?: KeyStore) => {
    // The idle lock is armed only for a signer that keeps a key in this page.
    // An extension or a remote signer holds its own key and evicting nothing
    // would prompt for a passphrase that unlocks nothing.
    const session = createNapSession({
      baseUrl: "/api/v1",
      signer,
      ...(keyStore
        ? { keyStore, autoLock: { enabled: true, timeoutMs: IDLE_LOCK_MS } }
        : {}),
      onLock: () => setState((s) => ({ ...s, locked: true })),
    });
    await session.login();
    sessionRef.current = session;
    const current = session.getSession();
    setState({
      roles: current?.roles ?? [],
      npub: current?.npub ?? null,
      authenticated: true,
      loading: false,
      locked: false,
    });
  }, []);

  // Only the key was evicted, so putting it back is the whole of the recovery --
  // no second handshake, and a wrong passphrase leaves the stored key untouched.
  const unlock = useCallback(async (passphrase: string) => {
    const session = sessionRef.current;
    if (!session) {
      throw new Error("no session to unlock");
    }
    await session.reunlock(passphrase);
    setState((s) => ({ ...s, locked: false }));
  }, []);

  // The cookie is the credential, so clearing local state alone leaves the operator
  // signed in and the next page load walks them straight back to the dashboard.
  const logout = useCallback(() => {
    const session = sessionRef.current;
    sessionRef.current = null;
    const ended = session
      ? session.logout()
      : fetch("/api/v1/auth/logout", { method: "POST" });
    void Promise.resolve(ended).catch(() => undefined);
    setState(SIGNED_OUT);
  }, []);

  const hasRole = useCallback(
    (role: string) =>
      state.roles.some((r) => r.toUpperCase() === role.toUpperCase()),
    [state.roles],
  );

  useEffect(() => {
    refresh().catch(() => setState(SIGNED_OUT));
  }, [refresh]);

  return (
    <AuthContext.Provider
      value={{ ...state, signIn, unlock, refresh, logout, hasRole }}
    >
      {children}
    </AuthContext.Provider>
  );
}
