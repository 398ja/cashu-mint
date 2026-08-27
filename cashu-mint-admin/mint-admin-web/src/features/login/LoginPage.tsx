import { useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { AuthRequestError, Nip07Error, createNip07Signer } from "@imani/nap-client-web";
import { useNip07 } from "@imani/nap-react";
import { useAuth } from "@/auth/useAuth";
import { Shield } from "lucide-react";

/**
 * One message per way a sign-in can fail. Extracted because the mapping is the
 * only part of this page with a decision in it.
 */
export function describeSignInError(error: unknown): string {
  if (error instanceof Nip07Error) {
    switch (error.code) {
      case "NOT_AVAILABLE":
        return "No signing extension is available. Install one and try again.";
      case "DECLINED":
        return "Your signing extension declined the request.";
      case "TIMEOUT":
        return "Your signing extension did not answer in time.";
      default:
        return "Your signing extension could not complete the request.";
    }
  }
  if (error instanceof AuthRequestError && error.terminal) {
    // Every refusal is the same 401 by design, so this cannot name the reason.
    // Of the reasons there are, an unprovisioned npub is the one an Operator
    // can do something about.
    return "This mint did not accept that key. If you are new, ask a Super "
      + "Administrator to provision an Operator profile for your npub.";
  }
  return "Sign-in failed. Please try again.";
}

export function LoginPage() {
  const { refresh, signIn } = useAuth();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const expired = searchParams.get("expired") === "true";
  const { status, provider, retry } = useNip07();
  const [error, setError] = useState<string | null>(null);
  const [signingIn, setSigningIn] = useState(false);

  // A session may already be open -- the cookie outlives this page.
  useEffect(() => {
    refresh().then((ok) => {
      if (ok) navigate("/dashboard", { replace: true });
    });
  }, [refresh, navigate]);

  async function handleSignIn() {
    if (!provider) return;
    setSigningIn(true);
    setError(null);
    try {
      await signIn(createNip07Signer(provider));
      navigate("/dashboard", { replace: true });
    } catch (e) {
      setError(describeSignInError(e));
    } finally {
      setSigningIn(false);
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center p-4">
      <div className="w-full max-w-sm">
        <div className="flex items-center justify-center gap-2 mb-8">
          <Shield className="h-8 w-8 text-zinc-400" />
          <h1 className="text-2xl font-bold text-zinc-100">Cashu Mint Admin</h1>
        </div>

        <div className="rounded-lg border border-zinc-800 bg-zinc-900 p-6 space-y-4">
          {expired && (
            <div className="rounded border border-red-900/50 bg-red-950/30 p-3 text-sm text-red-300">
              Session expired. Please sign in again.
            </div>
          )}
          <p className="text-sm text-zinc-300">Sign in with your Nostr key.</p>

          {error && (
            <div
              role="alert"
              className="rounded border border-red-900/50 bg-red-950/30 p-3 text-sm text-red-300"
            >
              {error}
            </div>
          )}

          {status === "detecting" && (
            <p className="text-sm text-zinc-500">Looking for a signing extension...</p>
          )}

          {status === "absent" && (
            <div className="space-y-3">
              <p className="text-sm text-zinc-500">
                No signing extension found. Install a NIP-07 browser extension,
                then check again.
              </p>
              <button
                onClick={retry}
                className="w-full rounded-md border border-zinc-700 px-3 py-2 text-sm text-zinc-200 hover:bg-zinc-800 transition-colors"
              >
                Check again
              </button>
            </div>
          )}

          {status === "present" && (
            <button
              onClick={() => void handleSignIn()}
              disabled={signingIn}
              className="w-full rounded-md bg-zinc-100 px-3 py-2 text-sm font-medium text-zinc-900 hover:bg-white disabled:opacity-50 transition-colors"
            >
              {signingIn ? "Signing in..." : "Sign in with extension"}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
