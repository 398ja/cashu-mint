package xyz.tcheeric.cashu.mint.rest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationEventPublisher;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteRequest;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteResponse;
import xyz.tcheeric.cashu.entities.rest.nut05.PostMeltQuoteRequest;
import xyz.tcheeric.cashu.entities.rest.nut05.PostMeltQuoteResponse;
import xyz.tcheeric.cashu.mint.proto.nut.NUT04;
import xyz.tcheeric.cashu.mint.proto.nut.NUT05;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.nut.NUT06;
import xyz.tcheeric.cashu.mint.rest.event.TraceMeltQuoteRequestedEvent;
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
                new PostMintQuoteResponse("q-mint", "lnbc100n1", 100, "sat", "UNPAID", false, 1700000000);

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
                new PostMintQuoteResponse("q", "lnbc", 1, "sat", "UNPAID", false, 0);
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
}
