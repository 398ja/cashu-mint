import { useEffect, useState, type FormEvent } from "react";
import { createPrivateKeySessionSigner } from "@imani/nap-client-web";
import { adminKeyStore, InvalidKeyError, toPrivateKeyHex } from "@/auth/keySigner";
import { useAuth } from "@/auth/useAuth";
import { describeSignInError } from "./LoginPage";

/** The two enrolment boxes disagree, so there is nothing to encrypt the key under. */
export class PassphraseMismatchError extends Error {
  constructor() {
    super("The two passphrases do not match.");
    this.name = "PassphraseMismatchError";
  }
}

/** Wrong passphrase, unusable key, or a failed handshake -- one message each. */
export function describeKeySignInError(error: unknown): string {
  if (error instanceof PassphraseMismatchError) {
    return error.message;
  }
  if (error instanceof InvalidKeyError) {
    return error.message;
  }
  // What the key store throws for a passphrase that will not decrypt the record.
  if (error instanceof Error && error.message === "Invalid passphrase") {
    return "Incorrect passphrase. Your stored key is unchanged.";
  }
  return describeSignInError(error);
}

/**
 * Sign in with a private key held in this browser, encrypted under a passphrase.
 * Enrolment and sign-in are one form: which one it is depends only on whether a
 * key is already stored here.
 */
export function KeySignIn({ onSignedIn }: { onSignedIn: () => void }) {
  const { signIn } = useAuth();
  const [enrolled, setEnrolled] = useState(false);
  const [privateKey, setPrivateKey] = useState("");
  const [passphrase, setPassphrase] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    adminKeyStore.hasKey().then(setEnrolled).catch(() => setEnrolled(false));
  }, []);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (!enrolled) {
        // A typo here encrypts the key under a passphrase nobody knows, and the
        // key is only in this browser -- so it is caught before the save, not
        // on the next visit when nothing can be done about it.
        if (passphrase !== confirmation) {
          throw new PassphraseMismatchError();
        }
        await adminKeyStore.save(toPrivateKeyHex(privateKey), passphrase);
      }
      // Always read the key back out of the store rather than reusing what was
      // typed: an enrolment that cannot be decrypted again fails here, not on
      // the Operator's next visit, and this is the only path that has the key.
      const hex = await adminKeyStore.loadKey(passphrase);
      await signIn(createPrivateKeySessionSigner(hex), adminKeyStore);
      setPrivateKey("");
      setPassphrase("");
      setConfirmation("");
      onSignedIn();
    } catch (e) {
      setError(describeKeySignInError(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={(e) => void handleSubmit(e)} className="space-y-3">
      {error && (
        <div
          role="alert"
          className="rounded border border-red-900/50 bg-red-950/30 p-3 text-sm text-red-300"
        >
          {error}
        </div>
      )}

      {!enrolled && (
        <div className="space-y-1">
          <label htmlFor="private-key" className="block text-sm text-zinc-400">
            Private key
          </label>
          <input
            id="private-key"
            type="password"
            autoComplete="off"
            value={privateKey}
            onChange={(e) => setPrivateKey(e.target.value)}
            placeholder="nsec1..."
            className="w-full rounded-md border border-zinc-700 bg-zinc-950 px-3 py-2 text-sm text-zinc-100"
          />
        </div>
      )}

      <div className="space-y-1">
        <label htmlFor="passphrase" className="block text-sm text-zinc-400">
          Passphrase
        </label>
        <input
          id="passphrase"
          type="password"
          autoComplete={enrolled ? "current-password" : "new-password"}
          value={passphrase}
          onChange={(e) => setPassphrase(e.target.value)}
          className="w-full rounded-md border border-zinc-700 bg-zinc-950 px-3 py-2 text-sm text-zinc-100"
        />
      </div>

      {!enrolled && (
        <div className="space-y-1">
          <label htmlFor="passphrase-confirmation" className="block text-sm text-zinc-400">
            Confirm passphrase
          </label>
          <input
            id="passphrase-confirmation"
            type="password"
            autoComplete="new-password"
            value={confirmation}
            onChange={(e) => setConfirmation(e.target.value)}
            className="w-full rounded-md border border-zinc-700 bg-zinc-950 px-3 py-2 text-sm text-zinc-100"
          />
        </div>
      )}

      <button
        type="submit"
        disabled={busy}
        className="w-full rounded-md border border-zinc-700 px-3 py-2 text-sm text-zinc-200 hover:bg-zinc-800 disabled:opacity-50 transition-colors"
      >
        {enrolled ? "Sign in with stored key" : "Encrypt key and sign in"}
      </button>
    </form>
  );
}
