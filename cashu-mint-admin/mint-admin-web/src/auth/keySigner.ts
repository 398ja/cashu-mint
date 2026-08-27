import { createWebCryptoKeyStore } from "@imani/nap-client-web";
import { bytesToHex } from "@imani/nap-core";
// Arrives with the vendored NAP packages, which sign with it.
import { nip19 } from "nostr-tools";

/**
 * How long an enrolled key survives without the Operator touching anything.
 *
 * Set to the server's own idle session TTL (`nap.session-idle-ttl-seconds=900`
 * in mint-admin-rest's nap-defaults.properties): evicting sooner asks for the
 * passphrase while the session is still good, and evicting later means a
 * passphrase prompt followed by a sign-in prompt a moment afterwards.
 */
export const IDLE_LOCK_MS = 15 * 60 * 1000;

/** The enrolled key, encrypted at rest under the Operator's passphrase. */
export const adminKeyStore = createWebCryptoKeyStore("cashu-admin-key");

/** Thrown for input that is not a private key, so the form can say which field. */
export class InvalidKeyError extends Error {}

/** Accepts what an Operator actually holds -- an nsec -- as well as raw hex. */
export function toPrivateKeyHex(input: string): string {
  const value = input.trim();
  if (/^[0-9a-f]{64}$/i.test(value)) {
    return value.toLowerCase();
  }
  try {
    const decoded = nip19.decode(value);
    if (decoded.type === "nsec") {
      return bytesToHex(decoded.data);
    }
  } catch {
    // Gibberish and a well-formed npub are the same answer to the Operator.
  }
  throw new InvalidKeyError(
    "That is not a private key. Paste an nsec, or 64 hex characters.",
  );
}
