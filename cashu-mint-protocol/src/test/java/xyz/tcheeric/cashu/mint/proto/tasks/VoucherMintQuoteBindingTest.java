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
import xyz.tcheeric.cashu.mint.proto.ports.MintIntegrityContext;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuote;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuoteRepository;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.util.VoucherQuoteRegistry;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A voucher quote records the voucher it was made for, at creation and before its invoice.
 *
 * <p>A gateway auditing an issuance trusts this record rather than the issuer's word for which
 * voucher a paid quote belongs to, so a genuine but different paid quote cannot be substituted.
 */
@DisplayName("a voucher quote records its voucher binding at creation")
class VoucherMintQuoteBindingTest {

    private static final int FACE_VALUE = 1000;
    private static final String VOUCHER_ID = "6f1c2b9e-1d2a-4c3b-9f8e-7a6b5c4d3e2f";

    private Gateway gateway;
    private MintProtocolService service;
    private VoucherQuoteRepository repository;

    @BeforeEach
    void wire() {
        gateway = mock(Gateway.class);
        when(gateway.createMintQuote(anyString(), anyInt(), isNull()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(gateway.getRequest(anyString())).thenReturn("lnbc...");
        when(gateway.getPaymentExpiry(anyString())).thenReturn(3600);
        service = mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.MOCK)).thenReturn(gateway);
        repository = mock(VoucherQuoteRepository.class);
        when(repository.save(any(VoucherQuote.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findByVoucherId(anyString())).thenReturn(Optional.empty());
        MintIntegrityContext.installVoucher(repository, null, null, null, null, null);
    }

    @AfterEach
    void unwire() {
        MintIntegrityContext.clear();
        VoucherQuoteRegistry.clear();
    }

    private VoucherMintQuoteTask bound(String voucherId) {
        return new VoucherMintQuoteTask(FACE_VALUE, PaymentMethod.MOCK, null, service, null, voucherId);
    }

    @Test
    @DisplayName("the voucher id is saved on the quote row, before the invoice is raised")
    void bindingIsRecordedBeforeInvoicing() throws Exception {
        bound(VOUCHER_ID).execute();

        ArgumentCaptor<VoucherQuote> saved = ArgumentCaptor.forClass(VoucherQuote.class);
        InOrder order = Mockito.inOrder(repository, gateway);
        order.verify(repository).save(saved.capture());
        order.verify(gateway).createMintQuote(anyString(), anyInt(), isNull());
        assertThat(saved.getValue().voucherId()).isEqualTo(VOUCHER_ID);
    }

    @Test
    @DisplayName("a quote made without a voucher id stays unbound, as before")
    void unboundQuoteStaysUnbound() throws Exception {
        new VoucherMintQuoteTask(FACE_VALUE, PaymentMethod.MOCK, service).execute();

        ArgumentCaptor<VoucherQuote> saved = ArgumentCaptor.forClass(VoucherQuote.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().voucherId()).isNull();
    }

    @Test
    @DisplayName("a voucher id another quote already has is refused, and nothing is invoiced")
    void secondQuoteForTheSameVoucherIsRefused() {
        when(repository.findByVoucherId(VOUCHER_ID)).thenReturn(Optional.of(mock(VoucherQuote.class)));

        assertThatThrownBy(() -> bound(VOUCHER_ID).execute())
                .isInstanceOf(CashuErrorException.class)
                .hasMessageContaining("voucher_id_already_bound");
        verify(repository, never()).save(any());
        verify(gateway, never()).createMintQuote(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("a malformed voucher id is refused, and nothing is written or invoiced")
    void malformedVoucherIdIsRefused() {
        assertThatThrownBy(() -> bound("not-a-uuid'; drop").execute())
                .isInstanceOf(CashuErrorException.class)
                .hasMessageContaining("voucher_id_invalid");
        verify(repository, never()).save(any());
        verify(gateway, never()).createMintQuote(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("a binding is refused when there is no durable store to keep it")
    void bindingWithoutDurableStoreIsRefused() {
        MintIntegrityContext.clear();

        assertThatThrownBy(() -> bound(VOUCHER_ID).execute())
                .isInstanceOf(CashuErrorException.class)
                .hasMessageContaining("voucher_binding_unsupported");
        verify(gateway, never()).createMintQuote(anyString(), anyInt(), any());
    }
}
