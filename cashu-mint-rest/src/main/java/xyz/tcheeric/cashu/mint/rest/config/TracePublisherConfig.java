package xyz.tcheeric.cashu.mint.rest.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.tcheeric.cashu.ledger.trace.publisher.OperationIdRegistry;
import xyz.tcheeric.cashu.ledger.trace.publisher.SqliteOperationIdRegistry;

/**
 * Spec 036 — trace producer wiring that the SDK starter does not provide.
 *
 * <p>The {@code cashu-ledger-trace-publisher} auto-configuration supplies the
 * outbox, signer, redactor, relay publisher and {@code TraceabilityPublisher}
 * (all gated on {@code cashu.trace.publisher.enabled=true}), but not an
 * {@link OperationIdRegistry}. This config adds a durable SQLite registry so a
 * retry/restart re-emitting the same operation resolves to one ledger record
 * (FR-008). Active only when tracing is enabled.
 */
@Configuration
@ConditionalOnProperty(prefix = "cashu.trace.publisher", name = "enabled", havingValue = "true")
public class TracePublisherConfig {

    /**
     * Durable operation-id registry. Defaults to the same SQLite location used
     * by the outbox (relaxed-binding key {@code cashu.trace.publisher.outbox-jdbc-url});
     * an in-memory URL is the last-resort fallback and loses cross-restart
     * idempotency, so production MUST set a file path.
     */
    @Bean
    @ConditionalOnMissingBean
    public OperationIdRegistry traceOperationIdRegistry(
            @Value("${cashu.trace.publisher.operation-id-jdbc-url:"
                    + "${cashu.trace.publisher.outbox-jdbc-url:jdbc:sqlite::memory:}}") String jdbcUrl) {
        return new SqliteOperationIdRegistry(jdbcUrl);
    }
}
