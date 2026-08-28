// Arrives with the vendored NAP packages, which decode with it.
import { nip19 } from "nostr-tools";

/**
 * Whether a value is an npub, checksum and all. Checked here so a mistyped key
 * is answered in the form rather than as a 400 from the create call.
 */
export function isNpub(value: string): boolean {
  try {
    return nip19.decode(value.trim()).type === "npub";
  } catch {
    return false;
  }
}
