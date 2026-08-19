package xyz.tcheeric.cashu.mint.admin.rest.config;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.fasterxml.jackson.databind.ObjectMapper;

import xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc.JdbcMintAggregateViewRepository;
import xyz.tcheeric.cashu.mint.admin.adapter.out.outbox.CompositeOutboxMessageHandler;
import xyz.tcheeric.cashu.mint.admin.adapter.out.outbox.LifecycleEventOutboxDispatcher;
import xyz.tcheeric.cashu.mint.admin.adapter.out.outbox.LifecycleEventOutboxHandler;
import xyz.tcheeric.cashu.mint.admin.adapter.out.outbox.VaultProvisioningOutboxHandler;
import xyz.tcheeric.cashu.mint.admin.adapter.out.vault.DeterministicKeyGenerator;
import xyz.tcheeric.cashu.mint.admin.adapter.out.vault.VaultProvisioningAdapter;
import xyz.tcheeric.cashu.mint.admin.application.port.out.ConfigurationSetRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintAggregateViewRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleEventPublisher;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleHistoryRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperationalControlRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OutboxRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.VaultProvisioningPort;

/**
 * Wires the outbox polling scheduler with both the read-model projection handler
 * and the vault provisioning saga handler.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "admin.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxSchedulerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OutboxSchedulerConfiguration.class);

    @Bean
    public MintAggregateViewRepository mintAggregateViewRepository(final DataSource dataSource) {
        return new JdbcMintAggregateViewRepository(dataSource);
    }

    @Bean
    public DeterministicKeyGenerator deterministicKeyGenerator() {
        return new DeterministicKeyGenerator();
    }

    @Bean
    public VaultProvisioningPort vaultProvisioningPort(final DeterministicKeyGenerator keyGenerator) {
        return new VaultProvisioningAdapter(keyGenerator);
    }

    @Bean
    public LifecycleEventOutboxDispatcher outboxDispatcher(
            final OutboxRepository outboxRepository,
            final MintAggregateViewRepository viewRepository,
            final MintLifecycleHistoryRepository historyRepository,
            final VaultProvisioningPort vaultPort,
            final MintRepository mintRepository,
            final ConfigurationSetRepository configurationSetRepository,
            final OperationalControlRepository operationalControlRepository,
            final MintLifecycleEventPublisher eventPublisher,
            final ObjectMapper objectMapper,
            final Clock adminClock,
            @Value("${admin.vault.provision.max-retries:5}") final int maxRetries,
            @Value("${admin.outbox.failure.backoff:PT30S}") final Duration backoff) {

        final LifecycleEventOutboxHandler readModelHandler =
            new LifecycleEventOutboxHandler(viewRepository, historyRepository, objectMapper);
        final VaultProvisioningOutboxHandler vaultHandler =
            new VaultProvisioningOutboxHandler(vaultPort, mintRepository, configurationSetRepository,
                operationalControlRepository, eventPublisher, objectMapper, adminClock, maxRetries);
        final CompositeOutboxMessageHandler compositeHandler =
            new CompositeOutboxMessageHandler(List.of(readModelHandler, vaultHandler));

        return new LifecycleEventOutboxDispatcher(outboxRepository, compositeHandler, adminClock, backoff);
    }

    @Bean
    public OutboxSchedulerRunner outboxSchedulerRunner(final LifecycleEventOutboxDispatcher dispatcher,
                                                       @Value("${admin.outbox.batch.size:100}") final int batchSize) {
        return new OutboxSchedulerRunner(dispatcher, batchSize);
    }

    /**
     * Periodic runner that polls the outbox at a fixed rate.
     */
    static class OutboxSchedulerRunner {

        private final LifecycleEventOutboxDispatcher dispatcher;
        private final int batchSize;

        OutboxSchedulerRunner(final LifecycleEventOutboxDispatcher dispatcher, final int batchSize) {
            this.dispatcher = dispatcher;
            this.batchSize = batchSize;
        }

        @Scheduled(fixedDelayString = "${admin.outbox.poll.interval:5000}")
        void pollOutbox() {
            try {
                dispatcher.dispatchPending(batchSize);
            } catch (final RuntimeException ex) {
                log.warn("Outbox dispatch cycle failed: {}", ex.getMessage());
            }
        }
    }
}
