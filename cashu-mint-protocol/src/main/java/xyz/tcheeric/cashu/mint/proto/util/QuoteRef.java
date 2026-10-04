package xyz.tcheeric.cashu.mint.proto.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *
 * <p>Besides the one id a caller names, {@link #redact(String)} replaces any id in the shapes the
 * mint's own text and its HTTP clients produce: a {@code quoteId=} / {@code quote_id=} field (as
 * in the payment gateway's {@code /search/findByQuoteId?quoteId=<id>} URL that Spring quotes in
 * "I/O error on GET request for ..."), a JSON {@code "quote":"<id>"} member, and the id segment of
 * a {@code /quote/<method>/<id>} URL. These are the same shapes as imani-wallet-lib's
 * {@code QuoteRef}.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class QuoteRef {

    /** What a missing id logs as, rather than a hash of nothing. Same placeholder as wallet-lib. */
    public static final String NO_QUOTE = "(none)";

    private static final String PREFIX = "q:";
    private static final int REF_BYTES = 6;

    /**
     * A guard against stack exhaustion only; real chains are far shorter. Cycles are caught by
     * identity, not by this limit. A chain longer than this ends in a marker link saying so, so
     * nothing past it can carry an unredacted id into a log.
     */
    private static final int MAX_CAUSE_DEPTH = 256;

    /** A ref already in the text, ending where an id character would not follow. */
    private static final String EXISTING_REF = "q:[0-9a-f]{12}(?![0-9a-zA-Z-])";
    private static final String EXISTING_PLACEHOLDER = "\\(none\\)";
    /** Skips a value that is already a ref, so redacting twice does not hash the ref itself. */
    private static final String NOT_A_REF = "(?!" + EXISTING_REF + ")(?!" + EXISTING_PLACEHOLDER + ")";

    /** The shapes a quote id takes in text the mint did not compose. Group 1 is kept, group 2 is the id. */
    private static final List<Pattern> QUOTE_ID_SHAPES = List.of(
            Pattern.compile("(quote_?[iI]d=)" + NOT_A_REF + "([^,;.&\\s)\"'}]+)"),
            Pattern.compile("(\"quote(?:_?[iI]d)?\"\\s*:\\s*\")" + NOT_A_REF + "([^\"]+)"),
            Pattern.compile("(/quote/(?:voucher/)?(?!voucher/)[a-z0-9_]+/)" + NOT_A_REF + "([^\\s\"'/?#,;)]+)"));

    /** The ref for a quote id, safe to log; {@code (none)} when there is no id. */
    public static String of(String quoteId) {
        if (quoteId == null || quoteId.isEmpty()) {
            return NO_QUOTE;
        }
        return PREFIX + HexFormat.of().formatHex(sha256(quoteId), 0, REF_BYTES);
    }

    /** {@code text} with every quote id it carries in one of the known shapes replaced by its ref. */
    public static String redact(String text) {
        if (text == null) {
            return null;
        }
        String redacted = text;
        for (Pattern shape : QUOTE_ID_SHAPES) {
            redacted = replaceIds(shape, redacted);
        }
        return redacted;
    }

    /**
     * As {@link #redact(String)}, and also every literal occurrence of {@code quoteId}, for text the
     * mint does not compose itself, such as an idempotency key built from the id, where the id can
     * appear in a shape no pattern knows.
     */
    public static String redact(String text, String quoteId) {
        String redacted = redact(text);
        if (redacted == null || quoteId == null || quoteId.isEmpty()) {
            return redacted;
        }
        return redacted.replace(quoteId, of(quoteId));
    }

    /** As {@link #redact(Throwable, String)} when the caller does not know which quote it is about. */
    public static Throwable redact(Throwable error) {
        return redact(error, null);
    }

    /**
     * {@code error} with every quote id redacted, as by {@link #redact(String, String)}, from every
     * message in its cause chain and in its suppressed exceptions, keeping each original stack
     * trace, so it can be passed to a logger as the throwable argument.
     *
     * <p>A chain with nothing to redact is returned as the same instance. Otherwise each link
     * becomes a {@link RedactedException} whose message starts with the original type, because a
     * logger prints the throwable's own class name and the original's must not be lost. A cyclic
     * chain is cut where it would repeat.
     */
    public static Throwable redact(Throwable error, String quoteId) {
        if (error == null || !needsRedaction(error, quoteId, Collections.newSetFromMap(new IdentityHashMap<>()), 0)) {
            return error;
        }
        return rebuild(error, quoteId, new IdentityHashMap<>(), 0);
    }

    private static boolean needsRedaction(Throwable error, String quoteId, Set<Throwable> seen, int depth) {
        if (error == null || !seen.add(error)) {
            return false;
        }
        if (depth >= MAX_CAUSE_DEPTH) {
            return true; // the rest is cut off and replaced by a marker, which is itself a change
        }
        String message = error.getMessage();
        if (message != null && !message.equals(redact(message, quoteId))) {
            return true;
        }
        for (Throwable suppressed : error.getSuppressed()) {
            if (needsRedaction(suppressed, quoteId, seen, depth + 1)) {
                return true;
            }
        }
        return needsRedaction(error.getCause(), quoteId, seen, depth + 1);
    }

    private static Throwable rebuild(Throwable error, String quoteId, Map<Throwable, Throwable> rebuilt, int depth) {
        if (error == null) {
            return null;
        }
        if (rebuilt.containsKey(error)) {
            return null; // a cycle or a shared link: it is already in the chain once
        }
        if (depth >= MAX_CAUSE_DEPTH) {
            return new RedactedException("(cause chain cut after " + MAX_CAUSE_DEPTH + " links)", null);
        }
        String message = error.getClass().getName() + ": " + redact(Objects.toString(error.getMessage(), ""), quoteId);
        RedactedException copy = new RedactedException(message);
        rebuilt.put(error, copy);
        Throwable cause = error.getCause() == error ? null : error.getCause();
        Throwable redactedCause = rebuild(cause, quoteId, rebuilt, depth + 1);
        if (redactedCause != null) {
            copy.initCause(redactedCause);
        }
        for (Throwable suppressed : error.getSuppressed()) {
            Throwable redactedSuppressed = rebuild(suppressed, quoteId, rebuilt, depth + 1);
            if (redactedSuppressed != null) {
                copy.addSuppressed(redactedSuppressed);
            }
        }
        copy.setStackTrace(error.getStackTrace());
        return copy;
    }

    private static String replaceIds(Pattern shape, String text) {
        Matcher matcher = shape.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(1) + of(matcher.group(2))));
        }
        matcher.appendTail(out);
        return out.toString();
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
        /** Cause left unset, so {@link #initCause} can attach the redacted cause after construction. */
        private RedactedException(String message) {
            super(message);
        }

        private RedactedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
