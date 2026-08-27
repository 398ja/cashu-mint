package xyz.tcheeric.cashu.mint.admin.rest.nap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NpubsTest {

    private static final String NPUB =
        "npub10xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqpkge6d";
    private static final String PUBKEY =
        "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";

    // Checks the conversion every lookup depends on.
    @Test
    @DisplayName("An npub decodes to lower-case hex")
    void decodesToHex() {
        assertThat(Npubs.toPubkeyHex(NPUB)).isEqualTo(PUBKEY);
    }

    // bech32 is defined as all-lower or all-upper, so an upper-cased npub is the same key
    // and must resolve to the same operator rather than being refused as malformed.
    @Test
    @DisplayName("An upper-case npub decodes to the same key")
    void decodesUpperCase() {
        assertThat(Npubs.toPubkeyHex(NPUB.toUpperCase())).isEqualTo(PUBKEY);
    }

    // The message reaches a 400 body, so it must not echo back what the caller sent.
    @Test
    @DisplayName("A rejection does not echo the caller's input")
    void rejectionDoesNotEchoInput() {
        assertThatThrownBy(() -> Npubs.toPubkeyHex("npub1<script>"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("not a valid npub");
    }

    // Anything that is not an npub is refused, including null and the bare hex form.
    @Test
    @DisplayName("Non-npub input is refused")
    void refusesNonNpub() {
        assertThatThrownBy(() -> Npubs.toPubkeyHex(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Npubs.toPubkeyHex(PUBKEY)).isInstanceOf(IllegalArgumentException.class);
    }
}
