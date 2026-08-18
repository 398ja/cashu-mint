package xyz.tcheeric.cashu.mint.observability.config;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import xyz.tcheeric.cashu.mint.observability.aspect.TaskTimingAspect;
import xyz.tcheeric.cashu.mint.observability.health.GatewayHealthIndicator;
import xyz.tcheeric.cashu.mint.observability.health.VaultHealthIndicator;
import xyz.tcheeric.cashu.mint.observability.interceptor.MetricsHandlerInterceptor;
import xyz.tcheeric.cashu.mint.observability.metrics.MicrometerMeltMetricsRecorder;
import xyz.tcheeric.cashu.mint.observability.metrics.MicrometerInvariantMetricsRecorder;
import xyz.tcheeric.cashu.mint.observability.metrics.MicrometerIssuanceMetricsRecorder;
import xyz.tcheeric.cashu.mint.observability.metrics.MicrometerVoucherMetricsRecorder;
import xyz.tcheeric.cashu.mint.observability.metrics.MicrometerWebhookMetricsRecorder;
import xyz.tcheeric.cashu.mint.observability.metrics.MicrometerTaskMetricsAdapter;
import xyz.tcheeric.cashu.mint.observability.metrics.TaskMetrics;
import xyz.tcheeric.cashu.mint.observability.metrics.LockMetrics;
import xyz.tcheeric.cashu.mint.observability.metrics.MicrometerLockMetricsAdapter;
import xyz.tcheeric.cashu.mint.proto.metrics.LockMetricsAdapter;
import xyz.tcheeric.cashu.mint.proto.metrics.LockMetricsRecorder;
import xyz.tcheeric.cashu.mint.proto.metrics.MeltMetricsRecorder;
import xyz.tcheeric.cashu.mint.proto.metrics.MetricRecorders;
import xyz.tcheeric.cashu.mint.proto.metrics.TaskExecutionRecorder;
import xyz.tcheeric.cashu.mint.proto.metrics.InvariantMetricsRecorder;
import xyz.tcheeric.cashu.mint.proto.metrics.IssuanceMetricsRecorder;
import xyz.tcheeric.cashu.mint.proto.metrics.VoucherMetricsRecorder;
import xyz.tcheeric.cashu.mint.proto.metrics.WebhookMetricsRecorder;
import xyz.tcheeric.cashu.mint.proto.metrics.TaskMetricsAdapter;

