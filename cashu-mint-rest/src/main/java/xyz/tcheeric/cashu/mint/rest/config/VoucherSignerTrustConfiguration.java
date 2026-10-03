package xyz.tcheeric.cashu.mint.rest.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.tcheeric.cashu.mint.proto.ports.MintIntegrityContext;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherIssuerBinding;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherIssuerBindingMode;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherSignerTrustList;

/**
 * Builds the voucher signer trust list once and binds it into the swap path (cashu-mint#527).
 *
 * <p>Unconditional, unlike {@link VoucherConfiguration}: voucher proofs reach {@code POST
 * /v1/swap} whatever {@code voucher.enabled} says, so the binding has to exist either way. The
 * merchant-verification service, which only exists when vouchers are enabled, reads the same
 * {@link VoucherSignerTrustList} bean rather than parsing the configuration a second time.
 *
 * <p>The binding is installed into {@link MintIntegrityContext} because the swap tasks are built
 * with {@code new} inside the static NUT helpers and cannot take a Spring bean directly; that is
 * the same path the durable repositories already travel.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(VoucherSignerTrustProperties.class)
public class VoucherSignerTrustConfiguration {

    /**
     * The parsed issuer keys and trusted signers. A malformed key fails the boot here, naming it.
     *
     * @param properties the bound configuration
     * @return the trust list every voucher check reads
     */
    @Bean
    public VoucherSignerTrustList voucherSignerTrustList(VoucherSignerTrustProperties properties) {
        VoucherSignerTrustList trustList = new VoucherSignerTrustList(
                properties.getIssuerKeys(), properties.getTrustedSigners());
        log.info("voucher_signer_trust_list issuerKeys={} trustedSigners={}",
                trustList.issuerKeyCount(), trustList.trustedSignerCount());
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
        MintIntegrityContext.installVoucherIssuerBinding(binding);
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
