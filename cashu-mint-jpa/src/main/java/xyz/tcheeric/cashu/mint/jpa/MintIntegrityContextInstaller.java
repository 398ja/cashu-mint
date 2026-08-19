package xyz.tcheeric.cashu.mint.jpa;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import xyz.tcheeric.cashu.mint.proto.ports.IssuanceRecordRepository;
import xyz.tcheeric.cashu.mint.proto.ports.LightningPaymentPort;
import xyz.tcheeric.cashu.mint.proto.ports.MeltSagaRepository;
import xyz.tcheeric.cashu.mint.proto.ports.MintIntegrityContext;
import xyz.tcheeric.cashu.mint.proto.ports.MintSuspensionRepository;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuoteRepository;
import xyz.tcheeric.cashu.mint.proto.ports.IdentityHasher;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherFundingRepository;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherFundingResolver;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherIssuanceRepository;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuoteRepository;

import java.time.Duration;

/**
 * Populates {@link MintIntegrityContext} on Spring bootstrap so the static
 * {@code NUT04} helpers can read the durable spec-001 repositories without
 * threading them through every static method signature.
 *
 * <p>Activated only when {@code cashu.mint.jpa.enabled=true} (alongside the
 * rest of {@link MintJpaAutoConfiguration}).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class MintIntegrityContextInstaller {

    private final MintQuoteRepository quoteRepository;
    private final IssuanceRecordRepository issuanceRecordRepository;
    private final MeltSagaRepository meltSagaRepository;
    private final VoucherQuoteRepository voucherQuoteRepository;
    private final VoucherFundingRepository voucherFundingRepository;
    private final VoucherIssuanceRepository voucherIssuanceRepository;
    private final VoucherFundingResolver voucherFundingResolver;
    private final IdentityHasher identityHasher;
    private final MintSuspensionRepository mintSuspensionRepository;
    private final Environment environment;

    @Autowired(required = false)
    private LightningPaymentPort lightningPaymentPort;

    @Value("${cashu.mint.url:}")
    private String mintUrl;

    @Value("${cashu.mint.melt.payment-timeout:PT30S}")
    private Duration meltPaymentTimeout;

    @Value("${cashu.mint.voucher.iou-policy:DENY}")
    private String voucherIouPolicy;

    @PostConstruct
    void install() {
        MintIntegrityContext.install(quoteRepository, issuanceRecordRepository,
                mintUrl != null ? mintUrl : "");
        MintIntegrityContext.installMelt(meltSagaRepository, lightningPaymentPort, meltPaymentTimeout);
        String[] active = environment.getActiveProfiles();
        String activeProfile = active.length == 0 ? "default" : active[0];
        MintIntegrityContext.installVoucher(voucherQuoteRepository, voucherFundingRepository,
                voucherIssuanceRepository, voucherFundingResolver, voucherIouPolicy, activeProfile);
        MintIntegrityContext.installIdentityHasher(identityHasher);
        MintIntegrityContext.installMintSuspension(mintSuspensionRepository);
        log.info("MintIntegrityContext installed (quoteRepo={}, issuanceRepo={}, meltSagaRepo={}, lightningPort={}, mintUrl={}, meltTimeout={}, voucherIouPolicy={}, activeProfile={}, identityHasher={}, mintSuspensionRepo={})",
                quoteRepository != null, issuanceRecordRepository != null,
                meltSagaRepository != null, lightningPaymentPort != null,
                mintUrl, meltPaymentTimeout,
                voucherIouPolicy, activeProfile, identityHasher != null, mintSuspensionRepository != null);
    }

    @PreDestroy
    void clear() {
        MintIntegrityContext.clear();
    }
}
