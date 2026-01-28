package xyz.tcheeric.cashu.mint.rest.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import xyz.tcheeric.cashu.mint.rest.ws.Nut17WebSocketHandler;

import java.util.Arrays;

/**
 * WebSocket configuration for NUT-17 subscriptions.
 *
 * <p>Enable with {@code cashu.websocket.enabled=true} (default: true).
 * <p>Configure allowed origins with {@code cashu.websocket.allowed-origins} (default: "*").
 * For production, restrict to specific origins (e.g., "https://example.com").
 */
@Slf4j
@Configuration
@EnableWebSocket
@ConditionalOnProperty(name = "cashu.websocket.enabled", havingValue = "true", matchIfMissing = true)
public class WebSocketConfig implements WebSocketConfigurer {

    private final Nut17WebSocketHandler nut17WebSocketHandler;
    private final Environment environment;

    @Value("${cashu.websocket.allowed-origins:*}")
    private String allowedOrigins;

    public WebSocketConfig(Nut17WebSocketHandler nut17WebSocketHandler, Environment environment) {
        this.nut17WebSocketHandler = nut17WebSocketHandler;
        this.environment = environment;
    }

    @PostConstruct
    public void warnIfWildcardOrigins() {
        if ("*".equals(allowedOrigins.trim())) {
            String[] activeProfiles = environment.getActiveProfiles();
            boolean isDev = Arrays.asList(activeProfiles).contains("dev")
                    || Arrays.asList(activeProfiles).contains("test");
            if (!isDev) {
                log.warn("SECURITY WARNING: WebSocket allowed-origins is set to '*' (all origins). " +
                        "This allows any website to connect to your mint's WebSocket endpoint. " +
                        "For production, set cashu.websocket.allowed-origins to specific trusted origins.");
            }
        }
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(nut17WebSocketHandler, "/v1/ws")
                .setAllowedOrigins(allowedOrigins.split(","));
    }

    @Bean
    @ConditionalOnProperty(name = "cashu.websocket.enabled", havingValue = "true", matchIfMissing = true)
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(64 * 1024);
        container.setMaxBinaryMessageBufferSize(64 * 1024);
        container.setMaxSessionIdleTimeout(300000L); // 5 minutes
        return container;
    }
}
