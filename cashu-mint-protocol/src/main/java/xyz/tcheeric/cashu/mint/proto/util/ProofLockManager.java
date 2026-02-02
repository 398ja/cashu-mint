package xyz.tcheeric.cashu.mint.proto.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Provides per-proof locking to coordinate concurrent melts that reference the same secrets.
 *
 * <p><b>Security:</b> Secrets are hashed using SHA-256 before being used as HashMap keys.
 * This prevents hash collision DoS attacks where an attacker could craft secrets with
 * colliding hashCodes to degrade HashMap performance (per Oracle Secure Coding Guidelines DOS-5).
 */
public final class ProofLockManager {
    private static final ConcurrentHashMap<String, ProofMutex> LOCKS = new ConcurrentHashMap<>();

    private ProofLockManager() {
    }

    /**
     * Derives a secure key from a secret using SHA-256.
     * This prevents hash collision attacks on the internal HashMap.
     *
     * @param secret the original secret string
     * @return SHA-256 hash of the secret as hex string
     */
    private static String deriveKey(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in Java
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public static ProofLock lockSecrets(Collection<String> secrets) {
        if (secrets == null || secrets.isEmpty()) {
            return () -> {
            };
        }

        TreeSet<String> uniqueKeys = new TreeSet<>();
        for (String secret : secrets) {
            if (secret != null) {
                // Use cryptographic hash as key to prevent hash collision attacks
                uniqueKeys.add(deriveKey(secret));
            }
        }
        if (uniqueKeys.isEmpty()) {
            return () -> {
            };
        }

        List<LockEntry> acquired = new ArrayList<>(uniqueKeys.size());
        boolean success = false;
        try {
            for (String key : uniqueKeys) {
                ProofMutex mutex = incrementReference(key);
                mutex.lock.lock();
                acquired.add(new LockEntry(key, mutex));
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
