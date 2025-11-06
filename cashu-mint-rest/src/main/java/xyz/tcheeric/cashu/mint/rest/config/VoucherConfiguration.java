package xyz.tcheeric.cashu.mint.rest.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nostr.base.PublicKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.tcheeric.cashu.voucher.app.MerchantVerificationService;
import xyz.tcheeric.cashu.voucher.app.VoucherBackupService;
import xyz.tcheeric.cashu.voucher.app.VoucherIssuanceService;
import xyz.tcheeric.cashu.voucher.app.VoucherService;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherBackupPort;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherLedgerPort;
import xyz.tcheeric.cashu.voucher.nostr.NostrClientAdapter;
import xyz.tcheeric.cashu.voucher.nostr.NostrVoucherBackupRepository;
import xyz.tcheeric.cashu.voucher.nostr.NostrVoucherLedgerRepository;
import xyz.tcheeric.cashu.voucher.nostr.config.NostrRelayConfig;

/**
 * Spring configuration for voucher functionality.
 *
 * <p>This configuration is activated when the property {@code voucher.enabled=true} is set.
 * It creates all the necessary beans for voucher issuance, verification, and Nostr storage.
 *
 * <h3>Architecture</h3>
 * <p>This configuration follows the hexagonal architecture (ports & adapters) pattern:
 * <ul>
 *   <li><b>Domain Layer</b>: {@code cashu-voucher-domain} - Pure business logic</li>
 *   <li><b>Application Layer</b>: {@code cashu-voucher-app} - Use cases and port interfaces</li>
 *   <li><b>Infrastructure Layer</b>: {@code cashu-voucher-nostr} - Nostr adapter implementation</li>
 * </ul>
 *
 * <h3>Bean Dependencies</h3>
 * <pre>
 * VoucherService
 *   ├── VoucherLedgerPort (NostrVoucherLedgerRepository)
 *   │     └── NostrClientAdapter
 *   │           └── NostrRelayConfig
 *   └── VoucherBackupPort (NostrVoucherBackupRepository)
 *         └── NostrClientAdapter
 *
 * VoucherIssuanceService
 *   └── VoucherService
 *
 * VoucherBackupService
 *   └── VoucherBackupPort
 *
 * MerchantVerificationService
 *   └── VoucherLedgerPort
 * </pre>
 *
 * <h3>Required Configuration</h3>
 * <pre>
 * voucher.enabled=true
 * voucher.mint.issuerPrivateKey=${MINT_VOUCHER_ISSUER_PRIVKEY}
 * voucher.mint.issuerPublicKey=${MINT_VOUCHER_ISSUER_PUBKEY}
 * voucher.nostr.relays[0]=wss://relay.damus.io
 * voucher.nostr.relays[1]=wss://relay.cashu.xyz
 * </pre>
 *
 * @see VoucherProperties
 * @see VoucherService
 * @see NostrVoucherLedgerRepository
 * @see NostrVoucherBackupRepository
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(VoucherProperties.class)
@ConditionalOnProperty(name = "voucher.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class VoucherConfiguration {

    private final VoucherProperties voucherProperties;

    /**
     * Creates the Nostr relay configuration from properties.
     *
     * @return configured NostrRelayConfig
     */
    @Bean
    public NostrRelayConfig nostrRelayConfig() {
        VoucherProperties.Nostr nostr = voucherProperties.getNostr();

        NostrRelayConfig config = NostrRelayConfig.builder()
                .relayUrls(nostr.getRelays())
                .connectionTimeoutMs(nostr.getConnectionTimeoutMs())
                .publishTimeoutMs(nostr.getPublishTimeoutMs())
                .queryTimeoutMs(nostr.getQueryTimeoutMs())
                .maxRetries(nostr.getMaxRetries())
                .exponentialBackoff(nostr.isExponentialBackoff())
                .batchSize(nostr.getBatchSize())
                .healthCheckEnabled(nostr.isHealthCheckEnabled())
                .healthCheckIntervalMs(nostr.getHealthCheckIntervalMs())
                .maxConsecutiveFailures(nostr.getMaxConsecutiveFailures())
                .autoReconnect(nostr.isAutoReconnect())
                .requireMinimumRelays(nostr.isRequireMinimumRelays())
                .minimumRelays(nostr.getMinimumRelays())
                .build();

        // Validate configuration
        config.validate();

        log.info("Nostr relay config initialized with {} relay(s): {}",
                config.getRelayCount(), config.getRelayUrls());

        return config;
    }

    /**
     * Creates the Nostr client adapter for relay communication.
     *
     * @param relayConfig Nostr relay configuration
     * @return NostrClientAdapter instance
     */
    @Bean
    public NostrClientAdapter nostrClientAdapter(NostrRelayConfig relayConfig) {
        NostrClientAdapter adapter = new NostrClientAdapter(
                relayConfig.getRelayUrls(),
                relayConfig.getConnectionTimeoutMs(),
                relayConfig.getMaxRetries()
        );

        log.info("NostrClientAdapter initialized for voucher operations");

        return adapter;
    }

    /**
     * Creates the voucher ledger repository (NIP-33 public ledger).
     *
     * @param nostrClient Nostr client adapter
     * @return VoucherLedgerPort implementation
     */
    @Bean
    public VoucherLedgerPort voucherLedgerPort(NostrClientAdapter nostrClient) {
        String issuerPublicKeyHex = voucherProperties.getMint().getIssuerPublicKey();

        if (issuerPublicKeyHex == null || issuerPublicKeyHex.isBlank()) {
            throw new IllegalStateException(
                    "voucher.mint.issuerPublicKey must be configured when voucher.enabled=true");
        }

        // Convert hex string to PublicKey
        PublicKey issuerPublicKey;
        try {
            issuerPublicKey = new PublicKey(issuerPublicKeyHex);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Invalid voucher.mint.issuerPublicKey format (must be hex-encoded ED25519 public key): "
                            + issuerPublicKeyHex, e);
        }

        NostrVoucherLedgerRepository repository = new NostrVoucherLedgerRepository(
                nostrClient,
                issuerPublicKey
        );

        log.info("VoucherLedgerPort (Nostr) initialized with issuer public key: {}...",
                issuerPublicKeyHex.substring(0, Math.min(8, issuerPublicKeyHex.length())));

        return repository;
    }

    /**
     * Creates the voucher backup repository (NIP-17 + NIP-44 private storage).
     *
     * @param nostrClient Nostr client adapter
     * @return VoucherBackupPort implementation
     */
    @Bean
    public VoucherBackupPort voucherBackupPort(NostrClientAdapter nostrClient) {
        NostrVoucherBackupRepository repository = new NostrVoucherBackupRepository(nostrClient);

        log.info("VoucherBackupPort (Nostr) initialized for encrypted voucher backups");

        return repository;
    }

    /**
     * Creates the main voucher service.
     *
     * @param ledgerPort Voucher ledger port
     * @param backupPort Voucher backup port
     * @return VoucherService instance
     */
    @Bean
    public VoucherService voucherService(
            VoucherLedgerPort ledgerPort,
            VoucherBackupPort backupPort
    ) {
        String issuerPrivateKey = voucherProperties.getMint().getIssuerPrivateKey();
        String issuerPublicKey = voucherProperties.getMint().getIssuerPublicKey();

        if (issuerPrivateKey == null || issuerPrivateKey.isBlank()) {
            throw new IllegalStateException(
                    "voucher.mint.issuerPrivateKey must be configured when voucher.enabled=true");
        }

        if (issuerPublicKey == null || issuerPublicKey.isBlank()) {
            throw new IllegalStateException(
                    "voucher.mint.issuerPublicKey must be configured when voucher.enabled=true");
        }

        VoucherService service = new VoucherService(
                ledgerPort,
                backupPort,
                issuerPrivateKey,
                issuerPublicKey
        );

        log.info("VoucherService initialized successfully");

        return service;
    }

    /**
     * Creates the voucher issuance service.
     *
     * @param voucherService Main voucher service
     * @return VoucherIssuanceService instance
     */
    @Bean
    public VoucherIssuanceService voucherIssuanceService(VoucherService voucherService) {
        VoucherIssuanceService service = new VoucherIssuanceService(voucherService);

        log.info("VoucherIssuanceService initialized");

        return service;
    }

    /**
     * Creates the voucher backup service.
     *
     * @param voucherService Main voucher service
     * @return VoucherBackupService instance
     */
    @Bean
    public VoucherBackupService voucherBackupService(VoucherService voucherService) {
        VoucherBackupService service = new VoucherBackupService(voucherService);

        log.info("VoucherBackupService initialized");

        return service;
    }

    /**
     * Creates the merchant verification service.
     *
     * @param ledgerPort Voucher ledger port
     * @return MerchantVerificationService instance
     */
    @Bean
    public MerchantVerificationService merchantVerificationService(VoucherLedgerPort ledgerPort) {
        MerchantVerificationService service = new MerchantVerificationService(ledgerPort);

        log.info("MerchantVerificationService initialized");

        return service;
    }
}
