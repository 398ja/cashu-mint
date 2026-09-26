package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteResponse;
import xyz.tcheeric.cashu.mint.proto.ports.MintIntegrityContext;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuote;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuoteRepository;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.util.VoucherQuoteRegistry;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The voucher quote is recorded before the invoice is raised, so a failed write cannot leave a
 * payable invoice behind it.
 *
 * <p>Raising the invoice is irreversible: it is payable the moment it exists and the gateway cannot
 * withdraw it. The task used to raise the invoice first and record the quote second, so any failure
 * of the write left a payable invoice with no record. On staging the customer paid, the mint
 * accepted the payment, and the request that created the quote had already returned an error to a
 * client that never came back: twelve quotes sat PAID with nothing issued and no process responsible
 * for them (cashu-mint#469).
 *
 * <p>Recording first requires knowing the quote id before the gateway is called, so the task now
 * chooses it and hands it to the gateway. A failed write then refuses the quote while nothing is yet
 * payable. The reverse failure, a row written and the invoice then not raised, leaves an UNFUNDED
 * row that nothing can be paid against or minted from: inert, where the old failure took money.
 */
@DisplayName("a voucher quote is recorded before its invoice is raised (#469)")
class VoucherMintQuoteRecordsBeforeInvoicingTest {

    private static final int FACE_VALUE = 1000;
    private static final int FEE_PRICE = 100;

    private Gateway gateway;
    private MintProtocolService service;
    private VoucherQuoteRepository repository;

    @BeforeEach
    void wireADurableRepository() {
        gateway = Mockito.mock(Gateway.class);
        when(gateway.createMintQuote(anyString(), anyInt(), isNull()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(gateway.getRequest(anyString())).thenReturn("lnbc...");
        when(gateway.getPaymentExpiry(anyString())).thenReturn(3600);

        service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.MOCK)).thenReturn(gateway);

        repository = Mockito.mock(VoucherQuoteRepository.class);
        when(repository.save(any(VoucherQuote.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MintIntegrityContext.installVoucher(repository, null, null, null, null, null);
    }

    @AfterEach
    void unwire() {
        MintIntegrityContext.clear();
        VoucherQuoteRegistry.clear();
    }

    /**
     * The ordering itself. The row must be saved before the gateway is asked for an invoice. This is
     * the assertion that fails against the old code, which called the gateway first.
     */
    @Test
    @DisplayName("the row is saved before the invoice is raised")
    void theRowIsSavedBeforeTheInvoice() throws CashuErrorException {
        new VoucherMintQuoteTask(FACE_VALUE, PaymentMethod.MOCK, service).execute();

        InOrder order = Mockito.inOrder(repository, gateway);
        order.verify(repository).save(any(VoucherQuote.class));
        order.verify(gateway).createMintQuote(anyString(), eq(FEE_PRICE), isNull());
    }

    /**
     * The harm #469 describes, prevented. When the write fails, no invoice may exist, because an
     * invoice raised for a refused quote is what took the customer's money.
     */
    @Test
    @DisplayName("a failed write raises no invoice at all")
    void aFailedWriteRaisesNoInvoice() {
        when(repository.save(any(VoucherQuote.class)))
                .thenThrow(new IllegalStateException("simulated transient database failure"));

        assertThatThrownBy(() -> new VoucherMintQuoteTask(FACE_VALUE, PaymentMethod.MOCK, service).execute())
                .isInstanceOf(CashuErrorException.class)
                .hasMessageContaining("voucher_quote_persist_failed");

        verify(gateway, never()).createMintQuote(anyString(), anyInt(), any());
        verify(gateway, never()).createMintQuote(anyInt(), any());
    }

    /**
     * The row and the invoice must name the same quote. Choosing the id before the gateway is only
     * safe if the gateway then uses it: a record under one id and an invoice under another would
     * leave the payment unmatched, which is the stranding moved one step later.
     */
    @Test
    @DisplayName("the row, the invoice and the response all carry the same quote id")
    void theRowTheInvoiceAndTheResponseAgreeOnTheId() throws CashuErrorException {
        PostMintQuoteResponse response = new VoucherMintQuoteTask(FACE_VALUE, PaymentMethod.MOCK, service).execute();

        ArgumentCaptor<VoucherQuote> saved = ArgumentCaptor.forClass(VoucherQuote.class);
        verify(repository).save(saved.capture());
        ArgumentCaptor<String> invoiced = ArgumentCaptor.forClass(String.class);
        verify(gateway).createMintQuote(invoiced.capture(), eq(FEE_PRICE), isNull());

        String recordedId = saved.getValue().quoteId();
        assertThat(recordedId).as("the recorded quote id").isNotBlank();
        assertThat(invoiced.getValue()).as("the id the invoice was raised under").isEqualTo(recordedId);
        assertThat(response.getQuoteId()).as("the id returned to the client").isEqualTo(recordedId);
    }

    /**
     * Each quote gets its own id. The id is now chosen by the mint rather than the gateway, so a
     * constant or reused value here would collide on the voucher_quote primary key.
     */
    @Test
    @DisplayName("each quote is given a distinct id")
    void eachQuoteGetsADistinctId() throws CashuErrorException {
        String first = new VoucherMintQuoteTask(FACE_VALUE, PaymentMethod.MOCK, service).execute().getQuoteId();
        String second = new VoucherMintQuoteTask(FACE_VALUE, PaymentMethod.MOCK, service).execute().getQuoteId();

        assertThat(first).isNotEqualTo(second);
    }

    /**
     * A gateway that raises the invoice under a different id than it was given breaks the contract
     * the reorder relies on. The task must refuse loudly rather than hand the client a quote whose
     * record and invoice disagree, because that payment would arrive unmatched.
     */
    @Test
    @DisplayName("a gateway that changes the quote id is refused, not trusted")
    void aGatewayThatChangesTheIdIsRefused() {
        when(gateway.createMintQuote(anyString(), anyInt(), isNull())).thenReturn("a-different-id");

        assertThatThrownBy(() -> new VoucherMintQuoteTask(FACE_VALUE, PaymentMethod.MOCK, service).execute())
                .isInstanceOf(CashuErrorException.class)
                .hasMessageContaining("voucher_quote_id_mismatch");
    }

    /**
     * The existing price guard still refuses before anything is written or raised. Moving the write
     * earlier must not let a quote the task already refuses leave an orphan row behind.
     */
    @Test
    @DisplayName("an unchargeable price is refused before anything is written or raised")
    void anUnchargeablePriceIsRefusedBeforeAnyWrite() {
        assertThatThrownBy(() -> new VoucherMintQuoteTask(0, PaymentMethod.MOCK, service).execute())
                .isInstanceOf(CashuErrorException.class);

        verify(repository, never()).save(any(VoucherQuote.class));
        verify(gateway, never()).createMintQuote(anyString(), anyInt(), any());
    }
}
