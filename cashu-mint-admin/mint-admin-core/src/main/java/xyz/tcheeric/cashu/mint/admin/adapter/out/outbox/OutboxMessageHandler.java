package xyz.tcheeric.cashu.mint.admin.adapter.out.outbox;

import xyz.tcheeric.cashu.mint.admin.domain.OutboxMessage;

/**
 * Strategy for processing messages dispatched from the transactional outbox.
 */
public interface OutboxMessageHandler {

    void handle(OutboxMessage message);
}
