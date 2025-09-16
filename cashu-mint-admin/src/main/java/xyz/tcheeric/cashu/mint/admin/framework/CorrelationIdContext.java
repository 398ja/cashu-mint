package xyz.tcheeric.cashu.mint.admin.framework;

import org.slf4j.MDC;

import java.util.Objects;

/**
 * Thread-scoped holder that exposes correlation identifiers to the application and
 * logging layers.
 */
public final class CorrelationIdContext {

    public static final String MDC_KEY = "correlationId";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private CorrelationIdContext() {
    }

    /**
     * Initialises the context with either the provided identifier or a freshly generated value.
     *
     * @param correlationId identifier provided by a caller, may be {@code null} or blank
     * @return the identifier stored in the context
     */
    public static String init(final String correlationId) {
        final String resolved = normalise(correlationId);
        final String valueToUse = resolved != null ? resolved : CorrelationIdGenerator.generate();
        set(valueToUse);
        return valueToUse;
    }

    /**
     * Initialises the context with a generated correlation identifier.
     *
     * @return generated identifier stored in the context
     */
    public static String init() {
        return init(null);
    }

    /**
     * Initialises the context and returns an auto-closeable scope that restores the previous state
     * when closed.
     *
     * @param correlationId identifier provided by a caller, may be {@code null} or blank
     * @return scope that must be closed to release the bound identifier
     */
    public static Scope open(final String correlationId) {
        final String previous = CURRENT.get();
        final String assigned = init(correlationId);
        return new Scope(previous, assigned);
    }

    /**
     * Initialises the context with a generated identifier and returns an auto-closeable scope.
     *
     * @return scope that must be closed to release the bound identifier
     */
    public static Scope open() {
        return open(null);
    }

    /**
     * Returns the correlation identifier bound to the current thread.
     *
     * @return correlation identifier or {@code null} if not initialised
     */
    public static String currentId() {
        return CURRENT.get();
    }

    /**
     * Clears the correlation identifier from the current thread and logging MDC.
     */
    public static void clear() {
        CURRENT.remove();
        MDC.remove(MDC_KEY);
    }

    private static void set(final String correlationId) {
        final String value = Objects.requireNonNull(correlationId, "correlationId");
        CURRENT.set(value);
        MDC.put(MDC_KEY, value);
    }

    private static String normalise(final String candidate) {
        if (candidate == null) {
            return null;
        }
        final String trimmed = candidate.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Scope that ensures correlation identifiers are cleared when the surrounding block exits.
     */
    public static final class Scope implements AutoCloseable {

        private final String previous;
        private final String assigned;
        private boolean closed;

        private Scope(final String previous, final String assigned) {
            this.previous = previous;
            this.assigned = Objects.requireNonNull(assigned, "assigned");
        }

        /**
         * Returns the identifier assigned to the current thread for the duration of this scope.
         *
         * @return current correlation identifier
         */
        public String correlationId() {
            return assigned;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            final String current = CURRENT.get();
            if (!Objects.equals(current, assigned)) {
                return;
            }
            if (previous != null) {
                set(previous);
            } else {
                clear();
            }
        }
    }
}
