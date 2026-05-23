package xyz.tcheeric.cashu.mint.proto.ports;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;

/**
 * Static service-locator that exposes the spec-001 durable repositories and
 * Micrometer registry to the {@link xyz.tcheeric.cashu.mint.proto.nut.NUT04}
 * static helpers (which can't take constructor dependencies). Mirrors the
 * existing {@code MintProtocolServiceFactory} singleton pattern in the
 * protocol module.
 *
 * <p>Production code installs the context once at Spring bootstrap via the
 * {@code MintIntegrityContextInstaller @Component} in cashu-mint-jpa.
 * Unit-test contexts that don't wire the JPA module leave the context empty
 * and the protocol tasks fall back to the legacy code path that doesn't
 * consult the durable repos.
 *
 * <p>All fields are nullable on purpose: any subset of the dependencies may
 * be wired (e.g. observability-only deploys could provide just the
 * {@link MeterRegistry}). Consumers MUST null-check.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MintIntegrityContext {

    private static volatile MintQuoteRepository quoteRepository;
    private static volatile IssuanceRecordRepository issuanceRecordRepository;
    private static volatile MeltSagaRepository meltSagaRepository;
    private static volatile LightningPaymentPort lightningPaymentPort;
    private static volatile MeterRegistry meterRegistry;
    private static volatile String mintUrl;
    private static volatile Duration meltPaymentTimeout;

    /** Installs the spec-001 portion of the context. Idempotent. */
    public static void install(MintQuoteRepository quoteRepository,
                               IssuanceRecordRepository issuanceRecordRepository,
                               MeterRegistry meterRegistry,
                               String mintUrl) {
        MintIntegrityContext.quoteRepository = quoteRepository;
        MintIntegrityContext.issuanceRecordRepository = issuanceRecordRepository;
        MintIntegrityContext.meterRegistry = meterRegistry;
        MintIntegrityContext.mintUrl = mintUrl;
    }

    /**
     * Spec 002 — installs the melt-saga driver components. Idempotent; only
     * the non-null arguments overwrite. Either the repository or the payment
     * port being null leaves {@link xyz.tcheeric.cashu.mint.proto.tasks.MeltTask}
     * on its legacy path; both must be non-null for the saga state machine
     * to run.
     */
    public static void installMelt(MeltSagaRepository meltSagaRepository,
                                   LightningPaymentPort lightningPaymentPort,
                                   Duration meltPaymentTimeout) {
        MintIntegrityContext.meltSagaRepository = meltSagaRepository;
        MintIntegrityContext.lightningPaymentPort = lightningPaymentPort;
        MintIntegrityContext.meltPaymentTimeout = meltPaymentTimeout;
    }

    /** Resets the context. Test-only — clears all references. */
    public static void clear() {
        quoteRepository = null;
        issuanceRecordRepository = null;
        meltSagaRepository = null;
        lightningPaymentPort = null;
        meterRegistry = null;
        mintUrl = null;
        meltPaymentTimeout = null;
    }

    public static MintQuoteRepository quoteRepository() {
        return quoteRepository;
    }

    public static IssuanceRecordRepository issuanceRecordRepository() {
        return issuanceRecordRepository;
    }

    public static MeltSagaRepository meltSagaRepository() {
        return meltSagaRepository;
    }

    public static LightningPaymentPort lightningPaymentPort() {
        return lightningPaymentPort;
    }

    public static MeterRegistry meterRegistry() {
        return meterRegistry;
    }

    public static String mintUrl() {
        return mintUrl;
    }

    public static Duration meltPaymentTimeout() {
        return meltPaymentTimeout;
    }
}
