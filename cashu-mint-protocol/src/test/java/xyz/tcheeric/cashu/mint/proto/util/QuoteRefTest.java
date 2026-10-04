package xyz.tcheeric.cashu.mint.proto.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class QuoteRefTest {

    private static final String QUOTE_ID = "9d745270-1405-46de-b5c5-e2762b4f5e00";

    // The ref must be byte-for-byte the one imani-wallet-lib and imani-gateway-customer log, so the
    // expected value is the same test vector wallet-lib's QuoteRefTest pins (computed with hashlib).
    @Test
    void refMatchesTheWalletLibAndGatewayFormat() {
        // Act
        String ref = QuoteRef.of(QUOTE_ID);

        // Assert
        assertThat(ref).isEqualTo("q:ef738801934d");
    }

    // A missing id logs as a fixed placeholder rather than as the hash of nothing, as in wallet-lib.
    @Test
    void aMissingIdHasAPlaceholder() {
        // Act and Assert
        assertThat(QuoteRef.of(null)).isEqualTo("(none)");
        assertThat(QuoteRef.of("")).isEqualTo("(none)");
    }

    // Text the mint did not compose, such as an idempotency key built from the id, carries the ref instead.
    @Test
    void redactsEveryLiteralOccurrenceInText() {
        // Act
        String redacted = QuoteRef.redact("bolt11:" + QUOTE_ID + " /quote/bolt11/" + QUOTE_ID, QUOTE_ID);

        // Assert
        assertThat(redacted).doesNotContain(QUOTE_ID).isEqualTo("bolt11:q:ef738801934d /quote/bolt11/q:ef738801934d");
    }

    // An exception chain that quotes the id anywhere is rebuilt without it, keeping the original
    // type in the message and the original stack trace, so it can still be logged with its trace.
    @Test
    void redactsTheIdFromEveryMessageInACauseChain() {
        // Arrange
        IOException root = new IOException("404 on GET /search/findByQuoteId?quoteId=" + QUOTE_ID);
        IllegalStateException outer = new IllegalStateException("lookup failed", root);

        // Act
        Throwable redacted = QuoteRef.redact(outer, QUOTE_ID);

        // Assert
        assertThat(redacted.getMessage()).isEqualTo("java.lang.IllegalStateException: lookup failed");
        assertThat(redacted.getCause().getMessage())
                .isEqualTo("java.io.IOException: 404 on GET /search/findByQuoteId?quoteId=q:ef738801934d");
        assertThat(redacted.getStackTrace()).isEqualTo(outer.getStackTrace());
        assertThat(redacted.getCause().getStackTrace()).isEqualTo(root.getStackTrace());
    }

    // An exception that never names the id is passed through untouched, so its real type still prints.
    @Test
    void leavesAChainThatNeverNamesTheIdAlone() {
        // Arrange
        IllegalStateException error = new IllegalStateException("database unavailable");

        // Act and Assert
        assertThat(QuoteRef.redact(error, QUOTE_ID)).isSameAs(error);
        assertThat(QuoteRef.redact((Throwable) null, QUOTE_ID)).isNull();
    }
}
