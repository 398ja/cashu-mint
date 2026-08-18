package xyz.tcheeric.cashu.mint.proto.ports;

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
 * be wired (a mint booted without {@code cashu.mint.jpa.enabled} has none of
 * them). Consumers MUST null-check.
 *
 * <p>Metrics are deliberately absent. This context used to hand out a raw
 * {@link io.micrometer.core.instrument.MeterRegistry}, which let any call site
 * invent a metric name with nothing tying the declaration to a consumer — the
 * root defect behind issue #337. Metrics now reach domain code only through
 * the typed recorder ports in
 * {@code xyz.tcheeric.cashu.mint.proto.metrics}; the accessor was deleted
 * rather than deprecated (issue #343), because a deprecated one preserves the
 * exact hole this work exists to close.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MintIntegrityContext {

    private static volatile MintQuoteRepository quoteRepository;
    private static volatile IssuanceRecordRepository issuanceRecordRepository;
    private static volatile MeltSagaRepository meltSagaRepository;
    private static volatile LightningPaymentPort lightningPaymentPort;
    private static volatile String mintUrl;
    private static volatile Duration meltPaymentTimeout;
    private static volatile VoucherQuoteRepository voucherQuoteRepository;
    private static volatile VoucherFundingRepository voucherFundingRepository;
    private static volatile VoucherIssuanceRepository voucherIssuanceRepository;
    private static volatile VoucherFundingResolver voucherFundingResolver;
    private static volatile IdentityHasher identityHasher;
    private static volatile String voucherIouPolicy;
    private static volatile String activeProfile;

    /** Installs the spec-001 portion of the context. Idempotent. */
    public static void install(MintQuoteRepository quoteRepository,
                               IssuanceRecordRepository issuanceRecordRepository,
                               String mintUrl) {
        MintIntegrityContext.quoteRepository = quoteRepository;
        MintIntegrityContext.issuanceRecordRepository = issuanceRecordRepository;
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

    /**
     * Spec 003 — installs the voucher durability + funding components.
     * Idempotent; non-null overwrites. {@code iouPolicy} is the string
     * form of {@code VoucherDurabilityProperties.IouPolicy} so the
     * protocol module does not depend on the REST module.
     */
    public static void installVoucher(VoucherQuoteRepository voucherQuoteRepository,
                                      VoucherFundingRepository voucherFundingRepository,
                                      VoucherIssuanceRepository voucherIssuanceRepository,
                                      VoucherFundingResolver voucherFundingResolver,
                                      String voucherIouPolicy,
                                      String activeProfile) {
        MintIntegrityContext.voucherQuoteRepository = voucherQuoteRepository;
        MintIntegrityContext.voucherFundingRepository = voucherFundingRepository;
        MintIntegrityContext.voucherIssuanceRepository = voucherIssuanceRepository;
        MintIntegrityContext.voucherFundingResolver = voucherFundingResolver;
        MintIntegrityContext.voucherIouPolicy = voucherIouPolicy;
        MintIntegrityContext.activeProfile = activeProfile;
    }

    /**
     * Spec 004 — installs the {@link IdentityHasher}. Hibernate
     * instantiates JPA {@code @Converter} classes outside the Spring
     * container, so the converter looks up the hasher via this static
     * accessor rather than constructor injection. Null in legacy
     * unit-test contexts means "no hashing" — the column passes
     * through, and the production fail-closed boot validator in
     * {@code HmacSha256IdentityHasher} ensures any JPA-enabled deploy
     * has the hasher wired.
     */
    public static void installIdentityHasher(IdentityHasher identityHasher) {
        MintIntegrityContext.identityHasher = identityHasher;
    }

    /** Resets the context. Test-only — clears all references. */
    public static void clear() {
        quoteRepository = null;
        issuanceRecordRepository = null;
        meltSagaRepository = null;
        lightningPaymentPort = null;
        mintUrl = null;
        meltPaymentTimeout = null;
        voucherQuoteRepository = null;
        voucherFundingRepository = null;
        voucherIssuanceRepository = null;
        voucherFundingResolver = null;
        identityHasher = null;
        voucherIouPolicy = null;
        activeProfile = null;
    }

    public static IdentityHasher identityHasher() {
        return identityHasher;
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

    public static String mintUrl() {
        return mintUrl;
    }

    public static Duration meltPaymentTimeout() {
        return meltPaymentTimeout;
    }

    public static VoucherQuoteRepository voucherQuoteRepository() {
        return voucherQuoteRepository;
    }

    public static VoucherFundingRepository voucherFundingRepository() {
        return voucherFundingRepository;
    }

    public static VoucherIssuanceRepository voucherIssuanceRepository() {
        return voucherIssuanceRepository;
    }

    public static VoucherFundingResolver voucherFundingResolver() {
        return voucherFundingResolver;
    }

    public static String voucherIouPolicy() {
        return voucherIouPolicy;
    }

    public static String activeProfile() {
        return activeProfile;
    }
}
