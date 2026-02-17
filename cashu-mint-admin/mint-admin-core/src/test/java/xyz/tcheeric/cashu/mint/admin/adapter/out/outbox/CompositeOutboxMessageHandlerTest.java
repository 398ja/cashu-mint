package xyz.tcheeric.cashu.mint.admin.adapter.out.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.domain.OutboxMessage;

class CompositeOutboxMessageHandlerTest {

    private static OutboxMessage sampleMessage() {
        return new OutboxMessage(
            UUID.randomUUID(),
            MintId.of(UUID.randomUUID()),
            "MintAggregate",
            "CREATED",
            "{\"type\":\"CREATED\"}",
            Map.of(),
            Instant.now(),
            Instant.now(),
            null,
            null,
            0);
    }

    @Test
    // Ensures all handlers are invoked for each message.
    void shouldInvokeAllHandlers() {
        final List<String> calls = new ArrayList<>();
        final OutboxMessageHandler h1 = msg -> calls.add("h1");
        final OutboxMessageHandler h2 = msg -> calls.add("h2");

        new CompositeOutboxMessageHandler(List.of(h1, h2)).handle(sampleMessage());

        assertThat(calls).containsExactly("h1", "h2");
    }

    @Test
    // Ensures fail-fast: second handler is not invoked when the first fails.
    void shouldFailFastWhenFirstHandlerThrows() {
        final List<String> calls = new ArrayList<>();
        final OutboxMessageHandler failing = msg -> { calls.add("fail"); throw new RuntimeException("boom"); };
        final OutboxMessageHandler passing = msg -> calls.add("ok");

        assertThrows(RuntimeException.class,
            () -> new CompositeOutboxMessageHandler(List.of(failing, passing)).handle(sampleMessage()));

        assertThat(calls).containsExactly("fail");
    }

    @Test
    // Ensures the exception from the failing handler propagates directly.
    void shouldPropagateHandlerException() {
        final OutboxMessageHandler h1 = msg -> { throw new RuntimeException("first"); };
        final OutboxMessageHandler h2 = msg -> { throw new RuntimeException("second"); };

        final RuntimeException ex = assertThrows(RuntimeException.class,
            () -> new CompositeOutboxMessageHandler(List.of(h1, h2)).handle(sampleMessage()));

        assertThat(ex).hasMessage("first");
    }

    @Test
    // Ensures OutboxMessageHandlingException is rethrown directly without wrapping.
    void shouldRethrowOutboxHandlingExceptionDirectly() {
        final OutboxMessageHandler h1 = msg -> {
            throw new OutboxMessageHandlingException("direct");
        };

        final OutboxMessageHandlingException ex = assertThrows(OutboxMessageHandlingException.class,
            () -> new CompositeOutboxMessageHandler(List.of(h1)).handle(sampleMessage()));

        assertThat(ex).hasMessage("direct");
    }

    @Test
    // Ensures construction rejects empty handler lists.
    void shouldRejectEmptyHandlerList() {
        assertThrows(IllegalArgumentException.class,
            () -> new CompositeOutboxMessageHandler(List.of()));
    }
}
