package xyz.tcheeric.cashu.mint.rest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import xyz.tcheeric.cashu.common.nut17.SubscriptionKind;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteResponse;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.MintIntegrityContext;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuoteRepository;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuote;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuoteRepository;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NUT-17 {@code bolt11_mint_quote} notifications answer exactly as the bolt11 status route does
 * (cashu-mint#500): no voucher quotes, lifecycle state, and the NUT-04 accounting fields.
 */
class SubscriptionManagerMintQuoteStateTest {

    private static final Instant UPDATED = Instant.parse("2026-09-26T18:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebSocketSession session = Mockito.mock(WebSocketSession.class);
    private final Gateway gateway = Mockito.mock(Gateway.class);
    private SubscriptionManager subscriptionManager;
    private MintProtocolService originalProtocolService;

    @BeforeEach
    void setUp() {
        when(session.getId()).thenReturn("session-1");
        when(session.isOpen()).thenReturn(true);
        when(gateway.getRequest(anyString())).thenReturn("lnbc640n1test");
        when(gateway.getPaymentExpiry(anyString())).thenReturn(600);
        when(gateway.checkPaymentStatus(anyString())).thenReturn(true);
        MintProtocolService protocol = Mockito.mock(MintProtocolService.class);
        when(protocol.createGateway(any(PaymentMethod.class))).thenReturn(gateway);
        when(protocol.createGateway(any(PaymentMethod.class), anyString())).thenReturn(gateway);
        originalProtocolService = MintProtocolServiceFactory.getInstance();
        MintProtocolServiceFactory.setInstance(protocol);
        subscriptionManager = new SubscriptionManager(objectMapper, Mockito.mock(ProofVaultService.class),
                protocol, "sat");
    }

    @AfterEach
    void tearDown() {
        MintIntegrityContext.clear();
        MintProtocolServiceFactory.setInstance(originalProtocolService);
    }

    private static void installQuotes(MintQuote regular, VoucherQuote voucher, String quoteId) {
        MintQuoteRepository regularRepo = Mockito.mock(MintQuoteRepository.class);
        when(regularRepo.findById(anyString())).thenReturn(Optional.empty());
        VoucherQuoteRepository voucherRepo = Mockito.mock(VoucherQuoteRepository.class);
        when(voucherRepo.findById(anyString())).thenReturn(Optional.empty());
        if (regular != null) {
            when(regularRepo.findById(quoteId)).thenReturn(Optional.of(regular));
        }
        if (voucher != null) {
            when(voucherRepo.findById(quoteId)).thenReturn(Optional.of(voucher));
        }
        MintIntegrityContext.install(regularRepo, null, "https://mint.example");
        MintIntegrityContext.installVoucher(voucherRepo, null, null, null, null, null);
    }

    private static MintQuote regularQuote(LifecycleState state) {
        return regularQuote(state, "sat");
    }

    private static MintQuote regularQuote(LifecycleState state, String unit) {
        MintQuote quote = Mockito.mock(MintQuote.class);
        when(quote.amount()).thenReturn(64L);
        when(quote.unit()).thenReturn(unit);
        when(quote.lifecycleState()).thenReturn(state);
        when(quote.updatedAt()).thenReturn(UPDATED);
        return quote;
    }

    private static VoucherQuote voucherQuote() {
        VoucherQuote quote = Mockito.mock(VoucherQuote.class);
        when(quote.faceValue()).thenReturn(1000L);
        when(quote.chargedAmount()).thenReturn(100L);
        when(quote.unit()).thenReturn("sat");
        when(quote.lifecycleState()).thenReturn(VoucherLifecycleState.ISSUED);
        return quote;
    }

    private JsonNode onlyNotificationPayload() throws IOException {
        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(sent.capture());
        return objectMapper.readTree(sent.getValue().getPayload()).path("params").path("payload");
    }

    // The leak #500 is about: subscribing to a voucher quote over bolt11_mint_quote used to get
    // "PAID" from the gateway, the same state the HTTP route stopped giving out. It now gets
    // nothing, as the HTTP route answers not found.
    @Test
    void aVoucherQuoteGetsNoBolt11Notification() throws IOException {
        installQuotes(null, voucherQuote(), "vq-1");

        String subId = subscriptionManager.subscribe(session, SubscriptionKind.bolt11_mint_quote, List.of("vq-1"));
        subscriptionManager.sendCurrentState(session, subId, SubscriptionKind.bolt11_mint_quote);

        verify(session, never()).sendMessage(any());
        verify(gateway, never()).checkPaymentStatus(anyString());
    }

    // An issued regular quote is reported ISSUED from its lifecycle, not PAID from the gateway,
    // and carries the NUT-04 accounting fields NUT-04 requires in every mint quote response.
    @Test
    void anIssuedQuoteIsReportedIssuedWithAccountingFields() throws IOException {
        installQuotes(regularQuote(LifecycleState.ISSUED), null, "q-1");

        String subId = subscriptionManager.subscribe(session, SubscriptionKind.bolt11_mint_quote, List.of("q-1"));
        subscriptionManager.sendCurrentState(session, subId, SubscriptionKind.bolt11_mint_quote);

        JsonNode payload = onlyNotificationPayload();
        assertThat(payload.path("quote").asText()).isEqualTo("q-1");
        assertThat(payload.path("state").asText()).isEqualTo("ISSUED");
        assertThat(payload.path("amount").asInt()).isEqualTo(64);
        assertThat(payload.path("amount_paid").asLong()).isEqualTo(64L);
        assertThat(payload.path("amount_issued").asLong()).isEqualTo(64L);
        assertThat(payload.path("updated_at").asLong()).isEqualTo(UPDATED.getEpochSecond());
        assertThat(payload.path("request").asText()).isEqualTo("lnbc640n1test");
    }

    // An id the mint does not know produces no notification, rather than an UNPAID state for a
    // quote that does not exist.
    @Test
    void anUnknownQuoteGetsNoNotification() throws IOException {
        installQuotes(null, null, "nope");

        String subId = subscriptionManager.subscribe(session, SubscriptionKind.bolt11_mint_quote, List.of("nope"));
        subscriptionManager.sendCurrentState(session, subId, SubscriptionKind.bolt11_mint_quote);

        verify(session, never()).sendMessage(any());
    }

    // The WebSocket answer must equal the HTTP one. The HTTP route passes no unit, so the quote's
    // own unit is reported; passing the configured default here reported a usd quote as sat.
    @Test
    void aNonDefaultUnitQuoteIsReportedInItsOwnUnit() throws IOException {
        installQuotes(regularQuote(LifecycleState.PAID, "usd"), null, "q-usd");

        String subId = subscriptionManager.subscribe(session, SubscriptionKind.bolt11_mint_quote, List.of("q-usd"));
        subscriptionManager.sendCurrentState(session, subId, SubscriptionKind.bolt11_mint_quote);

        assertThat(onlyNotificationPayload().path("unit").asText()).isEqualTo("usd");
    }

    // A state change published after a mint reaches bolt11 subscribers as the full NUT-04
    // response.
    @Test
    void aPublishedMintQuoteStateReachesSubscribersAsTheNut04Response() throws IOException {
        subscriptionManager.subscribe(session, SubscriptionKind.bolt11_mint_quote, List.of("q-2"));

        subscriptionManager.publishMintQuoteState(PostMintQuoteResponse.builder()
                .quoteId("q-2").state("ISSUED").amount(64).unit("sat")
                .amountPaid(64L).amountIssued(64L).updatedAt(UPDATED.getEpochSecond()).build());

        JsonNode payload = onlyNotificationPayload();
        assertThat(payload.path("state").asText()).isEqualTo("ISSUED");
        assertThat(payload.path("amount_issued").asLong()).isEqualTo(64L);
    }
}
