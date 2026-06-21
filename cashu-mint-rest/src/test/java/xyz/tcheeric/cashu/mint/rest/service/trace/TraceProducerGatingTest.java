package xyz.tcheeric.cashu.mint.rest.service.trace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import xyz.tcheeric.cashu.ledger.trace.core.TransactionEvent;
import xyz.tcheeric.cashu.ledger.trace.publisher.TraceabilityPublisher;
import xyz.tcheeric.cashu.ledger.trace.publisher.spring.TracePublisherAutoConfiguration;
import xyz.tcheeric.cashu.mint.rest.config.TracePublisherConfig;

/**
 * Spec 036 US3 — verifies tracing is a no-op when disabled (SC-008): no producer,
 * factory, guard, or SDK publisher beans are created, so the application events
 * published by the controller are silently dropped and mint behaviour is
 * unchanged.
 */
class TraceProducerGatingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TracePublisherAutoConfiguration.class))
            .withUserConfiguration(TracePublisherConfig.class, TraceEventFactory.class,
                    TraceMintProducer.class, TraceProducerGuard.class);

    // Explicitly disabled => none of the trace beans exist.
    @Test
    void disabled_createsNoTraceBeans() {
        runner.withPropertyValues("cashu.trace.publisher.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(TraceMintProducer.class);
                    assertThat(ctx).doesNotHaveBean(TraceEventFactory.class);
                    assertThat(ctx).doesNotHaveBean(TraceProducerGuard.class);
                    assertThat(ctx).doesNotHaveBean(TraceabilityPublisher.class);
                });
    }

    // Property absent entirely => same disabled behaviour (no beans), context healthy.
    @Test
    void absent_createsNoTraceBeansAndContextStartsClean() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(TraceMintProducer.class);
            assertThat(ctx).doesNotHaveBean(TransactionEvent.class); // sanity: no stray event bean
        });
    }
}
