package xyz.tcheeric.cashu.mint.rest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.SecretUtil;
import xyz.tcheeric.cashu.mint.proto.error.ErrorResponse;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteRequest;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteResponse;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintRequest;
import xyz.tcheeric.cashu.entities.rest.nut05.PostMeltQuoteRequest;
import xyz.tcheeric.cashu.entities.rest.nut05.PostMeltQuoteResponse;
import xyz.tcheeric.cashu.entities.rest.nut05.PostMeltRequest;
import xyz.tcheeric.cashu.mint.proto.nut.NUT04;
import xyz.tcheeric.cashu.mint.proto.nut.NUT05;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.nut.NUT06;
import xyz.tcheeric.cashu.mint.rest.event.TraceMeltFailedEvent;
import xyz.tcheeric.cashu.mint.rest.event.TraceMeltQuoteRequestedEvent;
import xyz.tcheeric.cashu.mint.rest.event.TraceMintFailedEvent;
import xyz.tcheeric.cashu.mint.rest.event.TraceMintQuoteRequestedEvent;

/**
 * Spec 036 — verifies the controller publishes the quote trace events on the
 * success seams, that publishing is a safe no-op when no publisher is wired, and
 * that the failure-emission scoping predicates only match the intended codes.
 */
class CashuControllerTraceTest {

    private CashuController<Secret> controller(ApplicationEventPublisher publisher) {
        CashuController<Secret> c = new CashuController<>(
                mock(NUT06.class),
                mock(MintLoadService.class),
                mock(SignatureVaultService.class),
                null, // Nut17EventPublisher — skip the NUT-17 path
                mock(MintVaultService.class),
                mock(ProofVaultService.class));
        if (publisher != null) {
            c.setApplicationEventPublisher(publisher);
        }
        return c;
    }

    // A successful mint quote publishes one TraceMintQuoteRequestedEvent with the
    // response's quote fields.
    @Test
    void quoteMint_publishesMintQuoteRequested() throws Exception {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        PostMintQuoteResponse resp =
                PostMintQuoteResponse.builder().quoteId("q-mint").request("lnbc100n1").amount(100)
                        .unit("sat").state("UNPAID").expiry(1700000000).build();

        try (MockedStatic<NUT04> nut04 = mockStatic(NUT04.class)) {
            nut04.when(() -> NUT04.quote(anyInt(), any())).thenReturn(resp);
            controller(publisher).quoteMint(new PostMintQuoteRequest(100, "sat"), "bolt11");
        }

        ArgumentCaptor<org.springframework.context.ApplicationEvent> captor =
                ArgumentCaptor.forClass(org.springframework.context.ApplicationEvent.class);
        org.mockito.Mockito.verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(TraceMintQuoteRequestedEvent.class);
        TraceMintQuoteRequestedEvent ev = (TraceMintQuoteRequestedEvent) captor.getValue();
        assertThat(ev.getQuoteId()).isEqualTo("q-mint");
        assertThat(ev.getAmount()).isEqualTo(100L);
        assertThat(ev.getUnit()).isEqualTo("sat");
        assertThat(ev.getRequest()).isEqualTo("lnbc100n1");
    }

    // A successful melt quote publishes one TraceMeltQuoteRequestedEvent carrying
    // the fee reserve.
    @Test
    void quoteMelt_publishesMeltQuoteRequested() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        PostMeltQuoteResponse resp = new PostMeltQuoteResponse("q-melt", 200, 5, false, 1700000000);
        PostMeltQuoteRequest req = mock(PostMeltQuoteRequest.class);
        org.mockito.Mockito.when(req.getRequest()).thenReturn("lnbc200n1");

        try (MockedStatic<NUT05> nut05 = mockStatic(NUT05.class)) {
            nut05.when(() -> NUT05.quote(any(), any())).thenReturn(resp);
            controller(publisher).quoteMelt(req, "bolt11");
        }

