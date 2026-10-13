package xyz.tcheeric.cashu.mint.admin.rest.config;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.vault.core.VaultTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc.JdbcMintAggregateViewRepository;
import xyz.tcheeric.cashu.mint.admin.adapter.out.outbox.CompositeOutboxMessageHandler;
import xyz.tcheeric.cashu.mint.admin.adapter.out.outbox.LifecycleEventOutboxDispatcher;
import xyz.tcheeric.cashu.mint.admin.adapter.out.outbox.LifecycleEventOutboxHandler;
import xyz.tcheeric.cashu.mint.admin.adapter.out.outbox.VaultProvisioningOutboxHandler;
import xyz.tcheeric.cashu.mint.admin.adapter.out.vault.DeterministicKeyGenerator;
import xyz.tcheeric.cashu.mint.admin.adapter.out.vault.KeysetMasterSeed;
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

    /**
     * The keyset master seed, read from Vault when the context starts (cashu-mint#545).
     *
     * <p>Eager on purpose: a missing, unreadable or weak seed fails startup here, before the outbox
     * runs, rather than surfacing later as provisioning retries. There is no unseeded fallback.
     * A context without a VaultTemplate (vault.hashi.enabled=false) also fails, because the seed
     * has nowhere else to live.
     */
    @Bean
    public Supplier<KeysetMasterSeed> keysetMasterSeed(
            final ObjectProvider<VaultTemplate> vaultTemplate,
            final Environment environment,
            @Value("${vault.hashi.engine.mount:cashu}") final String mount,
            @Value("${admin.keyset-seed.path:seeds/mint-admin/keyset-master-seed}") final String path,
            @Value("${admin.keyset-seed.generate-if-absent:false}") final boolean generateIfAbsent,
            @Value("${admin.keyset-seed.ephemeral:false}") final boolean ephemeral) {
        if (ephemeral) {
            return ephemeralSeed(environment);
        }
        return vaultSeed(vaultTemplate, mount, path, generateIfAbsent);
    }

    /**
     * A random in-memory seed for Spring contexts under test, which have no Vault.
     *
     * <p>Not derivable (it is CSPRNG output) but not persistent either, so keys provisioned with it
     * cannot be re-derived after a restart. Honoured only with the {@code test} profile active, so a
     * stray {@code admin.keyset-seed.ephemeral=true} in a real deployment fails startup instead.
     */
    static Supplier<KeysetMasterSeed> ephemeralSeed(final Environment environment) {
        if (!environment.acceptsProfiles(Profiles.of("test"))) {
            throw new IllegalStateException("admin.keyset-seed.ephemeral=true is only allowed with the "
                + "'test' Spring profile. A real mint-admin must read its keyset master seed from Vault.");
        }
        final byte[] bytes = new byte[KeysetMasterSeed.LENGTH];
        new java.security.SecureRandom().nextBytes(bytes);
        final KeysetMasterSeed seed = KeysetMasterSeed.fromHex(java.util.HexFormat.of().formatHex(bytes));
        log.warn("Using an EPHEMERAL keyset master seed (test profile). Keys will not survive a restart.");
        return () -> seed;
    }

    static Supplier<KeysetMasterSeed> vaultSeed(final ObjectProvider<VaultTemplate> vaultTemplate,
                                                final String mount, final String path,
                                                final boolean generateIfAbsent) {
        final VaultTemplate template = vaultTemplate.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException("mint-admin provisions keysets but no HashiCorp Vault is "
                + "configured (vault.hashi.enabled=false), so there is no keyset master seed. Refusing "
                + "to start: keys derived without a secret can be recomputed from the mint id "
                + "(cashu-mint#545). Set VAULT_HASHI_ENABLED=true, or admin.outbox.enabled=false for "
                + "an instance that must not provision.");
        }
        return new VaultKeysetSeedSource(template, mount, path, generateIfAbsent);
    }

    @Bean
    public DeterministicKeyGenerator deterministicKeyGenerator(final Supplier<KeysetMasterSeed> keysetMasterSeed) {
        return new DeterministicKeyGenerator(keysetMasterSeed);
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
