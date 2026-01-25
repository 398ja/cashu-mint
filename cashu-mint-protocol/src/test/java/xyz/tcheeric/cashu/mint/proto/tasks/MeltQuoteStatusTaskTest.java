package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.PostMeltQuoteResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

public class MeltQuoteStatusTaskTest {

    @Test
    public void execute() throws CashuErrorException {
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.getPaymentExpiry("qid")).thenReturn(5);
        when(gateway.checkPaymentStatus("qid")).thenReturn(true);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.MOCK)).thenReturn(gateway);

        MeltQuoteStatusTask task = new MeltQuoteStatusTask("qid", PaymentMethod.MOCK, service);
        PostMeltQuoteResponse resp = task.execute();

        assertEquals("qid", resp.getQuoteId());
        assertEquals(5L, resp.getExpiry());
        assertEquals(true, resp.isPaid());
    }
}
