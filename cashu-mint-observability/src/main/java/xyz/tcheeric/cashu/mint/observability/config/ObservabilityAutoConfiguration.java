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
import xyz.tcheeric.cashu.mint.observability.metrics.GatewayMetrics;
import xyz.tcheeric.cashu.mint.observability.metrics.MintMetrics;
import xyz.tcheeric.cashu.mint.observability.metrics.QuoteMetrics;
import xyz.tcheeric.cashu.mint.observability.metrics.MicrometerTaskMetricsAdapter;
import xyz.tcheeric.cashu.mint.observability.metrics.TaskMetrics;
import xyz.tcheeric.cashu.mint.observability.metrics.VoucherMetrics;
import xyz.tcheeric.cashu.mint.proto.metrics.TaskExecutionRecorder;
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
     * Creates the core MintMetrics bean for tracking mint operations.
     *
     * @param registry the Micrometer registry (auto-configured by Spring Boot)
     * @param properties observability configuration properties
     * @return the MintMetrics instance
     */
    @Bean
    @ConditionalOnMissingBean
    public MintMetrics mintMetrics(MeterRegistry registry, ObservabilityProperties properties) {
        log.info("Initializing Cashu Mint observability metrics");
        return new MintMetrics(registry, properties.getMetrics().isTrackKeysets());
    }

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
     * Creates the QuoteMetrics bean for tracking mint/melt quote operations.
     *
     * @param registry the Micrometer registry
     * @return the QuoteMetrics instance
     */
    @Bean
    @ConditionalOnMissingBean
    public QuoteMetrics quoteMetrics(MeterRegistry registry) {
        log.info("Initializing Cashu Mint quote metrics");
        return new QuoteMetrics(registry);
    }

    /**
     * Creates the VoucherMetrics bean for tracking voucher operations.
     *
     * <p>Can be disabled by setting {@code cashu.observability.vouchers.enabled=false}.
     *
     * @param registry the Micrometer registry
     * @return the VoucherMetrics instance
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "cashu.observability.vouchers.enabled", havingValue = "true", matchIfMissing = true)
    public VoucherMetrics voucherMetrics(MeterRegistry registry) {
        log.info("Initializing Cashu Mint voucher metrics");
        return new VoucherMetrics(registry);
    }

    /**
     * Creates the GatewayMetrics bean for tracking Lightning gateway operations.
     *
     * <p>Can be disabled by setting {@code cashu.observability.gateway.enabled=false}.
     *
     * @param registry the Micrometer registry
     * @return the GatewayMetrics instance
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "cashu.observability.gateway.enabled", havingValue = "true", matchIfMissing = true)
    public GatewayMetrics gatewayMetrics(MeterRegistry registry) {
        log.info("Initializing Cashu Mint gateway metrics");
        return new GatewayMetrics(registry);
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
