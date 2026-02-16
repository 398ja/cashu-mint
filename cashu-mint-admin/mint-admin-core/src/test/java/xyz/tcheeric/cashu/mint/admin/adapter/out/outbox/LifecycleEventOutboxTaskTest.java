package xyz.tcheeric.cashu.mint.admin.adapter.out.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintAggregateViewRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleEvent;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleHistoryRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OutboxRepository;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.domain.OutboxMessage;

class LifecycleEventOutboxTaskTest {

    private RecordingDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new RecordingDispatcher();
    }

    // Ensures the task delegates to the dispatcher using the configured batch size.
    @Test
    void shouldInvokeDispatcherWithBatchSize() {
        final LifecycleEventOutboxTask task = new LifecycleEventOutboxTask(dispatcher, 3);

        task.run();

        assertThat(dispatcher.lastBatchSize).isEqualTo(3);
        assertThat(dispatcher.invocationCount).isEqualTo(1);
    }

    // Ensures dispatcher failures are forwarded to the error handler without throwing.
    @Test
    void shouldForwardExceptionsToErrorHandler() {
        dispatcher.failWith(new IllegalStateException("boom"));
        final AtomicReference<RuntimeException> captured = new AtomicReference<>();
        final Consumer<RuntimeException> handler = captured::set;
        final LifecycleEventOutboxTask task = new LifecycleEventOutboxTask(dispatcher, 2, handler);

        task.run();

        assertThat(captured.get()).isInstanceOf(IllegalStateException.class).hasMessageContaining("boom");
        assertThat(dispatcher.invocationCount).isEqualTo(1);
    }

    // Ensures invalid batch sizes are rejected during construction.
    @Test
    void shouldRejectNonPositiveBatchSize() {
        assertThatThrownBy(() -> new LifecycleEventOutboxTask(dispatcher, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class RecordingDispatcher extends LifecycleEventOutboxDispatcher {

        private int lastBatchSize;
        private int invocationCount;
        private RuntimeException toThrow;

        RecordingDispatcher() {
            super(new NoopOutboxRepository(), new NoopHandler(), Clock.systemUTC(), Duration.ofSeconds(1));
        }

        @Override
        public int dispatchPending(final int batchSize) {
            invocationCount++;
            lastBatchSize = batchSize;
            if (toThrow != null) {
                throw toThrow;
            }
            return 0;
        }

        void failWith(final RuntimeException ex) {
            this.toThrow = ex;
        }
    }

    private static final class NoopOutboxRepository implements OutboxRepository {

        @Override
        public void append(final OutboxMessage message) {
        }

        @Override
        public List<OutboxMessage> findPending(final Instant availableBefore, final int limit) {
            return List.of();
        }

        @Override
        public void markDispatched(final UUID eventId, final Instant dispatchedAt) {
        }

        @Override
        public void recordFailure(final UUID eventId, final Instant attemptAt, final Instant nextAttemptAt) {
        }
    }

    private static final class NoopHandler extends LifecycleEventOutboxHandler {

        NoopHandler() {
            super(new NoopAggregateViewRepository(), new NoopHistoryRepository(), new ObjectMapper());
        }

        @Override
        public MintLifecycleEvent handle(final OutboxMessage message) {
            return null;
        }
    }

    private static final class NoopAggregateViewRepository implements MintAggregateViewRepository {

        @Override
        public void upsert(final MintLifecycleEvent event) {
        }

        @Override
        public java.util.Optional<MintAggregateView> findById(final MintId mintId) {
            return java.util.Optional.empty();
        }

        @Override
        public List<MintAggregateView> findAll() {
            return List.of();
        }
    }

    private static final class NoopHistoryRepository implements MintLifecycleHistoryRepository {

        @Override
        public void append(final UUID eventId, final MintLifecycleEvent event) {
        }

        @Override
        public List<MintLifecycleHistoryEntry> findByMintId(final MintId mintId) {
            return List.of();
        }
    }
}