/**
 * Auto-configuration for Cashu Mint observability features.
 *
 * <p>This configuration is automatically applied when:
 * <ul>
 *   <li>Micrometer is on the classpath ({@link MeterRegistry})</li>
 *   <li>The property {@code cashu.observability.enabled} is true (default)</li>
 * </ul>
 *
 * <p>To disable observability, set {@code cashu.observability.enabled=false}.
 */
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(name = "cashu.observability.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ObservabilityProperties.class)
@EnableAspectJAutoProxy
@Import(MetricsWebMvcConfiguration.class)
@Slf4j
public class ObservabilityAutoConfiguration {

    /**
     * Creates the TaskMetrics bean for tracking task executions.
     *
     * @param registry the Micrometer registry (auto-configured by Spring Boot)
     * @return the TaskMetrics instance
     */
    @Bean
    @ConditionalOnMissingBean
    public TaskMetrics taskMetrics(MeterRegistry registry) {
        log.info("Initializing Cashu Mint task metrics");
        return new TaskMetrics(registry);
    }

    /**
     * Creates the TaskTimingAspect for AOP-based task instrumentation.
     *
     * <p>Can be disabled by setting {@code cashu.observability.tasks.enabled=false}.
     *
     * @param taskMetrics the task metrics instance
     * @return the TaskTimingAspect instance
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "cashu.observability.tasks.enabled", havingValue = "true", matchIfMissing = true)
    public TaskTimingAspect taskTimingAspect(TaskMetrics taskMetrics) {
        log.info("Initializing Cashu Mint task timing aspect");
        return new TaskTimingAspect(taskMetrics);
    }

    /**
     * Registers a Micrometer-backed adapter so protocol tasks created with {@code new}
     * still emit timing and outcome metrics (without relying on proxy-based AOP).
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "cashu.observability.tasks.enabled", havingValue = "true", matchIfMissing = true)
    public TaskMetricsAdapter taskMetricsAdapter(TaskMetrics taskMetrics) {
        log.info("Registering task metrics adapter for protocol task instrumentation");
        TaskMetricsAdapter adapter = new MicrometerTaskMetricsAdapter(taskMetrics);
        TaskExecutionRecorder.register(adapter);
        return adapter;
    }

    /**
     * Creates the LockMetrics bean for tracking lock wait and hold times.
     *
     * <p>Monitors per-quote and per-proof locks used to prevent double-mint attacks.
     * Essential for virtual thread performance monitoring.
     *
     * @param registry the Micrometer registry
     * @return the LockMetrics instance
     */
    @Bean
    @ConditionalOnMissingBean
    public LockMetrics lockMetrics(MeterRegistry registry) {
        log.info("Initializing Cashu Mint lock metrics");
        return new LockMetrics(registry);
    }

    /**
     * Registers a Micrometer-backed adapter so lock managers emit metrics to Prometheus.
     *
     * <p>Can be disabled by setting {@code cashu.observability.locks.enabled=false}.
     *
     * @param lockMetrics the lock metrics instance
     * @return the lock metrics adapter
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "cashu.observability.locks.enabled", havingValue = "true", matchIfMissing = true)
    public LockMetricsAdapter lockMetricsAdapter(LockMetrics lockMetrics) {
        log.info("Registering lock metrics adapter for protocol lock instrumentation");
        LockMetricsAdapter adapter = new MicrometerLockMetricsAdapter(lockMetrics);
        LockMetricsRecorder.register(adapter);
        return adapter;
    }

    /**
     * Registers the melt-area recorder so {@code MeltTask} emits its counters
     * through a typed port instead of a raw registry (ADR 0001).
     *
     * @param registry the Micrometer registry
     * @return the melt metrics recorder
     */
    @Bean
    @ConditionalOnMissingBean
    public MeltMetricsRecorder meltMetricsRecorder(MeterRegistry registry) {
        log.info("Registering melt metrics recorder for protocol melt instrumentation");
        MeltMetricsRecorder recorder = new MicrometerMeltMetricsRecorder(registry);
        MetricRecorders.registerMelt(recorder);
        return recorder;
    }

    /**
     * Registers the voucher-area recorder so the voucher path emits its
     * counters through a typed port instead of a raw registry (ADR 0001,
     * issue #341).
     *
     * @param registry the Micrometer registry
     * @return the voucher metrics recorder
     */
    @Bean
    @ConditionalOnMissingBean
    public VoucherMetricsRecorder voucherMetricsRecorder(MeterRegistry registry) {
        log.info("Registering voucher metrics recorder for voucher instrumentation");
        VoucherMetricsRecorder recorder = new MicrometerVoucherMetricsRecorder(registry);
        MetricRecorders.registerVoucher(recorder);
        return recorder;
    }

    /**
     * Registers the mint/issuance recorder so {@code MintTask} and the
     * issuance rate-limit filter emit through a typed port (ADR 0001,
     * issue #342).
     *
     * @param registry the Micrometer registry
     * @return the issuance metrics recorder
     */
    @Bean
    @ConditionalOnMissingBean
    public IssuanceMetricsRecorder issuanceMetricsRecorder(MeterRegistry registry) {
        log.info("Registering issuance metrics recorder for mint-path instrumentation");
        IssuanceMetricsRecorder recorder = new MicrometerIssuanceMetricsRecorder(registry);
        MetricRecorders.registerIssuance(recorder);
        return recorder;
    }

    /**
     * Registers the webhook recorder so the delivery path emits through a
     * typed port (ADR 0001, issue #342).
     *
     * @param registry the Micrometer registry
     * @return the webhook metrics recorder
     */
    @Bean
    @ConditionalOnMissingBean
    public WebhookMetricsRecorder webhookMetricsRecorder(MeterRegistry registry) {
        log.info("Registering webhook metrics recorder for webhook delivery instrumentation");
        WebhookMetricsRecorder recorder = new MicrometerWebhookMetricsRecorder(registry);
        MetricRecorders.registerWebhook(recorder);
        return recorder;
    }

    /**
     * Registers the invariant gauge recorder so the DB-derived poller in
     * {@code cashu-mint-jpa} declares its gauges here rather than reaching for
     * a raw registry (ADR 0002, issue #343).
     *
     * @param registry the Micrometer registry
     * @return the invariant metrics recorder
     */
    @Bean
    @ConditionalOnMissingBean
    public InvariantMetricsRecorder invariantMetricsRecorder(MeterRegistry registry) {
        log.info("Registering invariant metrics recorder for DB-derived gauges");
        InvariantMetricsRecorder recorder = new MicrometerInvariantMetricsRecorder(registry);
        MetricRecorders.registerInvariant(recorder);
        return recorder;
    }

    /**
     * Creates the MetricsHandlerInterceptor for HTTP request metrics.
     *
     * <p>Only created in web application context.
     *
     * @param registry the Micrometer registry
     * @return the MetricsHandlerInterceptor instance
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public MetricsHandlerInterceptor metricsHandlerInterceptor(MeterRegistry registry) {
        log.info("Initializing Cashu Mint HTTP request metrics interceptor");
        return new MetricsHandlerInterceptor(registry);
    }

    /**
     * Creates the GatewayHealthIndicator for monitoring gateway connectivity.
     *
     * <p>Can be disabled by setting {@code cashu.observability.health.gateway.enabled=false}.
     *
     * @param properties observability configuration properties
     * @return the GatewayHealthIndicator instance
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "cashu.observability.health.gateway.enabled", havingValue = "true", matchIfMissing = true)
    public GatewayHealthIndicator gatewayHealthIndicator(ObservabilityProperties properties) {
        log.info("Initializing Cashu Mint gateway health indicator");
        return new GatewayHealthIndicator(properties.getHealth().getGateway().getTimeoutMs());
    }

    /**
     * Creates the VaultHealthIndicator for monitoring vault connectivity.
     *
     * <p>Can be disabled by setting {@code cashu.observability.health.vault.enabled=false}.
     *
     * @param properties observability configuration properties
     * @return the VaultHealthIndicator instance
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "cashu.observability.health.vault.enabled", havingValue = "true", matchIfMissing = true)
    public VaultHealthIndicator vaultHealthIndicator(ObservabilityProperties properties) {
        log.info("Initializing Cashu Mint vault health indicator");
        return new VaultHealthIndicator(properties.getHealth().getVault().getTimeoutMs());
    }
}
