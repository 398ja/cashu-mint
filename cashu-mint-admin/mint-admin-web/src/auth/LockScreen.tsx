import { useState, type FormEvent } from "react";
import { ReunlockError } from "@imani/nap-client-web";
import { useAuth } from "@/auth/useAuth";

/** One message per way the stored key can refuse to come back. */
export function describeUnlockError(error: unknown): string {
  if (error instanceof ReunlockError) {
    switch (error.code) {
      case "INVALID_PASSPHRASE":
        return "Incorrect passphrase. Your stored key is unchanged.";
      case "NO_STORED_KEY":
        return "No key is stored in this browser. Sign out and sign in again.";
      default:
        return "This browser would not let the stored key be read.";
    }
  }
  return "Could not unlock. Please try again.";
}

/**
 * Shown once the idle lock has evicted the key. The session itself is still
 * open -- this asks for the passphrase, not for a fresh sign-in.
 */
export function LockScreen() {
  const { unlock } = useAuth();
  const [passphrase, setPassphrase] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await unlock(passphrase);
      setPassphrase("");
    } catch (e) {
      setError(describeUnlockError(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-zinc-950/90 p-4">
      <form
        onSubmit={(e) => void handleSubmit(e)}
        role="dialog"
        aria-label="Session locked"
        className="w-full max-w-sm space-y-3 rounded-lg border border-zinc-800 bg-zinc-900 p-6"
      >
        <h2 className="text-lg font-semibold text-zinc-100">Session locked</h2>
        <p className="text-sm text-zinc-400">
          Your key was removed from this browser after a spell of inactivity.
          Enter your passphrase to carry on.
        </p>

        {error && (
          <div
            role="alert"
            className="rounded border border-red-900/50 bg-red-950/30 p-3 text-sm text-red-300"
          >
            {error}
          </div>
        )}

        <label htmlFor="unlock-passphrase" className="block text-sm text-zinc-400">
          Passphrase
        </label>
        <input
          id="unlock-passphrase"
          type="password"
          autoComplete="current-password"
          value={passphrase}
          onChange={(e) => setPassphrase(e.target.value)}
          className="w-full rounded-md border border-zinc-700 bg-zinc-950 px-3 py-2 text-sm text-zinc-100"
        />
        <button
          type="submit"
          disabled={busy}
          className="w-full rounded-md bg-zinc-100 px-3 py-2 text-sm font-medium text-zinc-900 hover:bg-white disabled:opacity-50 transition-colors"
        >
          Unlock
        </button>
      </form>
    </div>
  );
}
