package xyz.tcheeric.cashu.mint.observability.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import xyz.tcheeric.cashu.mint.observability.interceptor.MetricsHandlerInterceptor;

/**
 * Web MVC configuration for registering the metrics interceptor.
 *
 * <p>This configuration is only activated when:
 * <ul>
 *   <li>Running in a web application context</li>
 *   <li>Spring MVC is on the classpath</li>
 *   <li>The property {@code cashu.observability.enabled} is true (default)</li>
 * </ul>
 *
 * <p>The interceptor is registered for all paths but only tracks /v1/* endpoints
 * to avoid polluting metrics with actuator or other internal endpoints.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(WebMvcConfigurer.class)
@ConditionalOnProperty(name = "cashu.observability.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class MetricsWebMvcConfiguration implements WebMvcConfigurer {

    private final MetricsHandlerInterceptor metricsInterceptor;

    /**
     * Creates a new MetricsWebMvcConfiguration.
     *
     * @param metricsInterceptor the metrics interceptor to register
     */
    public MetricsWebMvcConfiguration(MetricsHandlerInterceptor metricsInterceptor) {
        this.metricsInterceptor = metricsInterceptor;
        log.info("MetricsWebMvcConfiguration initialized");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Register interceptor for Cashu API endpoints only
        registry.addInterceptor(metricsInterceptor)
                .addPathPatterns("/v1/**")
                .excludePathPatterns(
                        "/actuator/**",   // Exclude actuator endpoints
                        "/error",          // Exclude error endpoint
                        "/swagger-ui/**",  // Exclude Swagger UI
                        "/v3/api-docs/**"  // Exclude OpenAPI docs
                );

        log.debug("Metrics interceptor registered for /v1/** endpoints");
    }
}
