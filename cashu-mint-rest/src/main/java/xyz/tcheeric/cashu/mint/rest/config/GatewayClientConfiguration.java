package xyz.tcheeric.cashu.mint.rest.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;

/**
 * Configuration for gateway HTTP clients optimized for virtual thread workloads.
 *
 * <p>This configuration provides a RestTemplate using JDK 11+ HttpClient, which
 * integrates well with virtual threads. The HttpClient is configured with a
 * virtual thread executor for optimal VT compatibility.
 *
 * <h2>Configuration Properties</h2>
 * <ul>
 *   <li>{@code gateway.client.connect-timeout} - Connection timeout (default: 5s)</li>
 *   <li>{@code gateway.client.read-timeout} - Socket read timeout (default: 30s)</li>
 * </ul>
 *
 * <h2>Virtual Thread Optimization</h2>
 * <p>The HttpClient is configured with a virtual thread executor, ensuring that
 * all async operations use virtual threads. This eliminates the need for large
 * connection pools since VTs can efficiently wait for I/O operations.
 *
 * <h2>Usage</h2>
 * <p>Inject the {@code gatewayRestTemplate} bean for gateway operations:
 * <pre>{@code
 * @Autowired
 * @Qualifier("gatewayRestTemplate")
 * private RestTemplate restTemplate;
 * }</pre>
 *
 * <h2>Note on External Gateway Clients</h2>
 * <p>The external {@code payment-adapter} project creates its own RestTemplate
 * instances. For full VT optimization of gateway calls, the payment-adapter
 * project should be updated to accept a configured RestTemplate or HttpClient.
 *
 * @see <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html">JDK HttpClient</a>
 */
@Configuration
public class GatewayClientConfiguration {

    @Value("${gateway.client.connect-timeout:5s}")
    private Duration connectTimeout;

    @Value("${gateway.client.read-timeout:30s}")
    private Duration readTimeout;

    /**
     * Creates a RestTemplate configured for gateway operations using JDK HttpClient.
     *
     * <p>Features:
     * <ul>
     *   <li>JDK HttpClient with virtual thread executor</li>
     *   <li>Explicit connect timeout</li>
     *   <li>HTTP/1.1 protocol (explicit for compatibility)</li>
     * </ul>
     *
     * @param builder Spring Boot's RestTemplateBuilder for base configuration
     * @return configured RestTemplate for gateway calls
     */
    @Bean
    public RestTemplate gatewayRestTemplate(RestTemplateBuilder builder) {
        // Build JDK HttpClient with VT executor and timeouts
        HttpClient httpClient = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(connectTimeout)
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        // Create request factory using JDK HttpClient
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        return builder
                .requestFactory(() -> requestFactory)
                .build();
    }
}
