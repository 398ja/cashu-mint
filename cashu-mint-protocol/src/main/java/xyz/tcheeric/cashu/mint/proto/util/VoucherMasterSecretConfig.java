package xyz.tcheeric.cashu.mint.proto.util;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.security.SecureRandom;
import java.util.Properties;

/**
 * Utility for accessing the voucher master secret configuration.
 *
 * <p>The master secret is used to derive signing keys for voucher amounts.
 * It can be configured via (in order of precedence):
 * <ol>
 *   <li>System property: {@code -Dvoucher.master.secret=X}</li>
 *   <li>Environment variable: {@code VOUCHER_MASTER_SECRET}</li>
 *   <li>Property file: {@code voucher.master.secret} in proto.properties</li>
 *   <li>Auto-generated: a random 64-character hex string (stored in memory only)</li>
 * </ol>
 *
 * <p><b>Security Note:</b> In production, always configure the master secret explicitly
 * rather than relying on auto-generation. Auto-generated secrets are lost on restart.
 */
@Slf4j
public final class VoucherMasterSecretConfig {

    private static final String PROPERTY_KEY = "voucher.master.secret";
    private static final String ENV_KEY = "VOUCHER_MASTER_SECRET";
    private static final String SYSTEM_PROP_KEY = "voucher.master.secret";

    private static volatile String cachedSecret;
    private static final Object lock = new Object();

    private static final Properties properties = new Properties();

    static {
        try (InputStream input = VoucherMasterSecretConfig.class.getClassLoader().getResourceAsStream("proto.properties")) {
            if (input != null) {
                properties.load(input);
            }
        } catch (Exception e) {
            log.warn("Failed to load proto.properties: {}", e.getMessage());
        }
    }

    private VoucherMasterSecretConfig() {
    }

    /**
     * Returns the configured voucher master secret.
     *
     * @return the master secret as a 64-character hex string (32 bytes)
     */
    public static String getMasterSecret() {
        if (cachedSecret != null) {
            return cachedSecret;
        }

        synchronized (lock) {
            if (cachedSecret != null) {
                return cachedSecret;
            }

            String secret = resolveSecret();
            cachedSecret = secret;
            return secret;
        }
    }

    private static String resolveSecret() {
        // 1. Check system property
        String sysProp = System.getProperty(SYSTEM_PROP_KEY);
        if (sysProp != null && !sysProp.isBlank()) {
            log.info("Voucher master secret loaded from system property");
            return validateAndNormalize(sysProp);
        }

        // 2. Check environment variable
        String envVar = System.getenv(ENV_KEY);
        if (envVar != null && !envVar.isBlank()) {
            log.info("Voucher master secret loaded from environment variable");
            return validateAndNormalize(envVar);
        }

        // 3. Check property file.
        //
        // Kept for local development only. A key that lives in a file on the classpath is a key
        // that gets committed: proto.properties is packaged into the jar, so a value set here
        // ships to every deployment built from that source (audit M-3). Warned about loudly
        // rather than removed, because removing it would break existing local setups silently.
        String propFile = properties.getProperty(PROPERTY_KEY);
        if (propFile != null && !propFile.isBlank()) {
            log.warn("Voucher master secret loaded from proto.properties. This file is packaged "
                    + "into the jar, so the key ships with every build made from this source. "
                    + "Use {} or the {} system property instead.", ENV_KEY, SYSTEM_PROP_KEY);
            return validateAndNormalize(propFile);
        }

        // 4. No secret configured.
        //
        // Generating one per boot was worse than failing. Voucher identity is derived from this
        // key, so a restart silently invalidated every voucher already issued: the mint kept
        // serving, and the failure showed up later as vouchers that no longer verified, with
        // nothing at the point of restart to connect the two. A missing key for a subsystem that
        // is switched on is a configuration error, and configuration errors belong at startup.
        if (!isVoucherSubsystemEnabled()) {
            log.debug("No voucher master secret configured; the voucher subsystem is disabled.");
            return null;
        }
        throw new IllegalStateException(
                "The voucher subsystem is enabled but no master secret is configured. Voucher "
                        + "identity is derived from this key, so generating one per boot would "
                        + "invalidate every voucher already issued on the next restart. Set "
                        + ENV_KEY + " (or -D" + SYSTEM_PROP_KEY + ") to a 64-character hex "
                        + "string; generate one with: openssl rand -hex 32");
    }

    /**
     * Whether vouchers are switched on for this process.
     *
     * <p>A mint that does not issue vouchers has no reason to require the key, and most
     * deployments and every unit test are in that position.
     */
    private static boolean isVoucherSubsystemEnabled() {
        String property = System.getProperty("voucher.enabled");
        if (property != null && !property.isBlank()) {
            return Boolean.parseBoolean(property);
        }
        String env = System.getenv("VOUCHER_ENABLED");
        if (env != null && !env.isBlank()) {
            return Boolean.parseBoolean(env);
        }
        return false;
    }

    private static String validateAndNormalize(String secret) {
        String normalized = secret.trim().toLowerCase();
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Voucher master secret must be a 64-character hex string (32 bytes)");
        }
        return normalized;
    }

    private static String generateRandomSecret() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    /**
     * Clear the cached secret. Useful for testing.
     */
    public static void clearCache() {
        synchronized (lock) {
            cachedSecret = null;
        }
    }
}
