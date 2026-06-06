package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteResponse;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.MintIntegrityContext;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuoteRepository;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuote;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuoteRepository;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Covers the NUT-04 v1 status response (amount/unit/state) and the multi-repo
 * resolution added for the spec-041 client-mint fix.
 */
class MintQuoteStatusTaskTest {

    @AfterEach
    void tearDown() {
        MintIntegrityContext.clear();
    }

    private Gateway gateway(String quoteId, boolean paid) {
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.getRequest(quoteId)).thenReturn("req");
        when(gateway.getPaymentExpiry(quoteId)).thenReturn(60);
        when(gateway.checkPaymentStatus(quoteId)).thenReturn(paid);
        return gateway;
    }

    private MintProtocolService service(Gateway gateway) {
        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.MOCK)).thenReturn(gateway);
        return service;
    }

    // No repository wired (legacy/unit-test path): amount=0, unit defaults to
    // "sat", state derives from the payment flag.
    @Test
    void status_noRepository_fallsBackToPaymentFlag() throws CashuErrorException {
        MintProtocolService service = service(gateway("qid", true));

        PostMintQuoteResponse response = new MintQuoteStatusTask("qid", PaymentMethod.MOCK, service).execute();

        assertThat(response.getAmount()).isZero();
        assertThat(response.getUnit()).isEqualTo("sat");
        assertThat(response.getState()).isEqualTo("PAID");
        assertThat(response.isPaid()).isTrue();
    }

    // Regular quote row drives amount/unit; PAID lifecycle maps to v1 PAID.
    @Test
    void status_regularQuote_emitsV1Fields() throws CashuErrorException {
        MintProtocolService service = service(gateway("qid", true));
        MintQuoteRepository repo = Mockito.mock(MintQuoteRepository.class);
        MintQuote quote = Mockito.mock(MintQuote.class);
        when(quote.amount()).thenReturn(127L);
        when(quote.unit()).thenReturn("sat");
        when(quote.lifecycleState()).thenReturn(LifecycleState.PAID);
        when(repo.findById("qid")).thenReturn(Optional.of(quote));
        MintIntegrityContext.install(repo, null, null, "https://mint.example");

        PostMintQuoteResponse response = new MintQuoteStatusTask("qid", PaymentMethod.MOCK, service).execute();

        assertThat(response.getAmount()).isEqualTo(127);
        assertThat(response.getUnit()).isEqualTo("sat");
        assertThat(response.getState()).isEqualTo("PAID");
    }

    @Test
    void status_regularQuote_issuedMapsToIssued() throws CashuErrorException {
        MintProtocolService service = service(gateway("qid", true));
        MintQuoteRepository repo = Mockito.mock(MintQuoteRepository.class);
        MintQuote quote = Mockito.mock(MintQuote.class);
        when(quote.amount()).thenReturn(10L);
        when(quote.unit()).thenReturn("sat");
        when(quote.lifecycleState()).thenReturn(LifecycleState.ISSUED);
        when(repo.findById("qid")).thenReturn(Optional.of(quote));
        MintIntegrityContext.install(repo, null, null, null);

        PostMintQuoteResponse response = new MintQuoteStatusTask("qid", PaymentMethod.MOCK, service).execute();

        assertThat(response.getState()).isEqualTo("ISSUED");
    }

    // Voucher quote IDs live only in the voucher repository; the status task must
    // fall back to it and emit the FACE VALUE as the amount (Codex P2).
    @Test
    void status_voucherQuote_resolvesFromVoucherRepository() throws CashuErrorException {
        MintProtocolService service = service(gateway("vqid", true));
        MintIntegrityContext.install(Mockito.mock(MintQuoteRepository.class), null, null, null);

        VoucherQuoteRepository voucherRepo = Mockito.mock(VoucherQuoteRepository.class);
        VoucherQuote vq = Mockito.mock(VoucherQuote.class);
        when(vq.faceValue()).thenReturn(1000L);
        when(vq.unit()).thenReturn("sat");
        when(vq.lifecycleState()).thenReturn(VoucherLifecycleState.FUNDED);
        when(voucherRepo.findById("vqid")).thenReturn(Optional.of(vq));
        MintIntegrityContext.installVoucher(voucherRepo, null, null, null, null, null);

        PostMintQuoteResponse response = new MintQuoteStatusTask("vqid", PaymentMethod.MOCK, service).execute();

        assertThat(response.getAmount()).isEqualTo(1000);
        assertThat(response.getUnit()).isEqualTo("sat");
        assertThat(response.getState()).isEqualTo("PAID"); // FUNDED -> PAID
    }

    // A bad/legacy row with an out-of-int-range amount must clamp, never overflow
    // into a negative wire value.
    @Test
    void status_amountOverflow_clampsToIntMax() throws CashuErrorException {
        MintProtocolService service = service(gateway("qid", false));
        MintQuoteRepository repo = Mockito.mock(MintQuoteRepository.class);
        MintQuote quote = Mockito.mock(MintQuote.class);
        when(quote.amount()).thenReturn((long) Integer.MAX_VALUE + 5L);
        when(quote.unit()).thenReturn("sat");
        when(quote.lifecycleState()).thenReturn(LifecycleState.UNPAID);
        when(repo.findById("qid")).thenReturn(Optional.of(quote));
        MintIntegrityContext.install(repo, null, null, null);

        PostMintQuoteResponse response = new MintQuoteStatusTask("qid", PaymentMethod.MOCK, service).execute();

        assertThat(response.getAmount()).isEqualTo(Integer.MAX_VALUE);
        assertThat(response.getState()).isEqualTo("UNPAID");
    }
}
