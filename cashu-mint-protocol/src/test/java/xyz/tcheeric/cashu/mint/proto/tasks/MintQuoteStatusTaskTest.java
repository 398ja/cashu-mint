package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteResponse;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.IssuanceRecord;
import xyz.tcheeric.cashu.mint.proto.ports.IssuanceRecordRepository;
import xyz.tcheeric.cashu.mint.proto.ports.MintIntegrityContext;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuoteRepository;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuote;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuoteRepository;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherMintQuoteResponse;
import xyz.tcheeric.cashu.mint.proto.tasks.MintQuoteStatusTask.Kind;
import xyz.tcheeric.cashu.mint.proto.util.VoucherQuoteRegistry;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the NUT-04 status response and which route may answer for which quote kind
 * (cashu-mint#494).
 */
class MintQuoteStatusTaskTest {

    private static final Instant CREATED = Instant.parse("2026-09-26T14:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-09-26T14:00:05Z");

    @AfterEach
    void tearDown() {
        MintIntegrityContext.clear();
    }

    private Gateway gateway(String quoteId, boolean paid) {
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.getRequest(quoteId)).thenReturn("req");
        when(gateway.getPaymentExpiry(quoteId)).thenReturn(60);
        when(gateway.getCreatedAt(quoteId)).thenReturn(CREATED);
        when(gateway.checkPaymentStatus(quoteId)).thenReturn(paid);
        return gateway;
    }

    private MintProtocolService service(Gateway gateway) {
        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.MOCK)).thenReturn(gateway);
        return service;
    }

    private static MintQuote regularQuote(long amount, LifecycleState state) {
        MintQuote quote = Mockito.mock(MintQuote.class);
        when(quote.amount()).thenReturn(amount);
        when(quote.unit()).thenReturn("sat");
        when(quote.lifecycleState()).thenReturn(state);
        when(quote.createdAt()).thenReturn(CREATED);
        when(quote.updatedAt()).thenReturn(UPDATED);
        return quote;
    }

    private static VoucherQuote voucherQuote(long faceValue, long charged, VoucherLifecycleState state) {
        VoucherQuote quote = Mockito.mock(VoucherQuote.class);
        when(quote.faceValue()).thenReturn(faceValue);
        when(quote.chargedAmount()).thenReturn(charged);
        when(quote.unit()).thenReturn("sat");
        when(quote.lifecycleState()).thenReturn(state);
        when(quote.createdAt()).thenReturn(CREATED);
        when(quote.updatedAt()).thenReturn(UPDATED);
        return quote;
    }

    private static void installRegular(String quoteId, MintQuote quote) {
        MintQuoteRepository repo = Mockito.mock(MintQuoteRepository.class);
        when(repo.findById(any())).thenReturn(Optional.empty());
        if (quote != null) {
            when(repo.findById(quoteId)).thenReturn(Optional.of(quote));
        }
        MintIntegrityContext.install(repo, null, "https://mint.example");
    }

    private static void installVoucher(String quoteId, VoucherQuote quote) {
        VoucherQuoteRepository repo = Mockito.mock(VoucherQuoteRepository.class);
        when(repo.findById(any())).thenReturn(Optional.empty());
        if (quote != null) {
            when(repo.findById(quoteId)).thenReturn(Optional.of(quote));
        }
        MintIntegrityContext.installVoucher(repo, null, null, null, null, null);
    }

    private static PostMintQuoteResponse status(String quoteId, Kind kind, MintProtocolService service)
            throws CashuErrorException {
        return new MintQuoteStatusTask(quoteId, PaymentMethod.MOCK, null, kind, service).execute();
    }

    // With no repository wired (legacy/unit-test path) the task cannot classify the id, so it
    // answers from the payment flag: amount 0, unit "sat", PAID from the gateway.
    @Test
    void status_noRepository_fallsBackToPaymentFlag() throws CashuErrorException {
        MintProtocolService service = service(gateway("qid", true));

        PostMintQuoteResponse response = new MintQuoteStatusTask("qid", PaymentMethod.MOCK, service).execute();

        assertThat(response.getAmount()).isZero();
        assertThat(response.getUnit()).isEqualTo("sat");
        assertThat(response.getState()).isEqualTo("PAID");
        assertThat(response.isPaid()).isTrue();
    }

    // A regular quote row drives amount, unit and state on the regular route.
    @Test
    void status_regularQuote_emitsV1Fields() throws CashuErrorException {
        installRegular("qid", regularQuote(127L, LifecycleState.PAID));

        PostMintQuoteResponse response = status("qid", Kind.REGULAR, service(gateway("qid", true)));

        assertThat(response.getAmount()).isEqualTo(127);
        assertThat(response.getUnit()).isEqualTo("sat");
        assertThat(response.getState()).isEqualTo("PAID");
    }

    // An ISSUED regular quote reports ISSUED.
    @Test
    void status_regularQuote_issuedMapsToIssued() throws CashuErrorException {
        installRegular("qid", regularQuote(10L, LifecycleState.ISSUED));

        PostMintQuoteResponse response = status("qid", Kind.REGULAR, service(gateway("qid", true)));

        assertThat(response.getState()).isEqualTo("ISSUED");
    }

    // #494: the regular route refuses a voucher quote id. Answering would report the face value
    // as the amount while the invoice charged only a fee, so a verifier reading state=ISSUED,
    // amount=X could not tell whether X or a tenth of it was paid.
    @Test
    void regularRoute_refusesVoucherQuote() {
        installRegular("vqid", null);
        installVoucher("vqid", voucherQuote(1000L, 100L, VoucherLifecycleState.ISSUED));
        Gateway gateway = gateway("vqid", true);

        assertThatThrownBy(() -> status("vqid", Kind.REGULAR, service(gateway)))
                .isInstanceOf(CashuErrorException.class)
                .extracting(e -> ((CashuErrorException) e).getErrorCode())
                .isEqualTo(CashuErrorCode.quote_not_found);
        verify(gateway, never()).checkPaymentStatus(any());
    }

    // #494: without the durable tables the in-memory voucher registry still classifies the id, so
    // the regular route refuses a voucher quote on the legacy path too.
    @Test
    void regularRoute_refusesRegistryOnlyVoucherQuote() {
        VoucherQuoteRegistry.storeFaceValue("vqid", 1000L);
        try {
            assertThatThrownBy(() -> status("vqid", Kind.REGULAR, service(gateway("vqid", true))))
                    .isInstanceOf(CashuErrorException.class)
                    .extracting(e -> ((CashuErrorException) e).getErrorCode())
                    .isEqualTo(CashuErrorCode.quote_not_found);
        } finally {
            VoucherQuoteRegistry.removeFaceValue("vqid");
        }
    }

    // #494: the voucher route refuses a regular quote id, so neither route answers for the other.
    @Test
    void voucherRoute_refusesRegularQuote() {
        installRegular("qid", regularQuote(64L, LifecycleState.ISSUED));
        installVoucher("qid", null);

        assertThatThrownBy(() -> status("qid", Kind.VOUCHER, service(gateway("qid", true))))
                .isInstanceOf(CashuErrorException.class)
                .extracting(e -> ((CashuErrorException) e).getErrorCode())
                .isEqualTo(CashuErrorCode.voucher_quote_not_found);
    }

    // An id neither table knows is not found on the route whose repository is wired, instead of
    // being reported UNPAID with amount 0 as if it existed.
    @Test
    void regularRoute_unknownQuoteIsNotFound() {
        installRegular("nope", null);

        assertThatThrownBy(() -> status("nope", Kind.REGULAR, service(gateway("nope", false))))
                .isInstanceOf(CashuErrorException.class)
                .extracting(e -> ((CashuErrorException) e).getErrorCode())
                .isEqualTo(CashuErrorCode.quote_not_found);
    }

    // #499: a paid voucher reports its face value as amount_paid, so the NUT-04 mintable amount
    // (amount_paid - amount_issued) is the face value a wallet sizes its outputs to. What the
    // invoice charged travels separately, as charged_amount.
    @Test
    void voucherRoute_paidReportsFaceValueAsPaidAndFeeAsCharged() throws CashuErrorException {
        installRegular("vqid", null);
        installVoucher("vqid", voucherQuote(1000L, 100L, VoucherLifecycleState.FUNDED));

        PostMintQuoteResponse response = status("vqid", Kind.VOUCHER, service(gateway("vqid", true)));

        assertThat(response.getAmount()).isEqualTo(1000);
        assertThat(response.getAmountPaid()).isEqualTo(1000L);
        assertThat(response.getAmountIssued()).isZero();
        assertThat(response.getState()).isEqualTo("PAID");
        assertThat(response).isInstanceOfSatisfying(VoucherMintQuoteResponse.class,
                voucher -> assertThat(voucher.getChargedAmount()).isEqualTo(100L));
    }

    // #499: NUT-04 requires amount_issued <= amount_paid. An issued voucher used to report 100
    // paid and 1000 issued; it now reports the face value for both, and the fee as charged.
    @Test
    void voucherRoute_issuedReportsFaceValueIssued() throws CashuErrorException {
        installRegular("vqid", null);
        installVoucher("vqid", voucherQuote(1000L, 100L, VoucherLifecycleState.ISSUED));

        PostMintQuoteResponse response = status("vqid", Kind.VOUCHER, service(gateway("vqid", true)));

        assertThat(response.getState()).isEqualTo("ISSUED");
        assertThat(response.getAmountPaid()).isEqualTo(1000L);
        assertThat(response.getAmountIssued()).isEqualTo(1000L);
        assertThat(response.getAmountIssued()).isLessThanOrEqualTo(response.getAmountPaid());
        assertThat(((VoucherMintQuoteResponse) response).getChargedAmount()).isEqualTo(100L);
    }

    // An unpaid voucher has paid and issued nothing, but its price is already known, so a client
    // can show what the invoice will charge before it is paid.
    @Test
    void voucherRoute_unpaidReportsChargeButNothingPaid() throws CashuErrorException {
        installRegular("vqid", null);
        installVoucher("vqid", voucherQuote(1000L, 100L, VoucherLifecycleState.UNFUNDED));

        PostMintQuoteResponse response = status("vqid", Kind.VOUCHER, service(gateway("vqid", false)));

        assertThat(response.getState()).isEqualTo("UNPAID");
        assertThat(response.getAmountPaid()).isZero();
        assertThat(response.getAmountIssued()).isZero();
        assertThat(((VoucherMintQuoteResponse) response).getChargedAmount()).isEqualTo(100L);
    }

    // charged_amount is a voucher-route field only; the regular route answers with the plain
    // NUT-04 response, whose invoice charges its amount.
    @Test
    void regularRoute_answersWithThePlainNut04Response() throws CashuErrorException {
        installRegular("qid", regularQuote(64L, LifecycleState.PAID));

        PostMintQuoteResponse response = status("qid", Kind.REGULAR, service(gateway("qid", true)));

        assertThat(response).isNotInstanceOf(VoucherMintQuoteResponse.class);
    }

    // NUT-04: amount_paid and amount_issued follow the lifecycle. An unpaid quote has paid and
    // issued nothing, a paid one has paid its amount, an issued one has issued it too.
    @Test
    void regularRoute_reportsAccountingFieldsByLifecycle() throws CashuErrorException {
        installRegular("u", regularQuote(64L, LifecycleState.UNPAID));
        PostMintQuoteResponse unpaid = status("u", Kind.REGULAR, service(gateway("u", false)));
        assertThat(unpaid.getAmountPaid()).isZero();
        assertThat(unpaid.getAmountIssued()).isZero();

        installRegular("p", regularQuote(64L, LifecycleState.PAID));
        PostMintQuoteResponse paid = status("p", Kind.REGULAR, service(gateway("p", true)));
        assertThat(paid.getAmountPaid()).isEqualTo(64L);
        assertThat(paid.getAmountIssued()).isZero();

        installRegular("i", regularQuote(64L, LifecycleState.ISSUED));
        PostMintQuoteResponse issued = status("i", Kind.REGULAR, service(gateway("i", true)));
        assertThat(issued.getAmountPaid()).isEqualTo(64L);
        assertThat(issued.getAmountIssued()).isEqualTo(64L);
        assertThat(issued.getUpdatedAt()).isEqualTo(UPDATED.getEpochSecond());
    }

    // An ISSUING quote whose issuance ledger row exists has had its signatures produced and
    // recorded; only the final lifecycle write is outstanding, so it is reported ISSUED.
    @Test
    void regularRoute_issuingWithLedgerRowReportsIssued() throws CashuErrorException {
        MintQuote issuing = regularQuote(64L, LifecycleState.ISSUING);
        MintQuoteRepository repo = Mockito.mock(MintQuoteRepository.class);
        when(repo.findById("qid")).thenReturn(Optional.of(issuing));
        IssuanceRecord ledgerRow = Mockito.mock(IssuanceRecord.class);
        IssuanceRecordRepository issuance = Mockito.mock(IssuanceRecordRepository.class);
        when(issuance.findById("qid")).thenReturn(Optional.of(ledgerRow));
        MintIntegrityContext.install(repo, issuance, null);

        PostMintQuoteResponse response = status("qid", Kind.REGULAR, service(gateway("qid", true)));

        assertThat(response.getState()).isEqualTo("ISSUED");
        assertThat(response.getAmountIssued()).isEqualTo(64L);
    }

    private static void installIssuing(MintQuote quote, Instant issuedAt) {
        MintQuoteRepository repo = Mockito.mock(MintQuoteRepository.class);
        when(repo.findById("qid")).thenReturn(Optional.of(quote));
        IssuanceRecord ledgerRow = Mockito.mock(IssuanceRecord.class);
        when(ledgerRow.issuedAt()).thenReturn(issuedAt);
        IssuanceRecordRepository issuance = Mockito.mock(IssuanceRecordRepository.class);
        when(issuance.findById("qid")).thenReturn(Optional.of(ledgerRow));
        MintIntegrityContext.install(repo, issuance, null);
    }

    // #501: an ISSUING quote reported ISSUED had its amount_issued change when the ledger row was
    // written, after the quote row was last stamped. NUT-04 requires updated_at to move whenever
    // amount_issued changes, so it reports the ledger's issued_at.
    @Test
    void regularRoute_issuingReportedIssuedTakesUpdatedAtFromTheLedger() throws CashuErrorException {
        Instant issuedAt = UPDATED.plusSeconds(3);
        installIssuing(regularQuote(64L, LifecycleState.ISSUING), issuedAt);

        PostMintQuoteResponse response = status("qid", Kind.REGULAR, service(gateway("qid", true)));

        assertThat(response.getState()).isEqualTo("ISSUED");
        assertThat(response.getUpdatedAt()).isEqualTo(issuedAt.getEpochSecond());
    }

    // updated_at never goes backwards: a quote row stamped after its ledger row (the normal
    // ISSUING -> ISSUED close happens just after the ledger insert) keeps the later row time.
    @Test
    void regularRoute_updatedAtNeverFallsBehindTheQuoteRow() throws CashuErrorException {
        installIssuing(regularQuote(64L, LifecycleState.ISSUED), UPDATED.minusSeconds(1));

        PostMintQuoteResponse response = status("qid", Kind.REGULAR, service(gateway("qid", true)));

        assertThat(response.getUpdatedAt()).isEqualTo(UPDATED.getEpochSecond());
    }

    // Without the ledger row an ISSUING quote has issued nothing the mint has recorded, so it
    // stays PAID.
    @Test
    void regularRoute_issuingWithoutLedgerRowReportsPaid() throws CashuErrorException {
        MintQuote issuing = regularQuote(64L, LifecycleState.ISSUING);
        MintQuoteRepository repo = Mockito.mock(MintQuoteRepository.class);
        when(repo.findById("qid")).thenReturn(Optional.of(issuing));
        IssuanceRecordRepository issuance = Mockito.mock(IssuanceRecordRepository.class);
        when(issuance.findById("qid")).thenReturn(Optional.empty());
        MintIntegrityContext.install(repo, issuance, null);

        PostMintQuoteResponse response = status("qid", Kind.REGULAR, service(gateway("qid", true)));

        assertThat(response.getState()).isEqualTo("PAID");
        assertThat(response.getAmountIssued()).isZero();
    }

    // #494: expiry is the absolute Unix timestamp NUT-04 requires, counted from the gateway's
    // record of the quote's creation, not the gateway's relative TTL that a wallet reads as a moment in 1970.
    @Test
    void status_expiryIsAbsoluteFromCreation() throws CashuErrorException {
        installRegular("qid", regularQuote(64L, LifecycleState.UNPAID));

        PostMintQuoteResponse response = status("qid", Kind.REGULAR, service(gateway("qid", false)));

        assertThat(response.getExpiry()).isEqualTo((int) (CREATED.getEpochSecond() + 60));
    }

    // A bad/legacy row with an out-of-int-range amount must clamp, never overflow into a
    // negative wire value.
    @Test
    void status_amountOverflow_clampsToIntMax() throws CashuErrorException {
        installRegular("qid", regularQuote((long) Integer.MAX_VALUE + 5L, LifecycleState.UNPAID));

        PostMintQuoteResponse response = status("qid", Kind.REGULAR, service(gateway("qid", false)));

        assertThat(response.getAmount()).isEqualTo(Integer.MAX_VALUE);
        assertThat(response.getState()).isEqualTo("UNPAID");
    }
}
