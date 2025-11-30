package xyz.tcheeric.cashu.mint.observability.tracing;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.semconv.ResourceAttributes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import xyz.tcheeric.cashu.mint.observability.config.ObservabilityProperties;

/**
 * Auto-configuration for OpenTelemetry distributed tracing.
 *
 * <p>This configuration is activated when:
 * <ul>
 *   <li>OpenTelemetry SDK classes are on the classpath</li>
 *   <li>The property {@code cashu.observability.tracing.enabled} is true</li>
 * </ul>
 *
 * <p>Traces are exported to an OTLP endpoint (default: localhost:4317).
 * Configure the endpoint with {@code cashu.observability.tracing.endpoint}.
 *
 * <p>Usage with Jaeger:
 * <pre>
 * docker run -d --name jaeger \
 *   -e COLLECTOR_OTLP_ENABLED=true \
 *   -p 16686:16686 \
 *   -p 4317:4317 \
 *   jaegertracing/all-in-one:latest
 * </pre>
 */
@AutoConfiguration
@ConditionalOnClass({OpenTelemetry.class, OtelTracer.class})
@ConditionalOnProperty(name = "cashu.observability.tracing.enabled", havingValue = "true")
@EnableConfigurationProperties(ObservabilityProperties.class)
@Slf4j
public class TracingAutoConfiguration {

    /**
     * Creates the OpenTelemetry SDK configured for Cashu Mint.
     *
     * @param properties the observability properties
     * @return the configured OpenTelemetry instance
     */
    @Bean
    @ConditionalOnMissingBean
    public OpenTelemetry openTelemetry(ObservabilityProperties properties) {
        var tracingProps = properties.getTracing();

        log.info("Initializing OpenTelemetry tracing with endpoint: {}", tracingProps.getEndpoint());

        // Build resource with service info
        Resource resource = Resource.getDefault()
                .merge(Resource.create(Attributes.of(
                        ResourceAttributes.SERVICE_NAME, tracingProps.getServiceName(),
                        ResourceAttributes.SERVICE_VERSION, "0.4.0",
                        ResourceAttributes.DEPLOYMENT_ENVIRONMENT, tracingProps.getEnvironment()
                )));

        // Configure OTLP exporter
        OtlpGrpcSpanExporter spanExporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint(tracingProps.getEndpoint())
                .build();

        // Configure sampler based on ratio
        Sampler sampler = Sampler.traceIdRatioBased(tracingProps.getSamplingRatio());

        // Build tracer provider
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
                .setResource(resource)
                .setSampler(sampler)
                .build();

        // Build OpenTelemetry SDK
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(tracerProvider::close));

        log.info("OpenTelemetry tracing initialized with service name: {}", tracingProps.getServiceName());

        return openTelemetry;
    }

    /**
     * Creates the Micrometer Tracer using OpenTelemetry bridge.
     *
     * @param openTelemetry the OpenTelemetry instance
     * @return the Micrometer Tracer
     */
    @Bean
    @ConditionalOnMissingBean
    public Tracer tracer(OpenTelemetry openTelemetry) {
        io.opentelemetry.api.trace.Tracer otelTracer = openTelemetry.getTracer("cashu-mint");

        return new OtelTracer(
                otelTracer,
                new io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext(),
                event -> { }  // No-op baggage manager
        );
    }
}
