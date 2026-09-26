package xyz.tcheeric.cashu.mint.rest.spec001;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import xyz.tcheeric.cashu.common.nut17.SubscriptionKind;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.mint.jpa.entity.MintQuoteEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.VoucherQuoteJpaRepository;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.rest.service.SubscriptionManager;
import xyz.tcheeric.cashu.mint.rest.spec003.support.VoucherTestSupport;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * cashu-mint#500 against a real PostgreSQL: a NUT-17 {@code bolt11_mint_quote} subscription is
 * answered from the same durable quote rows as {@code GET /v1/mint/quote/bolt11/{id}}.
 */
class Nut17MintQuoteStateIT extends AbstractMintDurableIT {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    SubscriptionManager subscriptionManager;

    @Autowired
    VoucherQuoteJpaRepository voucherQuotes;

    private MintProtocolService originalProtocolService;
    private WebSocketSession session;

    @BeforeEach
    void stubGatewayAndSession() {
        originalProtocolService = MintProtocolServiceFactory.getInstance();
        Gateway gateway = Mockito.mock(Gateway.class);
        Mockito.when(gateway.checkPaymentStatus(Mockito.anyString())).thenReturn(true);
        Mockito.when(gateway.getRequest(Mockito.anyString())).thenReturn("lnbc640n1test");
        Mockito.when(gateway.getPaymentExpiry(Mockito.anyString())).thenReturn(600);
        MintProtocolService stub = Mockito.spy(originalProtocolService);
        Mockito.doReturn(gateway).when(stub).createGateway(Mockito.any(PaymentMethod.class));
        Mockito.doReturn(gateway).when(stub).createGateway(Mockito.any(PaymentMethod.class), Mockito.anyString());
        MintProtocolServiceFactory.setInstance(stub);

        session = Mockito.mock(WebSocketSession.class);
        Mockito.when(session.getId()).thenReturn("it-session-" + UUID.randomUUID());
        Mockito.when(session.isOpen()).thenReturn(true);
    }

    @AfterEach
    void restore() {
        MintProtocolServiceFactory.setInstance(originalProtocolService);
        subscriptionManager.removeSession(session.getId());
    }

    // The leak: a voucher quote subscribed to over bolt11_mint_quote used to be answered PAID from
    // the gateway. It is now answered exactly like the HTTP route: not at all.
    @Test
    void aVoucherQuoteIsNotAnsweredOverBolt11() throws IOException {
        String quoteId = VoucherTestSupport.newQuoteId("v500");
        voucherQuotes.saveAndFlush(VoucherTestSupport.unfundedQuote(quoteId, 1000L));

        subscribeAndSendCurrentState(quoteId);

        verify(session, never()).sendMessage(any());
    }

    // An issued regular quote is answered ISSUED with the NUT-04 accounting fields, from its row.
    @Test
    void anIssuedQuoteIsAnsweredWithItsLifecycleAndAccounting() throws IOException {
        String quoteId = seedRegularQuote(LifecycleState.ISSUED);

        subscribeAndSendCurrentState(quoteId);

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(sent.capture());
        JsonNode payload = objectMapper.readTree(sent.getValue().getPayload()).path("params").path("payload");
        assertThat(payload.path("quote").asText()).isEqualTo(quoteId);
        assertThat(payload.path("state").asText()).isEqualTo("ISSUED");
        assertThat(payload.path("amount_paid").asLong()).isEqualTo(64L);
        assertThat(payload.path("amount_issued").asLong()).isEqualTo(64L);
        assertThat(payload.path("updated_at").asLong()).isPositive();
    }

    private void subscribeAndSendCurrentState(String quoteId) {
        String subId = subscriptionManager.subscribe(session, SubscriptionKind.bolt11_mint_quote, List.of(quoteId));
        subscriptionManager.sendCurrentState(session, subId, SubscriptionKind.bolt11_mint_quote);
    }

    private String seedRegularQuote(LifecycleState state) {
        String quoteId = UUID.randomUUID().toString();
        MintQuoteEntity quote = new MintQuoteEntity();
        quote.setQuoteId(quoteId);
        quote.setAmount(64L);
        quote.setUnit("sat");
        quote.setMintUrl("https://mint.it.example");
        quote.setPaymentMethod("bolt11");
        quote.setInvoiceId(quoteId);
        quote.setLifecycleState(state);
        quote.setRequestHash("0".repeat(64));
        mintQuoteJpaRepository.saveAndFlush(quote);
        return quoteId;
    }
}
