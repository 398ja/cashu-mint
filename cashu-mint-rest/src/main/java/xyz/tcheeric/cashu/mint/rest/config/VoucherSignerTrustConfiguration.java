package xyz.tcheeric.cashu.mint.rest.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.tcheeric.cashu.mint.proto.voucher.InstalledVoucherIssuerBinding;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherIssuerBinding;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherIssuerBindingMode;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherSignerTrustList;

import java.util.Map;

/**
 * Builds the voucher signer trust list once and binds it into the swap path (cashu-mint#527).
 *
 * <p>Unconditional, unlike {@link VoucherConfiguration}: voucher proofs reach {@code POST
 * /v1/swap} whatever {@code voucher.enabled} says, so the binding has to exist either way. The
 * merchant-verification service, which only exists when vouchers are enabled, reads the same
 * {@link VoucherSignerTrustList} bean rather than parsing the configuration a second time.
 *
 * <p>The binding is installed into {@link InstalledVoucherIssuerBinding} because the swap tasks
 * are built with {@code new} inside the static NUT helpers and cannot take a Spring bean directly.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(VoucherSignerTrustProperties.class)
public class VoucherSignerTrustConfiguration {

    /** The property holding the key the mint signs its own vouchers with (POST /v1/vouchers). */
    static final String MINT_VOUCHER_ISSUER_KEY_PROPERTY = "voucher.mint.issuerPublicKey";

    /**
     * The parsed issuer keys and trusted signers. A malformed key fails the boot here, naming it.
     *
     * <p>The mint's own voucher issuer key is added to the trusted signers. When
     * {@code voucher.enabled=true}, {@code POST /v1/vouchers} signs every voucher with that key
     * under whatever {@code issuerId} the caller named, so without it {@code enforce} would refuse
     * the mint's own vouchers.
     *
     * @param properties the bound configuration
     * @param mintVoucherIssuerKey the mint's own voucher signing key, blank when it issues none
     * @return the trust list every voucher check reads
     */
    @Bean
    public VoucherSignerTrustList voucherSignerTrustList(
            VoucherSignerTrustProperties properties,
            @Value("${" + MINT_VOUCHER_ISSUER_KEY_PROPERTY + ":}") String mintVoucherIssuerKey) {
        VoucherSignerTrustList trustList = new VoucherSignerTrustList(
                properties.getIssuerKeys(), properties.getTrustedSigners(),
                Map.of(MINT_VOUCHER_ISSUER_KEY_PROPERTY, mintVoucherIssuerKey));
        log.info("voucher_signer_trust_list issuerKeys={} trustedSigners={} mintOwnKeyTrusted={}",
                trustList.issuerKeyCount(), trustList.trustedSignerCount(),
                !mintVoucherIssuerKey.isBlank());
        return trustList;
    }

    /**
     * The binding the swap path applies, installed for the spending conditions to find.
     *
     * <p>Refuses to build, and so refuses to boot, in {@code enforce} with nothing configured.
     *
     * @param properties the bound configuration, for the mode
     * @param trustList  the shared trust list
     * @return the installed binding
     */
    @Bean
    public VoucherIssuerBinding voucherIssuerBinding(VoucherSignerTrustProperties properties,
                                                     VoucherSignerTrustList trustList) {
        VoucherIssuerBinding binding = new VoucherIssuerBinding(properties.getIssuerBinding(), trustList);
        InstalledVoucherIssuerBinding.install(binding);
        warnWhenLoggingAgainstNothing(binding.mode(), trustList);
        log.info("voucher_issuer_binding installed mode={}", binding.mode().label());
        return binding;
    }

    private static void warnWhenLoggingAgainstNothing(VoucherIssuerBindingMode mode,
                                                      VoucherSignerTrustList trustList) {
        if (mode == VoucherIssuerBindingMode.LOG && trustList.isEmpty()) {
            log.warn("voucher_issuer_binding mode=log with no cashu.mint.voucher.issuer-keys.* and "
                    + "no cashu.mint.voucher.trusted-signers: every signed voucher will be reported "
                    + "as voucher_issuer_untrusted. Configure the signing keys before enforce.");
        }
    }
}
