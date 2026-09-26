package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A regular mint quote is recorded before its invoice is raised, so a failed write cannot leave a
 * payable invoice with no quote behind it (cashu-mint#502, the regular half of #469).
 */
@DisplayName("a regular mint quote is recorded before its invoice is raised (#502)")
class MintQuoteRecordsBeforeInvoicingTest {

    private Gateway gateway;
    private MintProtocolService service;
    private MintQuoteRepository repository;

    @BeforeEach
    void wireADurableRepository() {
        gateway = Mockito.mock(Gateway.class);
        when(gateway.createMintQuote(anyString(), anyInt(), isNull()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(gateway.getRequest(anyString())).thenReturn("lnbc640n1test");
        when(gateway.getPaymentExpiry(anyString())).thenReturn(600);
        service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.BOLT11, "sat")).thenReturn(gateway);
        repository = Mockito.mock(MintQuoteRepository.class);
    }

    private PostMintQuoteResponse quote() throws CashuErrorException {
        return new MintQuoteTask(64L, PaymentMethod.BOLT11, "sat", service, repository,
                "https://mint.example").execute();
    }

    // The ordering itself: the row is saved, then the invoice is raised.
    @Test
    void theRowIsSavedBeforeTheInvoice() throws CashuErrorException {
        quote();

        InOrder order = Mockito.inOrder(repository, gateway);
        order.verify(repository).save(any(MintQuote.class));
        order.verify(gateway).createMintQuote(anyString(), anyInt(), isNull());
    }

    // The failure the reorder exists for: a write that fails raises no invoice, so nobody can be
    // charged for a quote the mint has no record of.
    @Test
    void aFailedWriteRaisesNoInvoice() {
        doThrow(new IllegalStateException("database unavailable")).when(repository).save(any());

        assertThatThrownBy(this::quote).isInstanceOf(CashuErrorException.class);
        verify(gateway, never()).createMintQuote(anyString(), anyInt(), any());
        verify(gateway, never()).createMintQuote(anyInt(), any());
    }

    // The row, the invoice and the response all name the same quote, so the webhook for the
    // payment finds the row and the client mints against it.
    @Test
    void theRowTheInvoiceAndTheResponseShareOneId() throws CashuErrorException {
        PostMintQuoteResponse response = quote();

        ArgumentCaptor<MintQuote> saved = ArgumentCaptor.forClass(MintQuote.class);
        verify(repository).save(saved.capture());
        ArgumentCaptor<String> invoiced = ArgumentCaptor.forClass(String.class);
        verify(gateway).createMintQuote(invoiced.capture(), anyInt(), isNull());

        assertThat(saved.getValue().quoteId()).isEqualTo(response.getQuoteId());
        assertThat(invoiced.getValue()).isEqualTo(response.getQuoteId());
        assertThat(saved.getValue().invoiceId()).isEqualTo(response.getQuoteId());
    }

    // A gateway that raises the invoice under another id is refused rather than trusted: the
    // recorded row would name an invoice that does not exist.
    @Test
    void aGatewayThatChangesTheIdIsRefused() {
        when(gateway.createMintQuote(anyString(), anyInt(), isNull())).thenReturn("gateway-chosen");

        assertThatThrownBy(this::quote).isInstanceOf(CashuErrorException.class);
    }

    // Two quotes get two ids: the mint's id is fresh per quote, not reused.
    @Test
    void eachQuoteGetsItsOwnId() throws CashuErrorException {
        assertThat(quote().getQuoteId()).isNotEqualTo(quote().getQuoteId());
    }

    // A gateway that cannot take a caller-chosen id (payment-adapter's default refuses before
    // raising anything) still quotes, in the old invoice-then-record order: refusing every regular
    // quote on such a gateway would take a working mint down. The quote is recorded under the
    // gateway's id, so its payment still finds its row.
    @Test
    void aGatewayThatCannotTakeTheIdStillQuotesUnderItsOwnId() throws CashuErrorException {
        when(gateway.createMintQuote(anyString(), anyInt(), isNull()))
                .thenThrow(new UnsupportedOperationException("cannot take a caller-supplied id"));
        when(gateway.createMintQuote(anyInt(), isNull())).thenReturn("gateway-id");

        PostMintQuoteResponse response = quote();

        assertThat(response.getQuoteId()).isEqualTo("gateway-id");
        ArgumentCaptor<MintQuote> saved = ArgumentCaptor.forClass(MintQuote.class);
        verify(repository, Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(1).quoteId()).isEqualTo("gateway-id");
    }

    // The fallback leaves the row written under the mint's id behind; it is marked FAILED so it
    // does not read as an open quote, and the gateway's row is left alone.
    @Test
    void theFallbackAbandonsTheRowWrittenUnderTheMintsId() throws CashuErrorException {
        when(gateway.createMintQuote(anyString(), anyInt(), isNull()))
                .thenThrow(new UnsupportedOperationException("cannot take a caller-supplied id"));
        when(gateway.createMintQuote(anyInt(), isNull())).thenReturn("gateway-id");

        quote();

        ArgumentCaptor<MintQuote> saved = ArgumentCaptor.forClass(MintQuote.class);
        verify(repository, Mockito.times(2)).save(saved.capture());
        String mintsId = saved.getAllValues().get(0).quoteId();
        verify(repository).casLifecycle(mintsId, LifecycleState.UNPAID, LifecycleState.FAILED);
        verify(repository, never()).casLifecycle(eq("gateway-id"), any(), any());
    }

    // An invoice that fails leaves a row nobody can pay; it is marked FAILED and the gateway's
    // error still reaches the caller.
    @Test
    void aFailedInvoiceAbandonsItsRow() {
        when(gateway.createMintQuote(anyString(), anyInt(), isNull()))
                .thenThrow(new IllegalStateException("phoenixd unreachable"));

        assertThatThrownBy(this::quote).isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<MintQuote> saved = ArgumentCaptor.forClass(MintQuote.class);
        verify(repository).save(saved.capture());
        verify(repository).casLifecycle(saved.getValue().quoteId(), LifecycleState.UNPAID, LifecycleState.FAILED);
    }

    // A gateway that changes the id leaves the recorded row naming no invoice; it is marked FAILED.
    @Test
    void aChangedIdAbandonsTheRecordedRow() {
        when(gateway.createMintQuote(anyString(), anyInt(), isNull())).thenReturn("gateway-chosen");

        assertThatThrownBy(this::quote).isInstanceOf(CashuErrorException.class);

        verify(repository).casLifecycle(anyString(), eq(LifecycleState.UNPAID), eq(LifecycleState.FAILED));
    }

    // Abandoning is best effort: if marking the row fails, the caller still sees the original
    // gateway error, not the repository's.
    @Test
    void aFailedAbandonDoesNotMaskTheInvoiceError() {
        when(gateway.createMintQuote(anyString(), anyInt(), isNull()))
                .thenThrow(new IllegalStateException("phoenixd unreachable"));
        when(repository.casLifecycle(anyString(), any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(this::quote).hasMessage("phoenixd unreachable");
    }

    // A quote that succeeds is never abandoned.
    @Test
    void aSuccessfulQuoteIsNotAbandoned() throws CashuErrorException {
        quote();

        verify(repository, never()).casLifecycle(anyString(), any(), any());
    }
}