        ArgumentCaptor<org.springframework.context.ApplicationEvent> captor =
                ArgumentCaptor.forClass(org.springframework.context.ApplicationEvent.class);
        org.mockito.Mockito.verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(TraceMeltQuoteRequestedEvent.class);
        TraceMeltQuoteRequestedEvent ev = (TraceMeltQuoteRequestedEvent) captor.getValue();
        assertThat(ev.getQuoteId()).isEqualTo("q-melt");
        assertThat(ev.getAmount()).isEqualTo(200L);
        assertThat(ev.getFeeReserve()).isEqualTo(5L);
        assertThat(ev.getRequest()).isEqualTo("lnbc200n1");
    }

    // With no ApplicationEventPublisher wired (tracing absent), a quote is created
    // without error and nothing is published.
    @Test
    void quoteMint_withoutPublisher_doesNotThrow() {
        PostMintQuoteResponse resp =
                PostMintQuoteResponse.builder().quoteId("q").request("lnbc").amount(1)
                        .unit("sat").state("UNPAID").expiry(0).build();
        try (MockedStatic<NUT04> nut04 = mockStatic(NUT04.class)) {
            nut04.when(() -> NUT04.quote(anyInt(), any())).thenReturn(resp);
            assertThatCode(() -> controller(null).quoteMint(new PostMintQuoteRequest(1, "sat"), "bolt11"))
                    .doesNotThrowAnyException();
        }
    }

    // The failure-emission predicates match only their intended error codes, so
    // validation rejects and the parked-unknown path are not traced.
    @Test
    void failurePredicates_matchOnlyIntendedCodes() {
        assertThat(CashuController.isInvoiceNotPaid("{\"code\":\"mint_invoice_not_paid_error\"}")).isTrue();
        assertThat(CashuController.isInvoiceNotPaid("{\"code\":\"invalid_output_amount\"}")).isFalse();
        assertThat(CashuController.isInvoiceNotPaid(null)).isFalse();

        assertThat(CashuController.isMeltInvoiceNotPaid("{\"code\":\"melt_invoice_not_paid_error\"}")).isTrue();
        assertThat(CashuController.isMeltInvoiceNotPaid("{\"code\":\"payment_unknown\"}")).isFalse();
        assertThat(CashuController.isMeltInvoiceNotPaid("{\"code\":\"insufficient_input\"}")).isFalse();
    }

    // ---- live mint()/melt() failure-catch wiring ----

    private static final String KEYSET_ID = "00ad268c4d1f5826";
    private static final String MINT_ID = "11111111-1111-1111-1111-111111111111";

    private CashuController<Secret> controllerWith(ApplicationEventPublisher publisher, MintLoadService loader) {
        CashuController<Secret> c = new CashuController<>(
                mock(NUT06.class), loader, mock(SignatureVaultService.class), null,
                mock(MintVaultService.class), mock(ProofVaultService.class));
        c.setApplicationEventPublisher(publisher);
        return c;
    }

    // A mint that whose keyset resolves to MINT_ID, so mint()/melt() reach the NUT call.
    private MintLoadService loaderWithKeyset() throws CashuErrorException {
        KeySet ks = new KeySet();
        ks.setId(KEYSET_ID);
        Mint mint = new Mint(MINT_ID, Set.of(ks));
        MintLoadService loader = mock(MintLoadService.class);
        when(loader.load(anyBoolean())).thenReturn(List.of(mint));
        return loader;
    }

    private static CashuErrorException error(String code) {
        return new CashuErrorException(new ErrorResponse(code).toJson());
    }

    @SuppressWarnings("unchecked")
    private PostMintRequest<Secret> mintRequest() {
        BlindedMessage bm = mock(BlindedMessage.class);
        when(bm.getKeySetId()).thenReturn(KeysetId.fromString(KEYSET_ID));
        when(bm.getAmount()).thenReturn(100);
        PostMintRequest<Secret> request = mock(PostMintRequest.class);
        when(request.getQuoteId()).thenReturn("q-mint");
        when(request.getBlindedMessages()).thenReturn(List.of(bm));
        return request;
    }

    @SuppressWarnings("unchecked")
    private PostMeltRequest<Secret> meltRequest() {
        Proof<Secret> proof = mock(Proof.class);
        when(proof.getKeySetId()).thenReturn(KEYSET_ID);
        when(proof.getAmount()).thenReturn(8);
        when(proof.getSecret()).thenReturn(mock(Secret.class));
        PostMeltRequest<Secret> request = mock(PostMeltRequest.class);
        when(request.getQuoteId()).thenReturn("q-melt");
        when(request.getInputs()).thenReturn(List.of(proof));
        return request;
    }

    // mint() catch: the unpaid-invoice error publishes MINT_FAILED (no proofs) and
    // rethrows the ORIGINAL exception unchanged.
    @Test
    void mint_invoiceNotPaid_emitsMintFailedAndRethrows() throws Exception {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        CashuController<Secret> c = controllerWith(publisher, loaderWithKeyset());
        CashuErrorException ex = error("mint_invoice_not_paid_error");

        try (MockedStatic<NUT04> nut04 = mockStatic(NUT04.class)) {
            nut04.when(() -> NUT04.mint(any(), any(), any(), any(), any(), any(), any())).thenThrow(ex);
            assertThatThrownBy(() -> c.mint(mintRequest(), "bolt11")).isSameAs(ex);
        }

        ArgumentCaptor<ApplicationEvent> captor = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(TraceMintFailedEvent.class);
        TraceMintFailedEvent ev = (TraceMintFailedEvent) captor.getValue();
        assertThat(ev.getErrorCode()).isEqualTo("mint_invoice_not_paid_error");
        assertThat(ev.getQuoteId()).isEqualTo("q-mint");
        assertThat(ev.getAmount()).isEqualTo(100L);
    }

    // mint() catch: a non-target error rethrows but publishes nothing.
    @Test
    void mint_otherError_rethrowsWithoutEmitting() throws Exception {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        CashuController<Secret> c = controllerWith(publisher, loaderWithKeyset());
        CashuErrorException ex = error("invalid_output_amount");

        try (MockedStatic<NUT04> nut04 = mockStatic(NUT04.class)) {
            nut04.when(() -> NUT04.mint(any(), any(), any(), any(), any(), any(), any())).thenThrow(ex);
            assertThatThrownBy(() -> c.mint(mintRequest(), "bolt11")).isSameAs(ex);
        }

        verify(publisher, never()).publishEvent(any());
    }

    // melt() catch: the payment-failure error publishes MELT_FAILED (released inputs)
    // and rethrows the ORIGINAL exception unchanged.
    @Test
    void melt_paymentFailure_emitsMeltFailedAndRethrows() throws Exception {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        CashuController<Secret> c = controllerWith(publisher, loaderWithKeyset());
        CashuErrorException ex = error("melt_invoice_not_paid_error");

        try (MockedStatic<NUT05> nut05 = mockStatic(NUT05.class);
             MockedStatic<SecretUtil> su = mockStatic(SecretUtil.class)) {
            su.when(() -> SecretUtil.toY(any())).thenReturn("02" + "a".repeat(64));
            nut05.when(() -> NUT05.melt(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(ex);
            assertThatThrownBy(() -> c.melt(meltRequest(), "bolt11")).isSameAs(ex);
        }

        ArgumentCaptor<ApplicationEvent> captor = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(TraceMeltFailedEvent.class);
        TraceMeltFailedEvent ev = (TraceMeltFailedEvent) captor.getValue();
        assertThat(ev.getErrorCode()).isEqualTo("melt_invoice_not_paid_error");
        assertThat(ev.getQuoteId()).isEqualTo("q-melt");
        assertThat(ev.getInputs()).hasSize(1);
        assertThat(ev.getInputs().get(0).y()).isEqualTo("02" + "a".repeat(64));
    }

    // melt() catch: a validation error rethrows but publishes nothing.
    @Test
    void melt_validationError_rethrowsWithoutEmitting() throws Exception {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        CashuController<Secret> c = controllerWith(publisher, loaderWithKeyset());
        CashuErrorException ex = error("insufficient_input");

        try (MockedStatic<NUT05> nut05 = mockStatic(NUT05.class)) {
            nut05.when(() -> NUT05.melt(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(ex);
            assertThatThrownBy(() -> c.melt(meltRequest(), "bolt11")).isSameAs(ex);
        }

        verify(publisher, never()).publishEvent(any());
    }
}
