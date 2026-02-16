package xyz.tcheeric.cashu.mint.admin.framework;

import java.util.UUID;

/**
 * Utility for creating correlation identifiers that connect CLI and REST invocations
 * to downstream audit entries.
 */
public final class CorrelationIdGenerator {

    private CorrelationIdGenerator() {
    }

    /**
     * Generates a new random correlation identifier.
     *
     * @return correlation identifier suitable for logging and persistence
     */
    public static String generate() {
        return UUID.randomUUID().toString();
    }
}
