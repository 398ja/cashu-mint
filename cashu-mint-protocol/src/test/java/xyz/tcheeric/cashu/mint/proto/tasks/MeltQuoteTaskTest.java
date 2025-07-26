package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.PostMeltQuoteRequest;
import xyz.tcheeric.cashu.entities.rest.PostMeltQuoteResponse;
import xyz.tcheeric.cashu.gateway.Gateway;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

public class MeltQuoteTaskTest {

    @Test
    public void execute() throws CashuErrorException {
        PostMeltQuoteRequest request = new PostMeltQuoteRequest();
        request.setRequest("req");

        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.createMeltQuote("req")).thenReturn("qid");
        when(gateway.getFeeReserve("qid")).thenReturn(1);
        when(gateway.getPaymentExpiry("qid")).thenReturn(2);
        when(gateway.getAmount("qid")).thenReturn(3);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.MOCK)).thenReturn(gateway);

        MeltQuoteTask task = new MeltQuoteTask(request, PaymentMethod.MOCK, service);
        PostMeltQuoteResponse resp = task.execute();

        assertEquals("qid", resp.getQuoteId());
        assertEquals(1, resp.getFeeReserve());
        assertEquals(2L, resp.getExpiry());
        assertEquals(3, resp.getAmount());
    }
}
