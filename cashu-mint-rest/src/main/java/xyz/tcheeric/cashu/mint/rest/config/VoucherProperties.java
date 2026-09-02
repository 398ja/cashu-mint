package xyz.tcheeric.cashu.mint.rest.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for voucher functionality.
 *
 * <p>Binds to the {@code voucher.*} properties in application.properties or application.yml.
 *
 * <h3>Example Configuration (application.yml)</h3>
 * <pre>
 * voucher:
 *   mint:
 *     issuerPrivateKey: ${MINT_VOUCHER_ISSUER_PRIVKEY}
 *     issuerPublicKey: ${MINT_VOUCHER_ISSUER_PUBKEY}
 *   nostr:
 *     relays:
 *       - wss://relay.damus.io
 *       - wss://relay.cashu.xyz
 *     connectionTimeoutMs: 5000
 *     publishTimeoutMs: 5000
 *     queryTimeoutMs: 10000
 *     maxRetries: 3
 * </pre>
 *
 * <h3>Example Configuration (application.properties)</h3>
 * <pre>
 * voucher.mint.issuerPrivateKey=${MINT_VOUCHER_ISSUER_PRIVKEY}
 * voucher.mint.issuerPublicKey=${MINT_VOUCHER_ISSUER_PUBKEY}
 * voucher.nostr.relays[0]=wss://relay.damus.io
 * voucher.nostr.relays[1]=wss://relay.cashu.xyz
 * voucher.nostr.connectionTimeoutMs=5000
 * voucher.nostr.publishTimeoutMs=5000
 * voucher.nostr.queryTimeoutMs=10000
 * voucher.nostr.maxRetries=3
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "voucher")
public class VoucherProperties {

    /**
     * Mint-specific voucher configuration.
     */
    private Mint mint = new Mint();

    /**
     * Nostr relay configuration.
     */
    private Nostr nostr = new Nostr();

    /**
     * Mint issuer configuration for voucher signing.
     */
    @Data
    public static class Mint {
        /**
         * Private key used by the mint to sign vouchers (secp256k1, hex-encoded, 32 bytes).
         * This should be kept secure and never committed to version control.
         */
        private String issuerPrivateKey;

        /**
         * Public key corresponding to the issuer private key (BIP-340 x-only, hex-encoded).
         * This is published and used by merchants/wallets to verify voucher signatures.
         */
        private String issuerPublicKey;
    }

    /**
     * Nostr relay configuration for voucher ledger and backup.
     */
    @Data
    public static class Nostr {
        /**
         * List of Nostr relay URLs (wss:// or ws://).
         * Default: Cashu-specific relays (relay.damus.io, relay.cashu.xyz).
         */
        private List<String> relays = new ArrayList<>(List.of(
                "wss://relay.damus.io",
                "wss://relay.cashu.xyz"
        ));

        /**
         * Connection timeout in milliseconds.
         * Default: 5000ms (5 seconds).
         */
        private long connectionTimeoutMs = 5000L;

        /**
         * Publish timeout in milliseconds.
         * Default: 5000ms (5 seconds).
         */
        private long publishTimeoutMs = 5000L;

        /**
         * Query timeout in milliseconds.
         * Default: 10000ms (10 seconds).
         */
        private long queryTimeoutMs = 10000L;

        /**
         * Maximum retry attempts for failed operations.
         * Default: 3.
         */
        private int maxRetries = 3;

        /**
         * Whether to enable exponential backoff for retries.
         * Default: true.
         */
        private boolean exponentialBackoff = true;

        /**
         * Batch size for bulk operations.
         * Default: 100.
         */
        private int batchSize = 100;

        /**
         * Whether to enable relay health checks.
         * Default: true.
         */
        private boolean healthCheckEnabled = true;

        /**
         * Health check interval in milliseconds.
         * Default: 60000ms (1 minute).
         */
        private long healthCheckIntervalMs = 60000L;

        /**
         * Maximum consecutive failures before marking relay as unhealthy.
         * Default: 3.
         */
        private int maxConsecutiveFailures = 3;

        /**
         * Whether to auto-reconnect to relays on connection loss.
         * Default: true.
         */
        private boolean autoReconnect = true;

        /**
         * Whether to require a minimum number of relays.
         * Default: true.
         */
        private boolean requireMinimumRelays = true;

        /**
         * Minimum number of relays required.
         * Default: 1.
         */
        private int minimumRelays = 1;
    }
}
