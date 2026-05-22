package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteResponse;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuoteRepository;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    // Spec 001 T112 — when a MintQuoteRepository is wired, the task persists a
    // mint_quote row in UNPAID state with a deterministic request_hash.
    @Test
    public void quote_PersistsDurableRowWhenRepositoryProvided() throws CashuErrorException {
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.createMintQuote(anyInt(), Mockito.isNull())).thenReturn("qid-durable");
        when(gateway.getRequest("qid-durable")).thenReturn("lnbc1...");
        when(gateway.getPaymentExpiry("qid-durable")).thenReturn(900);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.BOLT11, "sat")).thenReturn(gateway);

        MintQuoteRepository repository = Mockito.mock(MintQuoteRepository.class);

        MintQuoteTask task = new MintQuoteTask(
                100L, PaymentMethod.BOLT11, "sat", service, repository, "https://mint.example");
        PostMintQuoteResponse response = task.execute();

        assertEquals("qid-durable", response.getQuoteId());

        ArgumentCaptor<MintQuote> captor = ArgumentCaptor.forClass(MintQuote.class);
        verify(repository).save(captor.capture());
        MintQuote persisted = captor.getValue();
        assertThat(persisted.quoteId()).isEqualTo("qid-durable");
        assertThat(persisted.amount()).isEqualTo(100L);
        assertThat(persisted.unit()).isEqualTo("sat");
        assertThat(persisted.mintUrl()).isEqualTo("https://mint.example");
        assertThat(persisted.paymentMethod()).isEqualTo("BOLT11");
        assertThat(persisted.lifecycleState()).isEqualTo(LifecycleState.UNPAID);
        assertThat(persisted.requestHash()).hasSize(64); // sha-256 hex
        assertThat(persisted.invoiceId()).isEqualTo("qid-durable");
    }

    // The legacy path (no repository) must not call repository.save().
    @Test
    public void quote_DoesNotPersistWhenRepositoryAbsent() throws CashuErrorException {
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.createMintQuote(anyInt(), Mockito.isNull())).thenReturn("qid-legacy");
        when(gateway.getRequest("qid-legacy")).thenReturn("req");
        when(gateway.getPaymentExpiry("qid-legacy")).thenReturn(60);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.MOCK)).thenReturn(gateway);

        MintQuoteRepository repository = Mockito.mock(MintQuoteRepository.class);

        MintQuoteTask task = new MintQuoteTask(7L, PaymentMethod.MOCK, service);
        task.execute();

        verify(repository, never()).save(Mockito.any());
    }
}
