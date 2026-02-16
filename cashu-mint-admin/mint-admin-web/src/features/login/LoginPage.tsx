import { useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { useAuth } from "@/auth/useAuth";
import { Shield } from "lucide-react";

export function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const expired = searchParams.get("expired") === "true";

  const [token, setToken] = useState("");
  const [roles, setRoles] = useState("MINT_ADMIN,USER_ADMIN,ALERTS_ADMIN,OPS_ADMIN");
  const [error, setError] = useState<string | null>(
    expired ? "Session expired. Please log in again." : null,
  );
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const ok = await login(token, roles);
      if (ok) {
        navigate("/dashboard", { replace: true });
      } else {
        setError("Authentication failed. Check your token.");
      }
    } catch {
      setError("Connection failed. Is the backend running?");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center p-4">
      <div className="w-full max-w-sm">
        <div className="flex items-center justify-center gap-2 mb-8">
          <Shield className="h-8 w-8 text-zinc-400" />
          <h1 className="text-2xl font-bold text-zinc-100">
            Cashu Mint Admin
          </h1>
        </div>

        <form
          onSubmit={handleSubmit}
          className="rounded-lg border border-zinc-800 bg-zinc-900 p-6 space-y-4"
        >
          {error && (
            <div className="rounded border border-red-900/50 bg-red-950/30 p-3 text-sm text-red-300">
              {error}
            </div>
          )}

          <div>
            <label
              htmlFor="token"
              className="block text-sm font-medium text-zinc-300 mb-1"
            >
              Admin Token
            </label>
            <input
              id="token"
              type="password"
              value={token}
              onChange={(e) => setToken(e.target.value)}
              required
              autoFocus
              className="w-full rounded border border-zinc-700 bg-zinc-800 px-3 py-2 text-sm text-zinc-100 placeholder:text-zinc-500 focus:border-zinc-600 focus:outline-none focus:ring-1 focus:ring-zinc-600"
              placeholder="Enter your admin token"
            />
          </div>

          <div>
            <label
              htmlFor="roles"
              className="block text-sm font-medium text-zinc-300 mb-1"
            >
              Roles (comma-separated)
            </label>
            <input
              id="roles"
              type="text"
              value={roles}
              onChange={(e) => setRoles(e.target.value)}
              className="w-full rounded border border-zinc-700 bg-zinc-800 px-3 py-2 text-sm text-zinc-100 placeholder:text-zinc-500 focus:border-zinc-600 focus:outline-none focus:ring-1 focus:ring-zinc-600"
              placeholder="MINT_ADMIN,USER_ADMIN"
            />
          </div>

          <button
            type="submit"
            disabled={!token || loading}
            className="w-full rounded bg-zinc-100 px-4 py-2 text-sm font-medium text-zinc-900 hover:bg-zinc-200 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {loading ? "Authenticating..." : "Sign In"}
          </button>
        </form>
      </div>
    </div>
  );
}
