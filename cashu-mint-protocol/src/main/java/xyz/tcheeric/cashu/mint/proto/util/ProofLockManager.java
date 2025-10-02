package xyz.tcheeric.cashu.mint.proto.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Provides per-proof locking to coordinate concurrent melts that reference the same secrets.
 */
public final class ProofLockManager {
    private static final ConcurrentHashMap<String, ProofMutex> LOCKS = new ConcurrentHashMap<>();

    private ProofLockManager() {
    }

    public static ProofLock lockSecrets(Collection<String> secrets) {
        if (secrets == null || secrets.isEmpty()) {
            return () -> {
            };
        }

        TreeSet<String> uniqueSecrets = new TreeSet<>();
        for (String secret : secrets) {
            if (secret != null) {
                uniqueSecrets.add(secret);
            }
        }
        if (uniqueSecrets.isEmpty()) {
            return () -> {
            };
        }

        List<LockEntry> acquired = new ArrayList<>(uniqueSecrets.size());
        boolean success = false;
        try {
            for (String secret : uniqueSecrets) {
                ProofMutex mutex = incrementReference(secret);
                mutex.lock.lock();
                acquired.add(new LockEntry(secret, mutex));
            }
            success = true;
        } finally {
            if (!success) {
                for (int i = acquired.size() - 1; i >= 0; i--) {
                    LockEntry entry = acquired.get(i);
                    entry.mutex.lock.unlock();
                    decrementReference(entry.secret, entry.mutex);
                }
            }
        }

        return () -> {
            for (int i = acquired.size() - 1; i >= 0; i--) {
                LockEntry entry = acquired.get(i);
                entry.mutex.lock.unlock();
                decrementReference(entry.secret, entry.mutex);
            }
        };
    }

    private static ProofMutex incrementReference(String secret) {
        return LOCKS.compute(secret, (key, existing) -> {
            ProofMutex mutex = existing;
            if (mutex == null) {
                mutex = new ProofMutex();
            }
            mutex.referenceCount++;
            return mutex;
        });
    }

    @FunctionalInterface
    public interface ProofLock extends AutoCloseable {
        @Override
        void close();
    }

    private static void decrementReference(String secret, ProofMutex mutex) {
        LOCKS.computeIfPresent(secret, (key, existing) -> {
            if (existing != mutex) {
                return existing;
            }
            existing.referenceCount--;
            if (existing.referenceCount <= 0) {
                return null;
            }
            return existing;
        });
    }

    private static final class ProofMutex {
        private final ReentrantLock lock = new ReentrantLock();
        private int referenceCount;
    }

    private static final class LockEntry {
        private final String secret;
        private final ProofMutex mutex;

        private LockEntry(String secret, ProofMutex mutex) {
            this.secret = secret;
            this.mutex = mutex;
        }
    }
}
