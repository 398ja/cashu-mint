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

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for VoucherMintQuoteTask.
 *
 * <p>The mint now chooses the quote id and hands it to the gateway, rather than taking whatever id
 * the gateway generates, so the quote can be recorded before its invoice is raised (#469). These
 * tests therefore use a gateway that echoes back the id it is given, and read the id from the
 * response instead of predicting a literal one. Asserting on a gateway-chosen literal would now be
 * asserting on a value the gateway no longer chooses.
 */
public class VoucherMintQuoteTaskTest {

    @AfterEach
    public void cleanup() {
        // Clean up registry after each test
        VoucherQuoteRegistry.clear();
    }

    /**
     * A gateway that raises every invoice under the id the task supplies, as a correct gateway
     * does, and answers the follow-up lookups for any id.
     */
    private static Gateway echoingGateway(String request, int expiry) {
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.createMintQuote(anyString(), anyInt(), Mockito.isNull()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(gateway.getRequest(anyString())).thenReturn(request);
        when(gateway.getPaymentExpiry(anyString())).thenReturn(expiry);
        return gateway;
    }

    private static MintProtocolService serviceFor(Gateway gateway) {
        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.MOCK)).thenReturn(gateway);
        return service;
    }

    // A 1000 sat voucher at the default 10% fee invoices 100 sats, not the face value, and the
    // face value is what the registry keeps against the quote.
    @Test
    public void testExecuteWithDefaultPercentage() throws CashuErrorException {
        Gateway gateway = echoingGateway("lnbc100n...", 3600);

        long before = Instant.now().getEpochSecond();
        PostMintQuoteResponse response =
                new VoucherMintQuoteTask(1000, PaymentMethod.MOCK, serviceFor(gateway)).execute();
        long after = Instant.now().getEpochSecond();

        // Gateway receives 100 sats (10% of 1000), not 1000 sats
        verify(gateway).createMintQuote(eq(response.getQuoteId()), eq(100), Mockito.isNull());

        assertEquals("lnbc100n...", response.getRequest());
        // NUT-04: the 3600 s TTL is reported as an absolute timestamp (#494)
        assertTrue(response.getExpiry() >= before + 3600 && response.getExpiry() <= after + 3600);

        // Face value is stored against the quote the client was given
        assertEquals(1000L, VoucherQuoteRegistry.getFaceValue(response.getQuoteId()));
        assertTrue(VoucherQuoteRegistry.isVoucherQuote(response.getQuoteId()));
    }

    // 10 sats at 10% is 1 sat, the smallest chargeable fee.
    @Test
    public void testExecuteWithSmallAmount() throws CashuErrorException {
        Gateway gateway = echoingGateway("lnbc1n...", 3600);

        PostMintQuoteResponse response =
                new VoucherMintQuoteTask(10, PaymentMethod.MOCK, serviceFor(gateway)).execute();

        verify(gateway).createMintQuote(eq(response.getQuoteId()), eq(1), Mockito.isNull());
        assertEquals(10L, VoucherQuoteRegistry.getFaceValue(response.getQuoteId()));
    }

    // 5 sats at 10% = floor(0.5) = 0, which is NOT chargeable.
    //
    // This test previously asserted `createMintQuote(0, null)` and so encoded the defect: a
    // zero-amount invoice is created, settles trivially, and the mint then refuses its own webhook
    // because a non-positive amount cannot match an authorised quote. Staging, 2026-09-23: 9
    // stranded quotes and unbounded webhook rejections. The fee is now floored to the configured
    // minimum, so the gateway is asked for something payable.
    @Test
    public void testExecuteWithVerySmallAmount() throws CashuErrorException {
        Gateway gateway = echoingGateway("lnbc0n...", 3600);

        PostMintQuoteResponse response =
                new VoucherMintQuoteTask(5, PaymentMethod.MOCK, serviceFor(gateway)).execute();

        // Floored to the minimum fee (1), never zero.
        verify(gateway).createMintQuote(eq(response.getQuoteId()), eq(1), Mockito.isNull());
        assertEquals(5L, VoucherQuoteRegistry.getFaceValue(response.getQuoteId()));
    }

    // 1,000,000 sats at 10% invoices 100,000 sats.
    @Test
    public void testExecuteWithLargeAmount() throws CashuErrorException {
        Gateway gateway = echoingGateway("lnbc100000n...", 3600);

        PostMintQuoteResponse response =
                new VoucherMintQuoteTask(1_000_000, PaymentMethod.MOCK, serviceFor(gateway)).execute();

        verify(gateway).createMintQuote(eq(response.getQuoteId()), eq(100_000), Mockito.isNull());
        assertEquals(1_000_000L, VoucherQuoteRegistry.getFaceValue(response.getQuoteId()));
    }

    // Two quotes are kept separately, each under its own id with its own face value.
    @Test
    public void testMultipleVoucherQuotes() throws CashuErrorException {
        Gateway gateway = echoingGateway("req", 3600);
        MintProtocolService service = serviceFor(gateway);

        String first = new VoucherMintQuoteTask(1000, PaymentMethod.MOCK, service).execute().getQuoteId();
        String second = new VoucherMintQuoteTask(2000, PaymentMethod.MOCK, service).execute().getQuoteId();

        assertNotEquals(first, second, "each quote must get its own id");
        assertEquals(1000L, VoucherQuoteRegistry.getFaceValue(first));
        assertEquals(2000L, VoucherQuoteRegistry.getFaceValue(second));
        assertEquals(2, VoucherQuoteRegistry.size());
    }

    // A gateway failure propagates, and leaves no registry entry for a quote that was never made.
    @Test
    public void testGatewayErrorPropagates() {
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.createMintQuote(anyString(), anyInt(), Mockito.isNull()))
            .thenThrow(new RuntimeException("Gateway error"));

        VoucherMintQuoteTask task = new VoucherMintQuoteTask(1000, PaymentMethod.MOCK, serviceFor(gateway));

        // Should propagate the exception
        assertThrows(RuntimeException.class, task::execute);

        // Registry should not have any entry since quote creation failed
        assertEquals(0, VoucherQuoteRegistry.size());
    }

    // Every field of the NUT-04 response is populated, with the amount set to the face value.
    @Test
    public void testResponseStructure() throws CashuErrorException {
        Gateway gateway = echoingGateway("test-request", 7200);

        long before = Instant.now().getEpochSecond();
        PostMintQuoteResponse response =
                new VoucherMintQuoteTask(1000, PaymentMethod.MOCK, serviceFor(gateway)).execute();
        long after = Instant.now().getEpochSecond();

        assertNotNull(response);
        assertNotNull(response.getQuoteId());
        assertFalse(response.getQuoteId().isBlank());
        assertEquals("test-request", response.getRequest());
        // NUT-04: the 7200 s TTL is reported as an absolute timestamp (#494)
        assertTrue(response.getExpiry() >= before + 7200 && response.getExpiry() <= after + 7200);
        // The mintable amount is the face value, not the 100 sat fee the invoice charges.
        assertEquals(1000, response.getAmount());
        assertEquals("UNPAID", response.getState());
    }
}
