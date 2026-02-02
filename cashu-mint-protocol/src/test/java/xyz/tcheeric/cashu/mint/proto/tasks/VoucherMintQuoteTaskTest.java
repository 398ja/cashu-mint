package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.util.VoucherQuoteRegistry;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for VoucherMintQuoteTask.
 */
public class VoucherMintQuoteTaskTest {

    @AfterEach
    public void cleanup() {
        // Clean up registry after each test
        VoucherQuoteRegistry.clear();
    }

    @Test
    public void testExecuteWithDefaultPercentage() throws CashuErrorException {
        // Test voucher quote creation with default 10% fee
        // Given: 1000 sat voucher face value
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.createMintQuote(anyInt(), Mockito.isNull())).thenReturn("qid");
        when(gateway.getRequest("qid")).thenReturn("lnbc100n...");
        when(gateway.getPaymentExpiry("qid")).thenReturn(3600);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.MOCK)).thenReturn(gateway);

        // When: Execute voucher mint quote task
        VoucherMintQuoteTask task = new VoucherMintQuoteTask(1000, PaymentMethod.MOCK, service);
        PostMintQuoteResponse response = task.execute();

        // Then: Gateway receives 100 sats (10% of 1000), not 1000 sats
        verify(gateway).createMintQuote(100, null);

        // And: Response is correct
        assertEquals("qid", response.getQuoteId());
        assertEquals("lnbc100n...", response.getRequest());
        assertEquals(3600, response.getExpiry());

        // And: Face value is stored in registry
        assertEquals(1000L, VoucherQuoteRegistry.getFaceValue("qid"));
        assertTrue(VoucherQuoteRegistry.isVoucherQuote("qid"));
    }

    @Test
    public void testExecuteWithSmallAmount() throws CashuErrorException {
        // Test voucher quote with small amount: 10 sats @ 10% = 1 sat
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.createMintQuote(anyInt(), Mockito.isNull())).thenReturn("qid2");
        when(gateway.getRequest("qid2")).thenReturn("lnbc1n...");
        when(gateway.getPaymentExpiry("qid2")).thenReturn(3600);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.MOCK)).thenReturn(gateway);

        VoucherMintQuoteTask task = new VoucherMintQuoteTask(10, PaymentMethod.MOCK, service);
        PostMintQuoteResponse response = task.execute();

        // Gateway should receive 1 sat (10% of 10)
        verify(gateway).createMintQuote(1, null);

        // Face value should be stored
        assertEquals(10L, VoucherQuoteRegistry.getFaceValue("qid2"));
    }

    @Test
    public void testExecuteWithVerySmallAmount() throws CashuErrorException {
        // Test voucher quote with amount resulting in zero fee: 5 sats @ 10% = floor(0.5) = 0 sats
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.createMintQuote(anyInt(), Mockito.isNull())).thenReturn("qid3");
        when(gateway.getRequest("qid3")).thenReturn("lnbc0n...");
        when(gateway.getPaymentExpiry("qid3")).thenReturn(3600);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.MOCK)).thenReturn(gateway);

        VoucherMintQuoteTask task = new VoucherMintQuoteTask(5, PaymentMethod.MOCK, service);
        PostMintQuoteResponse response = task.execute();

        // Gateway should receive 0 sats (floor of 0.5)
        verify(gateway).createMintQuote(0, null);

        // Face value should still be stored
        assertEquals(5L, VoucherQuoteRegistry.getFaceValue("qid3"));
    }

    @Test
    public void testExecuteWithLargeAmount() throws CashuErrorException {
        // Test voucher quote with large amount: 1,000,000 sats @ 10% = 100,000 sats
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.createMintQuote(anyInt(), Mockito.isNull())).thenReturn("qid4");
        when(gateway.getRequest("qid4")).thenReturn("lnbc100000n...");
        when(gateway.getPaymentExpiry("qid4")).thenReturn(3600);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.MOCK)).thenReturn(gateway);

        VoucherMintQuoteTask task = new VoucherMintQuoteTask(1_000_000, PaymentMethod.MOCK, service);
        PostMintQuoteResponse response = task.execute();

        // Gateway should receive 100,000 sats (10% of 1,000,000)
        verify(gateway).createMintQuote(100_000, null);

        // Face value should be stored
        assertEquals(1_000_000L, VoucherQuoteRegistry.getFaceValue("qid4"));
    }

    @Test
    public void testMultipleVoucherQuotes() throws CashuErrorException {
        // Test creating multiple voucher quotes
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.createMintQuote(anyInt(), Mockito.isNull()))
            .thenReturn("qid5")
            .thenReturn("qid6");
        when(gateway.getRequest(Mockito.anyString())).thenReturn("req");
        when(gateway.getPaymentExpiry(Mockito.anyString())).thenReturn(3600);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.MOCK)).thenReturn(gateway);

        // Create first voucher quote: 1000 sats
        VoucherMintQuoteTask task1 = new VoucherMintQuoteTask(1000, PaymentMethod.MOCK, service);
        task1.execute();

        // Create second voucher quote: 2000 sats
        VoucherMintQuoteTask task2 = new VoucherMintQuoteTask(2000, PaymentMethod.MOCK, service);
        task2.execute();

        // Both face values should be stored
        assertEquals(1000L, VoucherQuoteRegistry.getFaceValue("qid5"));
        assertEquals(2000L, VoucherQuoteRegistry.getFaceValue("qid6"));
        assertEquals(2, VoucherQuoteRegistry.size());
    }

    @Test
    public void testGatewayErrorPropagates() {
        // Test that gateway errors propagate correctly
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.createMintQuote(anyInt(), Mockito.isNull()))
            .thenThrow(new RuntimeException("Gateway error"));

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.MOCK)).thenReturn(gateway);

        VoucherMintQuoteTask task = new VoucherMintQuoteTask(1000, PaymentMethod.MOCK, service);

        // Should propagate the exception
        assertThrows(RuntimeException.class, task::execute);

        // Registry should not have any entry since quote creation failed
        assertEquals(0, VoucherQuoteRegistry.size());
    }

    @Test
    public void testResponseStructure() throws CashuErrorException {
        // Test that the response structure matches PostMintQuoteResponse
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.createMintQuote(anyInt(), Mockito.isNull())).thenReturn("test-qid");
        when(gateway.getRequest("test-qid")).thenReturn("test-request");
        when(gateway.getPaymentExpiry("test-qid")).thenReturn(7200);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.MOCK)).thenReturn(gateway);

        VoucherMintQuoteTask task = new VoucherMintQuoteTask(1000, PaymentMethod.MOCK, service);
        PostMintQuoteResponse response = task.execute();

        // Verify all fields are populated
        assertNotNull(response);
        assertEquals("test-qid", response.getQuoteId());
        assertEquals("test-request", response.getRequest());
        assertEquals(7200, response.getExpiry());
    }
}
