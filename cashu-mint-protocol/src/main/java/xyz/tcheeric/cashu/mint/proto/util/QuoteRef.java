package xyz.tcheeric.cashu.mint.proto.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A loggable stand-in for a quote id (cashu-mint#531).
 *
 * <p>A NUT-04 quote id is a bearer claim: whoever holds the id of a paid quote that is not NUT-20
 * locked can mint its ecash. Logs are shipped and kept, so a raw id in a log line or a logged
 * exception hands that claim to every reader. A short hash still ties one quote's lines together.
 *
 * <p>The ref is {@code q:} followed by the first 6 bytes of SHA-256 over the UTF-8 id, in lowercase
 * hex. That is exactly what imani-wallet-lib's {@code QuoteRef} and imani-gateway-customer log as
 * {@code quote_ref}, so one quote can be followed across the mint, the gateway and the wallet.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class QuoteRef {

    /** What a missing id logs as, rather than a hash of nothing. Same placeholder as wallet-lib. */
    public static final String NO_QUOTE = "(none)";

    private static final String PREFIX = "q:";
    private static final int REF_BYTES = 6;

    /** Deep enough for any real cause chain, and a stop for a cyclic one. */
    private static final int MAX_CAUSE_DEPTH = 16;

    /** The ref for a quote id, safe to log; {@code (none)} when there is no id. */
    public static String of(String quoteId) {
        if (quoteId == null || quoteId.isEmpty()) {
            return NO_QUOTE;
        }
        return PREFIX + HexFormat.of().formatHex(sha256(quoteId), 0, REF_BYTES);
    }

    /**
     * {@code text} with every literal occurrence of {@code quoteId} replaced by its ref. For text the
     * mint does not compose itself, such as an exception message that quotes a request URL, a SQL
     * key or an idempotency key built from the id.
     */
    public static String redact(String text, String quoteId) {
        if (text == null || quoteId == null || quoteId.isEmpty()) {
            return text;
        }
        return text.replace(quoteId, of(quoteId));
    }

    /**
     * {@code error} with {@code quoteId} redacted from every message in its cause chain, keeping
     * each original stack trace, so it can be passed to a logger as the throwable argument.
     *
     * <p>A chain that never mentions the id is returned as the same instance. Otherwise each link
     * becomes a {@link RedactedException} whose message starts with the original type, because a
     * logger prints the throwable's own class name and the original's must not be lost.
     */
    public static Throwable redact(Throwable error, String quoteId) {
        if (error == null || quoteId == null || quoteId.isEmpty() || !mentions(error, quoteId, 0)) {
            return error;
        }
        return rebuild(error, quoteId, 0);
    }

    private static boolean mentions(Throwable error, String quoteId, int depth) {
        if (error == null || depth >= MAX_CAUSE_DEPTH) {
            return false;
        }
        String message = error.getMessage();
        if (message != null && message.contains(quoteId)) {
            return true;
        }
        return mentions(causeOf(error), quoteId, depth + 1);
    }

    private static Throwable rebuild(Throwable error, String quoteId, int depth) {
        if (error == null || depth >= MAX_CAUSE_DEPTH) {
            return null;
        }
        Throwable cause = rebuild(causeOf(error), quoteId, depth + 1);
        String message = error.getClass().getName() + ": " + redact(Objects.toString(error.getMessage(), ""), quoteId);
        RedactedException copy = new RedactedException(message, cause);
        copy.setStackTrace(error.getStackTrace());
        return copy;
    }

    private static Throwable causeOf(Throwable error) {
        Throwable cause = error.getCause();
        return cause == error ? null : cause;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Stands in for an exception whose message named a quote id; its message leads with the original type. */
    public static final class RedactedException extends RuntimeException {
        private RedactedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
