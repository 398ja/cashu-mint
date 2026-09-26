package xyz.tcheeric.cashu.mint.rest.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nostr.base.PublicKey;
import nostr.id.Identity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.tcheeric.cashu.mint.rest.service.MintVoucherService;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import xyz.tcheeric.cashu.voucher.app.MerchantVerificationService;
import xyz.tcheeric.cashu.voucher.app.VoucherBackupService;
import xyz.tcheeric.cashu.voucher.app.VoucherIssuanceService;
import xyz.tcheeric.cashu.voucher.app.VoucherService;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherBackupPort;
import xyz.tcheeric.cashu.voucher.app.ports.IssuerKeyRegistry;
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
 * voucher.nostr.relays=${MINT_VOUCHER_NOSTR_RELAYS:wss://relay.damus.io,wss://relay.cashu.xyz}
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
     * <p>Carries the timeouts and retries. It does <b>not</b> carry the relay list or the
     * minimum-relay rule: in cashu-voucher 0.14.x the builder's hand-written {@code relayUrls(...)} writes a
     * field Lombok's {@code @Builder.Default} never reads, so every list given to it is dropped
     * and the two public default relays come back. That, not the missing placeholder alone, is
     * why the relays could not be changed (#407). The client adapter takes the bound list
     * straight from {@link VoucherProperties} instead, and {@link #configuredRelays()} checks it.
     *
     * @return configured NostrRelayConfig
     */
    @Bean
    public NostrRelayConfig nostrRelayConfig() {
        VoucherProperties.Nostr nostr = voucherProperties.getNostr();

        // Only settings something honours are passed on: the connection timeout and retries reach
        // NostrClientAdapter and the publish and query timeouts reach the two repositories (#407).
        NostrRelayConfig config = NostrRelayConfig.builder()
                .connectionTimeoutMs(nostr.getConnectionTimeoutMs())
                .publishTimeoutMs(nostr.getPublishTimeoutMs())
                .queryTimeoutMs(nostr.getQueryTimeoutMs())
                .maxRetries(nostr.getMaxRetries())
                .build();

        config.validate();
        return config;
    }

    /**
     * Creates the Nostr client adapter for relay communication.
     *
     * <p>The relays are the ones bound from {@code voucher.nostr.relays}, checked here, where they
     * are used. See {@link #nostrRelayConfig()} for why they do not pass through that config.
     *
     * @param relayConfig Nostr relay configuration
     * @return NostrClientAdapter instance
     */
    @Bean
    public NostrClientAdapter nostrClientAdapter(NostrRelayConfig relayConfig) {
        List<String> relays = configuredRelays();
        NostrClientAdapter adapter = new NostrClientAdapter(
                relays,
                relayConfig.getConnectionTimeoutMs(),
                relayConfig.getMaxRetries()
        );

        log.info("NostrClientAdapter initialized for voucher operations with {} relay(s): {}",
                relays.size(), relays);

        return adapter;
    }

    /**
     * The configured relays, refused at startup when they cannot work: fewer than the required
     * minimum, blank, or not a {@code ws://} or {@code wss://} URL. Checked here because the
     * library's own validation only ever saw its default list.
     */
    List<String> configuredRelays() {
        VoucherProperties.Nostr nostr = voucherProperties.getNostr();
        List<String> relays = nostr.getRelays() == null ? List.of()
                : nostr.getRelays().stream().map(VoucherConfiguration::requireRelayUrl).toList();
        int required = nostr.isRequireMinimumRelays() ? Math.max(1, nostr.getMinimumRelays()) : 1;
        if (relays.size() < required) {
            throw new IllegalStateException("voucher.nostr.relays has " + relays.size()
                    + " relay(s) but at least " + required + " are required");
        }
        return relays;
    }

    /** The relay URL without surrounding whitespace, refused unless it is {@code ws://} or {@code wss://}. */
    private static String requireRelayUrl(String relay) {
        String url = relay == null ? "" : relay.strip();
        if (!"ws".equals(schemeOf(url)) && !"wss".equals(schemeOf(url))) {
            throw new IllegalStateException(
                    "voucher.nostr.relays entries must be ws:// or wss:// URLs, got: '" + relay + "'");
        }
        return url;
    }

    private static String schemeOf(String url) {
        try {
            return new URI(url).getScheme();
        } catch (URISyntaxException notAUrl) {
            return null;
        }
    }

    /**
     * Creates the voucher ledger repository (NIP-33 public ledger).
     *
     * <p>Built with the configured publish and query timeouts. The shorter constructors hardcode
     * 5000ms for both, which silently discarded {@code voucher.nostr.publishTimeoutMs} and
     * {@code queryTimeoutMs} (cashu-mint#407).
     *
     * @param nostrClient Nostr client adapter
     * @param relayConfig the validated relay configuration, source of the timeouts
     * @return VoucherLedgerPort implementation
     */
    @Bean
    public VoucherLedgerPort voucherLedgerPort(NostrClientAdapter nostrClient, NostrRelayConfig relayConfig) {
        String issuerPublicKeyHex = voucherProperties.getMint().getIssuerPublicKey();
        String issuerPrivateKeyHex = voucherProperties.getMint().getIssuerPrivateKey();

        if (issuerPublicKeyHex == null || issuerPublicKeyHex.isBlank()) {
            throw new IllegalStateException(
                    "voucher.mint.issuerPublicKey must be configured when voucher.enabled=true");
        }

        // The ledger has to SIGN, not merely name who signed.
        //
        // NostrVoucherLedgerRepository refuses to publish without an identity —
        // correctly, since an unsigned ledger event is not evidence of anything
        // and relays reject it. Built with the public key alone it threw
        // "This repository has no signing identity" on every publish, and
        // because that happens on the publish path rather than at startup, the
        // mint booted clean and only failed once a voucher existed. The same
        // defect was live in the customer gateway, where it cost 12 publishes
        // in a single boot; here it is currently masked because voucher.enabled
        // is off in the test stack.
        //
        // The private key is already required below for VoucherService, so
        // nothing new needs configuring.
        if (issuerPrivateKeyHex == null || issuerPrivateKeyHex.isBlank()) {
            throw new IllegalStateException(
                    "voucher.mint.issuerPrivateKey must be configured when voucher.enabled=true: "
                            + "the voucher ledger cannot publish without an identity to sign with");
        }

        // Convert hex string to PublicKey
        PublicKey issuerPublicKey;
        try {
            issuerPublicKey = new PublicKey(issuerPublicKeyHex);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Invalid voucher.mint.issuerPublicKey format (must be a hex-encoded BIP-340 x-only "
                            + "secp256k1 public key, 64 hex characters): "
                            + issuerPublicKeyHex, e);
        }

        Identity issuerIdentity;
        try {
            issuerIdentity = Identity.create(new nostr.base.PrivateKey(issuerPrivateKeyHex));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Invalid voucher.mint.issuerPrivateKey format (must be a hex-encoded "
                            + "secp256k1 private key, 64 hex characters)", e);
        }

        // Guard the pair rather than trusting it: a private key that does not
        // derive the configured public key would publish ledger events signed by
        // a author nobody is checking against, which is worse than not
        // publishing because it looks like it worked.
        if (!issuerIdentity.getPublicKey().toString().equalsIgnoreCase(issuerPublicKey.toString())) {
            throw new IllegalStateException(
                    "voucher.mint.issuerPrivateKey does not derive voucher.mint.issuerPublicKey. "
                            + "The ledger would publish as a different author than the one vouchers "
                            + "name as issuer.");
        }

        NostrVoucherLedgerRepository repository = new NostrVoucherLedgerRepository(
                nostrClient,
                issuerIdentity.getPublicKey(),
                issuerIdentity,
                relayConfig.getPublishTimeoutMs(),
                relayConfig.getQueryTimeoutMs()
        );

        log.info("VoucherLedgerPort (Nostr) initialized with issuer public key: {}...",
                issuerPublicKeyHex.substring(0, Math.min(8, issuerPublicKeyHex.length())));

        return repository;
    }

    /**
     * Creates the voucher backup repository (NIP-17 + NIP-44 private storage).
     *
     * <p>Built with the configured timeouts, for the same reason as the ledger (#407).
     *
     * @param nostrClient Nostr client adapter
     * @param relayConfig the validated relay configuration, source of the timeouts
     * @return VoucherBackupPort implementation
     */
    @Bean
    public VoucherBackupPort voucherBackupPort(NostrClientAdapter nostrClient, NostrRelayConfig relayConfig) {
        NostrVoucherBackupRepository repository = new NostrVoucherBackupRepository(
                nostrClient,
                relayConfig.getPublishTimeoutMs(),
                relayConfig.getQueryTimeoutMs());

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

        VoucherService service = new MintVoucherService(
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
     * Issuer id to public key, the trust anchor merchant verification checks a voucher's
     * signature against.
     *
     * <p>Configured as {@code cashu.mint.voucher.issuer-keys.<issuerId>=<hex pubkey>}. Empty
     * means no issuer is trusted, so every voucher verifies as untrusted: the honest answer with
     * nothing to check against, but only a safe default because it can be changed.
     */
    @Bean
    @ConfigurationProperties(prefix = "cashu.mint.voucher")
    public VoucherIssuerKeys voucherIssuerKeys() {
        return new VoucherIssuerKeys();
    }

    /** Holder for the bound {@code issuer-keys} map. */
    public static class VoucherIssuerKeys {
        private Map<String, String> issuerKeys = new LinkedHashMap<>();

        public Map<String, String> getIssuerKeys() {
            return issuerKeys;
        }

        public void setIssuerKeys(Map<String, String> issuerKeys) {
            this.issuerKeys = issuerKeys == null ? new LinkedHashMap<>() : issuerKeys;
        }
    }

    /**
     * Creates the merchant verification service.
     *
     * <p>The registry argument is required: verifying that a voucher carries a valid signature
     * says nothing unless the key is tied to the issuer the voucher claims, which is what let a
     * voucher be signed under any key with any issuer id and still pass (audit H-12).
     *
     * @param ledgerPort Voucher ledger port
     * @param issuerKeys trusted issuer keys; empty means nothing is trusted
     * @return MerchantVerificationService instance
     */
    @Bean
    public MerchantVerificationService merchantVerificationService(VoucherLedgerPort ledgerPort,
                                                                   VoucherIssuerKeys issuerKeys) {
        Map<String, String> keys = issuerKeys.getIssuerKeys().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        e -> e.getKey().toLowerCase(java.util.Locale.ROOT),
                        e -> e.getValue().toLowerCase(java.util.Locale.ROOT)));
        if (keys.isEmpty()) {
            log.warn("No cashu.mint.voucher.issuer-keys configured: merchant verification will "
                    + "report every voucher's signature as untrusted, because no issuer key is "
                    + "trusted. Configure cashu.mint.voucher.issuer-keys.<issuerId>=<hex pubkey>.");
        }
        IssuerKeyRegistry registry =
                issuerId -> issuerId == null
                        ? Optional.empty()
                        : Optional.ofNullable(keys.get(issuerId.toLowerCase(java.util.Locale.ROOT)));

        MerchantVerificationService service = new MerchantVerificationService(ledgerPort, registry);

        log.info("MerchantVerificationService initialized with {} trusted issuer key(s)",
                keys.size());

        return service;
    }
}
