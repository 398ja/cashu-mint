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

    // Ids the caller did not name are still caught by shape: the gateway lookup URL Spring quotes in
    // an I/O error, a JSON quote member and a quote status path. Already-redacted text is left alone.
    @Test
    void redactsAnyIdInTheKnownShapesWithoutBeingTold() {
        // Arrange
        String other = "0b6b3a8e-2f4c-4d0e-9a51-7c3f1e2d4b6a";
        String text = "I/O error on GET request for \"http://gw/search/findByQuoteId?quoteId=" + other + "&x=1\""
                + " body {\"quote\":\"" + other + "\"} at /v1/mint/quote/bolt11/" + other + "/";

        // Act
        String redacted = QuoteRef.redact(text);

        // Assert
        String ref = QuoteRef.of(other);
        assertThat(redacted).doesNotContain(other)
                .contains("quoteId=" + ref + "&x=1", "\"quote\":\"" + ref + "\"", "/quote/bolt11/" + ref + "/");
        assertThat(QuoteRef.redact(redacted)).isEqualTo(redacted);
    }

    // An id in a suppressed exception, for example from a failed close in try-with-resources, is
    // redacted too, and the suppressed exception is kept rather than dropped.
    @Test
    void keepsAndRedactsSuppressedExceptions() {
        // Arrange
        IllegalStateException error = new IllegalStateException("mint failed");
        error.addSuppressed(new IOException("close failed for quoteId=" + QUOTE_ID));

        // Act
        Throwable redacted = QuoteRef.redact(error);

        // Assert
        assertThat(redacted.getSuppressed()).singleElement().satisfies(suppressed -> assertThat(suppressed.getMessage())
                .isEqualTo("java.io.IOException: close failed for quoteId=q:ef738801934d"));
    }

    // A chain deeper than 16 links used to be cut there, silently dropping its root cause. Every
    // link of a realistic deep chain is kept, and the id is gone from the deepest one too.
    @Test
    void keepsEveryLinkOfADeepChain() {
        // Arrange
        Throwable chain = new IOException("root for quoteId=" + QUOTE_ID);
        for (int i = 0; i < 40; i++) {
            chain = new IllegalStateException("wrap " + i, chain);
        }

        // Act
        Throwable redacted = QuoteRef.redact(chain);

        // Assert
        int links = 0;
        Throwable deepest = redacted;
        for (Throwable link = redacted; link != null; link = link.getCause()) {
            links++;
            deepest = link;
        }
        assertThat(links).isEqualTo(41);
        assertThat(deepest.getMessage()).isEqualTo("java.io.IOException: root for quoteId=q:ef738801934d");
    }

    // A cause chain that loops back on itself is redacted without looping forever.
    @Test
    void cutsACyclicChainWhereItWouldRepeat() {
        // Arrange
        IllegalStateException first = new IllegalStateException("a " + QUOTE_ID);
        IllegalArgumentException second = new IllegalArgumentException("b", first);
        first.initCause(second);

        // Act
        Throwable redacted = QuoteRef.redact(first, QUOTE_ID);

        // Assert
        assertThat(redacted.getMessage()).doesNotContain(QUOTE_ID);
        assertThat(redacted.getCause().getCause()).isNull();
    }
}
