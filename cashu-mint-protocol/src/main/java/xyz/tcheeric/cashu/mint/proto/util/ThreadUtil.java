package xyz.tcheeric.cashu.mint.proto.util;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread utilities for the Cashu mint protocol.
 */
public class ThreadUtil {

    /**
     * Global lock for mint/melt operations.
     *
     * @deprecated This global lock has been replaced by {@link QuoteLockManager} for per-quote
     *             locking, which allows parallel processing of different quotes while still
     *             preventing double-mint attacks. Use {@link QuoteLockManager#lockQuote(String)}
     *             instead.
     */
    @Deprecated(since = "0.7.4", forRemoval = true)
    public static final ReentrantLock MINT_MELT_LOCK = new ReentrantLock();

    private ThreadUtil() {
    }
}
