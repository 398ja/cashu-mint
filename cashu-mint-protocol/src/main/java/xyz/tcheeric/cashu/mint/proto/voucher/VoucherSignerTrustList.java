package xyz.tcheeric.cashu.mint.proto.voucher;

import lombok.NonNull;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The mint's configured voucher signer keys, parsed once and shared by every consumer
 * (cashu-mint#527).
 *
 * <p>Two sources of trust, matching how vouchers are really signed:
 * <ul>
 *   <li><b>Issuer keys</b> ({@code cashu.mint.voucher.issuer-keys.<issuerId>}) — the key one
 *       issuer signs with. Trusted only for vouchers naming that issuer.</li>
 *   <li><b>Trusted signers</b> ({@code cashu.mint.voucher.trusted-signers}) — keys that sign on
 *       behalf of any issuer, such as the gateway that issues a merchant's vouchers under its own
 *       identity key while naming the merchant as {@code issuerId}.</li>
 * </ul>
 *
 * <p>Issuer ids are matched case-insensitively, as the merchant-verification registry always
 * matched them. Keys are normalised to x-only lower-case hex, so a key configured compressed
 * matches the x-only key a voucher carries. Blank entries are skipped, because an unset
 * {@code ${ENV:}} placeholder resolves to the empty string and means "not configured". A
 * malformed key is refused, so a typo fails the boot instead of silently trusting nothing.
 */
public final class VoucherSignerTrustList implements TrustedVoucherSigners {

    private static final String ISSUER_KEYS_PROPERTY = "cashu.mint.voucher.issuer-keys";
    private static final String TRUSTED_SIGNERS_PROPERTY = "cashu.mint.voucher.trusted-signers";

    private final Map<String, VoucherSignerKey> issuerKeys;
    private final Set<VoucherSignerKey> trustedSigners;

    /**
     * @param issuerKeys     issuer id to the key that issuer signs with
     * @param trustedSigners keys trusted to sign for any issuer
     * @throws IllegalArgumentException when a non-blank key is not a secp256k1 public key
     */
    public VoucherSignerTrustList(@NonNull Map<String, String> issuerKeys,
                                  @NonNull Collection<String> trustedSigners) {
        this(issuerKeys, trustedSigners, Map.of());
    }

    /**
     * @param issuerKeys       issuer id to the key that issuer signs with
     * @param trustedSigners   keys trusted to sign for any issuer
     * @param mintOwnSigners   keys the mint itself signs vouchers with, trusted for any issuer;
     *                         taken from other properties, so named separately in errors
     * @throws IllegalArgumentException when a non-blank key is not a secp256k1 public key
     */
    public VoucherSignerTrustList(@NonNull Map<String, String> issuerKeys,
                                  @NonNull Collection<String> trustedSigners,
                                  @NonNull Map<String, String> mintOwnSigners) {
        this.issuerKeys = parseIssuerKeys(issuerKeys);
        this.trustedSigners = parseTrustedSigners(trustedSigners, mintOwnSigners);
    }

    /**
     * A list that trusts nothing.
     *
     * @return the empty list
     */
    public static VoucherSignerTrustList empty() {
        return new VoucherSignerTrustList(Map.of(), Set.of(), Map.of());
    }

    @Override
    public boolean trusts(String issuerId, String signerPublicKey) {
        return VoucherSignerKey.tryParse(signerPublicKey)
                .map(key -> trustedSigners.contains(key) || isRegisteredFor(issuerId, key))
                .orElse(false);
    }

    @Override
    public boolean isEmpty() {
        return issuerKeys.isEmpty() && trustedSigners.isEmpty();
    }

    /**
     * The key registered for one issuer, in x-only hex: the answer the merchant-verification
     * {@code IssuerKeyRegistry} port needs, so both checks read one parsed configuration.
     *
     * @param issuerId the issuer, matched case-insensitively
     * @return the registered key, or empty when the issuer has none
     */
    public Optional<String> registeredKeyFor(String issuerId) {
        return registeredSignerKeyFor(issuerId).map(VoucherSignerKey::getXOnlyHex);
    }

    /** @return how many issuers have a registered key */
    public int issuerKeyCount() {
        return issuerKeys.size();
    }

    /** @return how many keys are trusted to sign for any issuer */
    public int trustedSignerCount() {
        return trustedSigners.size();
    }

    private boolean isRegisteredFor(String issuerId, VoucherSignerKey key) {
        return registeredSignerKeyFor(issuerId).map(key::equals).orElse(false);
    }

    private Optional<VoucherSignerKey> registeredSignerKeyFor(String issuerId) {
        return issuerId == null
                ? Optional.empty()
                : Optional.ofNullable(issuerKeys.get(normaliseIssuerId(issuerId)));
    }

    private static Map<String, VoucherSignerKey> parseIssuerKeys(Map<String, String> configured) {
        Map<String, VoucherSignerKey> parsed = new LinkedHashMap<>();
        configured.forEach((issuerId, key) -> {
            if (isConfigured(key)) {
                parsed.put(normaliseIssuerId(issuerId),
                        VoucherSignerKey.parse(key, ISSUER_KEYS_PROPERTY + "." + issuerId));
            }
        });
        return Map.copyOf(parsed);
    }

    private static Set<VoucherSignerKey> parseTrustedSigners(Collection<String> configured,
                                                             Map<String, String> mintOwnSigners) {
        Set<VoucherSignerKey> parsed = new LinkedHashSet<>();
        int position = 0;
        for (String key : configured) {
            if (isConfigured(key)) {
                parsed.add(VoucherSignerKey.parse(key, TRUSTED_SIGNERS_PROPERTY + "[" + position + "]"));
            }
            position++;
        }
        mintOwnSigners.forEach((property, key) -> {
            if (isConfigured(key)) {
                parsed.add(VoucherSignerKey.parse(key, property));
            }
        });
        return Set.copyOf(parsed);
    }

    private static boolean isConfigured(String key) {
        return key != null && !key.isBlank();
    }

    private static String normaliseIssuerId(String issuerId) {
        return issuerId.toLowerCase(Locale.ROOT);
    }
}
