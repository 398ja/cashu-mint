package xyz.tcheeric.cashu.mint.jpa;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.mint.proto.ports.IssuanceRecordRepository;
import xyz.tcheeric.cashu.mint.proto.ports.LightningPaymentPort;
import xyz.tcheeric.cashu.mint.proto.ports.MeltSagaRepository;
import xyz.tcheeric.cashu.mint.proto.ports.MintIntegrityContext;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuoteRepository;

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

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    @Autowired(required = false)
    private LightningPaymentPort lightningPaymentPort;

    @Value("${cashu.mint.url:}")
    private String mintUrl;

    @Value("${cashu.mint.melt.payment-timeout:PT30S}")
    private Duration meltPaymentTimeout;

    @PostConstruct
    void install() {
        MintIntegrityContext.install(quoteRepository, issuanceRecordRepository, meterRegistry,
                mintUrl != null ? mintUrl : "");
        MintIntegrityContext.installMelt(meltSagaRepository, lightningPaymentPort, meltPaymentTimeout);
        log.info("MintIntegrityContext installed (quoteRepo={}, issuanceRepo={}, meltSagaRepo={}, lightningPort={}, meterRegistry={}, mintUrl={}, meltTimeout={})",
                quoteRepository != null, issuanceRecordRepository != null,
                meltSagaRepository != null, lightningPaymentPort != null,
                meterRegistry != null, mintUrl, meltPaymentTimeout);
    }

    @PreDestroy
    void clear() {
        MintIntegrityContext.clear();
    }
}
