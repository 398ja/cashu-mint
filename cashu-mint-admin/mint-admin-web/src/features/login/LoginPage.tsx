import { useEffect } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { useAuth } from "@/auth/useAuth";
import { Shield } from "lucide-react";

export function LoginPage() {
  const { refresh } = useAuth();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const expired = searchParams.get("expired") === "true";

  // A session may already be open -- the cookie outlives this page.
  useEffect(() => {
    refresh().then((ok) => {
      if (ok) navigate("/dashboard", { replace: true });
    });
  }, [refresh, navigate]);

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
          <p className="text-sm text-zinc-300">
            Sign in with your Nostr key.
          </p>
          <p className="text-sm text-zinc-500">
            The signing flow is not wired into this page yet; the API accepts a
            NAP handshake today.
          </p>
        </div>
      </div>
    </div>
  );
}
