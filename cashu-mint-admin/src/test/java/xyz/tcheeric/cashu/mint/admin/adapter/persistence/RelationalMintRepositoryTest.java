package xyz.tcheeric.cashu.mint.admin.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import xyz.tcheeric.cashu.mint.admin.domain.AuditMetadata;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationSet;
import xyz.tcheeric.cashu.mint.admin.domain.MintAggregate;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.domain.NotificationPolicy;
import xyz.tcheeric.cashu.mint.admin.domain.OperatorAccount;

class RelationalMintRepositoryTest {

    // Ensures the findById stub runs within a transaction and returns an empty result.
    @Test
    void shouldReturnEmptyOptionalWhenMintMissing() {
        final RecordingTransactionExecutor executor = new RecordingTransactionExecutor();
        final MintRepository repository = new RelationalMintRepository(new PlaceholderMintAggregateRowMapper(), executor);
        final MintId mintId = MintId.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        final Optional<MintAggregate> result = repository.findById(mintId);

        assertThat(result).isEmpty();
        assertThat(executor.invocationCount()).isEqualTo(1);
    }

    // Ensures saving an aggregate delegates to the mapper and echoes the original aggregate.
    @Test
    void shouldReturnAggregateWhenSaving() {
        final RecordingTransactionExecutor executor = new RecordingTransactionExecutor();
        final RecordingMintAggregateRowMapper mapper = new RecordingMintAggregateRowMapper();
        final MintRepository repository = new RelationalMintRepository(mapper, executor);
        final MintAggregate aggregate = newAggregate();

        final MintAggregate saved = repository.save(aggregate);

        assertThat(saved).isSameAs(aggregate);
        assertThat(mapper.lastAggregate()).containsSame(aggregate);
        assertThat(mapper.lastRow()).isPresent();
        assertThat(executor.invocationCount()).isEqualTo(1);
    }

    // Ensures findAll currently returns an empty collection inside a transaction boundary.
    @Test
    void shouldReturnEmptyListWhenNoMintsPersisted() {
        final RecordingTransactionExecutor executor = new RecordingTransactionExecutor();
        final MintRepository repository = new RelationalMintRepository(new PlaceholderMintAggregateRowMapper(), executor);

        final List<MintAggregate> all = repository.findAll();

        assertThat(all).isEmpty();
        assertThat(executor.invocationCount()).isEqualTo(1);
    }

    // Ensures delete executes inside a transaction even when it performs no work yet.
    @Test
    void shouldExecuteDeletionWithinTransaction() {
        final RecordingTransactionExecutor executor = new RecordingTransactionExecutor();
        final MintRepository repository = new RelationalMintRepository(new PlaceholderMintAggregateRowMapper(), executor);
        final MintId mintId = MintId.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        repository.delete(mintId);

        assertThat(executor.invocationCount()).isEqualTo(1);
    }

    private static MintAggregate newAggregate() {
        final MintId mintId = MintId.of(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        final AuditMetadata creation = new AuditMetadata("system", "mint-created", Instant.parse("2024-01-01T00:00:00Z"));
        final ConfigurationSet configuration = new ConfigurationSet(ConfigurationRevisionId.of(1),
            Map.of("max_tokens", "500"), creation);
        final OperatorAccount operator = new OperatorAccount(UUID.fromString("22222222-2222-2222-2222-222222222222"),
            "Primary Operator",
            Set.of("ADMIN"),
            creation);
        final NotificationPolicy policy = new NotificationPolicy(true, false, Duration.ofMinutes(5), creation);
        final MintAggregate aggregate = MintAggregate.create(mintId, configuration, operator, policy, creation);
        final AuditMetadata activation = new AuditMetadata("system", "activate", Instant.parse("2024-01-02T00:00:00Z"));
        return aggregate.activate(activation);
    }

    private static final class RecordingTransactionExecutor implements TransactionExecutor {

        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public <T> T execute(final Supplier<T> action) {
            invocations.incrementAndGet();
            return action.get();
        }

        int invocationCount() {
            return invocations.get();
        }
    }

    private static final class RecordingMintAggregateRowMapper implements MintAggregateRowMapper {

        private MintAggregate lastAggregate;
        private MintAggregateRow lastRow;

        @Override
        public MintAggregateRow toRow(final MintAggregate aggregate) {
            this.lastAggregate = aggregate;
            this.lastRow = new PlaceholderMintAggregateRowMapper().toRow(aggregate);
            return lastRow;
        }

        @Override
        public Optional<MintAggregate> toAggregate(final MintAggregateRow row) {
            this.lastRow = row;
            return Optional.empty();
        }

        Optional<MintAggregate> lastAggregate() {
            return Optional.ofNullable(lastAggregate);
        }

        Optional<MintAggregateRow> lastRow() {
            return Optional.ofNullable(lastRow);
        }
    }
}
