package xyz.tcheeric.cashu.mint.observability.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Cashu Mint observability features.
 *
 * <p>These properties control various aspects of metrics collection,
 * health indicators, and instrumentation behavior.
 */
@ConfigurationProperties(prefix = "cashu.observability")
@Data
public class ObservabilityProperties {

    /**
     * Whether observability features are enabled globally.
     */
    private boolean enabled = true;

    /**
     * Task instrumentation settings.
     */
    private TasksProperties tasks = new TasksProperties();

    /**
     * Health indicator settings.
     */
    private HealthProperties health = new HealthProperties();

    /**
     * Metrics settings.
     */
    private MetricsProperties metrics = new MetricsProperties();

    /**
     * Voucher metrics settings.
     */
    private VouchersProperties vouchers = new VouchersProperties();

    /**
     * Gateway metrics settings.
     */
    private GatewayProperties gateway = new GatewayProperties();

    /**
     * Tracing settings.
     */
    private TracingProperties tracing = new TracingProperties();

    /**
     * Configuration for task instrumentation.
     */
    @Data
    public static class TasksProperties {
        /**
         * Whether task timing instrumentation is enabled.
         */
        private boolean enabled = true;
    }

    /**
     * Configuration for health indicators.
     */
    @Data
    public static class HealthProperties {
        /**
         * Gateway health indicator settings.
         */
        private GatewayHealthProperties gateway = new GatewayHealthProperties();

        /**
         * Vault health indicator settings.
         */
        private VaultHealthProperties vault = new VaultHealthProperties();
    }

    /**
     * Configuration for gateway health indicator.
     */
    @Data
    public static class GatewayHealthProperties {
        /**
         * Whether gateway health indicator is enabled.
         */
        private boolean enabled = true;

        /**
         * Timeout in milliseconds for gateway health check.
         */
        private long timeoutMs = 5000;
    }

    /**
     * Configuration for vault health indicator.
     */
    @Data
    public static class VaultHealthProperties {
        /**
         * Whether vault health indicator is enabled.
         */
        private boolean enabled = true;

        /**
         * Timeout in milliseconds for vault health check.
         */
        private long timeoutMs = 5000;
    }

    /**
     * Configuration for metrics collection.
     */
    @Data
    public static class MetricsProperties {
        /**
         * Common tags to apply to all metrics.
         */
        private String application = "cashu-mint";

        /**
         * Environment tag value.
         */
        private String environment = "local";

        /**
         * Whether to track individual keyset metrics (may increase cardinality).
         */
        private boolean trackKeysets = true;

        /**
         * Whether to include unit tag in business metrics.
         */
        private boolean includeUnitTag = true;
    }

    /**
     * Configuration for voucher metrics.
     */
    @Data
    public static class VouchersProperties {
        /**
         * Whether voucher metrics collection is enabled.
         */
        private boolean enabled = true;
    }

    /**
     * Configuration for gateway metrics.
     */
    @Data
    public static class GatewayProperties {
        /**
         * Whether gateway metrics collection is enabled.
         */
        private boolean enabled = true;
    }

    /**
     * Configuration for distributed tracing.
     */
    @Data
    public static class TracingProperties {
        /**
         * Whether distributed tracing is enabled.
         */
        private boolean enabled = false;

        /**
         * OTLP endpoint for trace export (e.g., http://localhost:4317).
         */
        private String endpoint = "http://localhost:4317";

        /**
         * Service name for traces.
         */
        private String serviceName = "cashu-mint";

        /**
         * Environment name for traces.
         */
        private String environment = "development";

        /**
         * Sampling ratio (0.0 to 1.0). Default is 1.0 (sample all traces).
         */
        private double samplingRatio = 1.0;
    }
}
