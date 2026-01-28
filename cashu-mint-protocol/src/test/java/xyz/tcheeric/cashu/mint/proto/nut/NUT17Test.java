package xyz.tcheeric.cashu.mint.proto.nut;

import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.nut17.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests NUT17 static utility methods for subscription management.
 */
class NUT17Test {

    // Tests that subscription ID generation produces unique values.
    @Test
    void generateSubscriptionId_ProducesUniqueValues() {
        String id1 = NUT17.generateSubscriptionId();
        String id2 = NUT17.generateSubscriptionId();

        assertNotNull(id1);
        assertNotNull(id2);
        assertNotEquals(id1, id2, "Generated IDs should be unique");
    }

    // Tests validation of valid subscription parameters.
    @Test
    void validateSubscriptionParams_Valid_ReturnsTrue() {
        SubscriptionFilter filter = new SubscriptionFilter();
        filter.setIds(List.of("proof-1"));

        SubscriptionParams params = new SubscriptionParams();
        params.setKind(SubscriptionKind.proof_state);
        params.setFilters(List.of(filter));

        assertTrue(NUT17.validateSubscriptionParams(params));
    }

    // Tests validation fails for missing kind.
    @Test
    void validateSubscriptionParams_MissingKind_ReturnsFalse() {
        SubscriptionFilter filter = new SubscriptionFilter();
        filter.setIds(List.of("proof-1"));

        SubscriptionParams params = new SubscriptionParams();
        params.setFilters(List.of(filter));

        assertFalse(NUT17.validateSubscriptionParams(params));
    }

    // Tests validation fails for missing filters.
    @Test
    void validateSubscriptionParams_MissingFilters_ReturnsFalse() {
        SubscriptionParams params = new SubscriptionParams();
        params.setKind(SubscriptionKind.proof_state);

        assertFalse(NUT17.validateSubscriptionParams(params));
    }

    // Tests validation fails for empty filters.
    @Test
    void validateSubscriptionParams_EmptyFilters_ReturnsFalse() {
        SubscriptionParams params = new SubscriptionParams();
        params.setKind(SubscriptionKind.proof_state);
        params.setFilters(List.of());

        assertFalse(NUT17.validateSubscriptionParams(params));
    }

    // Tests validation fails for filter with empty IDs.
    @Test
    void validateSubscriptionParams_EmptyFilterIds_ReturnsFalse() {
        SubscriptionFilter filter = new SubscriptionFilter();
        filter.setIds(List.of());

        SubscriptionParams params = new SubscriptionParams();
        params.setKind(SubscriptionKind.proof_state);
        params.setFilters(List.of(filter));

        assertFalse(NUT17.validateSubscriptionParams(params));
    }

    // Tests ID extraction from multiple filters.
    @Test
    void extractIds_FlattensMultipleFilters() {
        SubscriptionFilter filter1 = new SubscriptionFilter();
        filter1.setIds(List.of("a", "b"));
        SubscriptionFilter filter2 = new SubscriptionFilter();
        filter2.setIds(List.of("c"));

        List<SubscriptionFilter> filters = List.of(filter1, filter2);

        List<String> ids = NUT17.extractIds(filters);

        assertEquals(List.of("a", "b", "c"), ids);
    }

    // Tests subscribe response format.
    @Test
    void subscribeResponse_ContainsSubId() {
        JsonRpcResponse response = NUT17.subscribeResponse("req-1", "sub-abc");

        assertEquals("2.0", response.getJsonrpc());
        assertEquals("req-1", response.getId());
        assertNull(response.getError());
        assertNotNull(response.getResult());
    }

    // Tests error response format.
    @Test
    void errorResponse_ContainsErrorDetails() {
        JsonRpcResponse response = NUT17.errorResponse("req-2", -32600, "Invalid Request");

        assertEquals("req-2", response.getId());
        assertNotNull(response.getError());
        assertEquals(-32600, response.getError().getCode());
        assertEquals("Invalid Request", response.getError().getMessage());
        assertNull(response.getResult());
    }

    // Tests proof state notification format.
    @Test
    void proofStateNotification_ContainsPayload() {
        JsonRpcNotification notification = NUT17.proofStateNotification("sub-1", "y-value", "SPENT", null);

        assertEquals("2.0", notification.getJsonrpc());
        assertEquals("notification", notification.getMethod());
        assertNotNull(notification.getParams());
        assertEquals("sub-1", notification.getParams().getSubId());

        ProofStatePayload payload = (ProofStatePayload) notification.getParams().getPayload();
        assertEquals("y-value", payload.getY());
        assertEquals("SPENT", payload.getState());
        assertNull(payload.getWitness());
    }

    // Tests quote state notification format.
    @Test
    void quoteStateNotification_ContainsPayload() {
        QuoteStatePayload quotePayload = new QuoteStatePayload();
        quotePayload.setQuoteId("quote-123");
        quotePayload.setState("PAID");
        quotePayload.setPaid(true);
        quotePayload.setAmount(1000L);

        JsonRpcNotification notification = NUT17.quoteStateNotification("sub-2", quotePayload);

        assertEquals("notification", notification.getMethod());
        assertEquals("sub-2", notification.getParams().getSubId());

        QuoteStatePayload payload = (QuoteStatePayload) notification.getParams().getPayload();
        assertEquals("quote-123", payload.getQuoteId());
        assertEquals("PAID", payload.getState());
        assertTrue(payload.getPaid());
        assertEquals(1000L, payload.getAmount());
    }
}
