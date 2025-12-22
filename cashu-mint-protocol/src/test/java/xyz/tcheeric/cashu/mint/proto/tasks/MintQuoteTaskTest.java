package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.PostMintQuoteResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.gateway.common.Gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

public class MintQuoteTaskTest {

    // Ensures mint quote creation returns the gateway-generated identifiers
    @Test
    public void quote() throws CashuErrorException {
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.createMintQuote(anyInt(), Mockito.isNull())).thenReturn("qid");
        when(gateway.getRequest("qid")).thenReturn("req");
        when(gateway.getPaymentExpiry("qid")).thenReturn(123);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.createGateway(PaymentMethod.MOCK)).thenReturn(gateway);

        MintQuoteTask task = new MintQuoteTask(42, PaymentMethod.MOCK, service);
        PostMintQuoteResponse response = task.execute();

        assertEquals("qid", response.getQuoteId());
        assertEquals("req", response.getRequest());
        assertEquals(123L, response.getExpiry());
        assertFalse(response.isPaid());
    }

    // Ensures mint quote status reflects current gateway state
    @Test
    public void quoteStatus() throws CashuErrorException {
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.getRequest("qid")).thenReturn("req");
        when(gateway.getPaymentExpiry("qid")).thenReturn(123);
        when(gateway.checkPaymentStatus("qid")).thenReturn(true);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.createGateway(PaymentMethod.MOCK)).thenReturn(gateway);

        MintQuoteStatusTask task = new MintQuoteStatusTask("qid", PaymentMethod.MOCK, service);
        PostMintQuoteResponse response = task.execute();

        assertEquals("qid", response.getQuoteId());
        assertEquals("req", response.getRequest());
        assertEquals(123L, response.getExpiry());
        assertEquals(true, response.isPaid());
    }
}
