package xyz.tcheeric.cashu.mint.webhook;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteResponse;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintRequest;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintResponse;
import xyz.tcheeric.cashu.mint.proto.ports.MintIntegrityContext;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultSignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.tasks.MintQuoteTask;
import xyz.tcheeric.cashu.mint.proto.tasks.MintTask;
import xyz.tcheeric.cashu.mint.proto.tasks.VoucherMintQuoteTask;
import xyz.tcheeric.cashu.mint.proto.util.QuoteRef;
import xyz.tcheeric.cashu.mint.proto.util.VoucherQuoteRegistry;
import xyz.tcheeric.cashu.mint.webhook.PaymentWebhookController.WebhookResponse;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.slf4j.Logger.ROOT_LOGGER_NAME;

/**
 * cashu-mint#531: a quote id is a bearer claim on a paid, unlocked quote, so it must never reach a
 * log. This drives a regular quote and a voucher quote through the mint's real code paths, from
 * creation through the signed payment webhook to issuance, with every logger captured at TRACE,
 * and fails if a raw quote id appears anywhere in the output, including MDC values and the
 * messages of logged exceptions. Each quote's {@link QuoteRef} must appear instead, so the lines
 * still correlate.
 */
class QuoteIdNeverLoggedFlowTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEYSET_ID = "009a1f293253e41e";
    private static final String PROVIDER = "phoenixd";
    private static final long REGULAR_AMOUNT = 8L;
    private static final int VOUCHER_FACE_VALUE = 8;
    private static final String FIRST_OUTPUT = "035015e6d7ade60ba8426cefaf1832bbd27257636e44a76b922d78e79b47cb689d";
    private static final String OTHER_OUTPUT = "02c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5";

    private final InMemoryQuoteStores stores = new InMemoryQuoteStores();
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private Logger root;
    private Level previousLevel;
    private Gateway gateway;
    private MintProtocolService service;
    private QuoteStatusUpdater updater;
    private PaymentWebhookController webhook;

    @BeforeEach
    void wireTheMintAndCaptureEveryLogger() throws CashuErrorException {
        root = (Logger) LoggerFactory.getLogger(ROOT_LOGGER_NAME);
        previousLevel = root.getLevel();
        root.setLevel(Level.TRACE);
        captured.start();
        root.addAppender(captured);

        gateway = Mockito.mock(Gateway.class);
        when(gateway.createMintQuote(anyString(), anyInt(), isNull())).thenAnswer(call -> call.getArgument(0));
        when(gateway.getRequest(anyString())).thenReturn("lnbc80n1test");
        when(gateway.getPaymentExpiry(anyString())).thenReturn(600);
        when(gateway.getCreatedAt(anyString())).thenReturn(Instant.now());
        when(gateway.getAmount(anyString())).thenReturn((int) REGULAR_AMOUNT);
        when(gateway.checkPaymentStatus(anyString())).thenReturn(true);
        service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(any(PaymentMethod.class))).thenReturn(gateway);
        when(service.createGateway(any(PaymentMethod.class), any())).thenReturn(gateway);
        when(service.getPrivateKeyForSigning(anyString(), anyInt(), any())).thenReturn(signingKey());
        when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(signingKey());

        MintIntegrityContext.install(stores.mintQuotes, stores.issuances, "https://mint.example");
        MintIntegrityContext.installVoucher(stores.voucherQuotes, stores.fundings, stores.voucherIssuances,
                stores.fundingResolver, "DENY", "test");

        WebhookProperties properties = new WebhookProperties();
        properties.setProvider(PROVIDER);
        updater = new QuoteStatusUpdater(Duration.ofHours(1), Duration.ofHours(24),
                10_485_760L, 100_000, null, stores.mintQuotes, stores.voucherQuotes, stores.webhookEvents,
                stores.fundingResolver, properties);
        WebhookSignatureValidator acceptsAnySignature = Mockito.mock(WebhookSignatureValidator.class);
        when(acceptsAnySignature.validate(any(), any(), any())).thenReturn(true);
        webhook = new PaymentWebhookController(updater, acceptsAnySignature);
    }

    @AfterEach
    void restoreLogging() {
        root.detachAppender(captured);
        root.setLevel(previousLevel);
        MintIntegrityContext.clear();
        VoucherQuoteRegistry.clear();
    }

    // A regular NUT-04 quote, the case #531 is about (an unlocked paid quote), goes from quote to
    // paid webhook to issued tokens, and no log line, MDC value or logged exception names its id.
    @Test
    void aRegularQuoteIsCreatedPaidAndIssuedWithoutItsIdInAnyLog() throws Exception {
        // Arrange
        String quoteId = new MintQuoteTask(REGULAR_AMOUNT, PaymentMethod.BOLT11, "sat", service,
                stores.mintQuotes, "https://mint.example").execute().getQuoteId();

        // Act
        ResponseEntity<WebhookResponse> paid = deliverPayment(quoteId, REGULAR_AMOUNT, "preimage-regular");
        PostMintResponse issued = mint(quoteId);

        // Assert
        assertThat(paid.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(issued.getBlindSignatures()).hasSize(1);
        assertNoLogNames(quoteId);
    }

    // A voucher quote goes the same way: created, its fee paid by webhook (which attaches the
    // funding), then issued; none of the voucher loggers named in #531 may print its id.
    @Test
    void aVoucherQuoteIsCreatedPaidAndIssuedWithoutItsIdInAnyLog() throws Exception {
        // Arrange
        PostMintQuoteResponse quote =
                new VoucherMintQuoteTask(VOUCHER_FACE_VALUE, PaymentMethod.BOLT11, "sat", service).execute();
        String quoteId = quote.getQuoteId();
        long charged = stores.voucherQuotes.findById(quoteId).orElseThrow().chargedAmount();

        // Act
        ResponseEntity<WebhookResponse> paid = deliverPayment(quoteId, charged, "preimage-voucher");
        PostMintResponse issued = mint(quoteId);

        // Assert
        assertThat(paid.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(issued.getBlindSignatures()).hasSize(1);
        assertNoLogNames(quoteId);
    }

    // The failure paths log too, often with the exception: a webhook whose processing throws, an
    // underpaying webhook, an idempotent replay and a second mint with other outputs against an
    // issued quote must not leak the id through the message or the throwable.
    @Test
    void failurePathsDoNotLogTheIdEither() throws Exception {
        // Arrange
        String quoteId = new MintQuoteTask(REGULAR_AMOUNT, PaymentMethod.BOLT11, "sat", service,
                stores.mintQuotes, "https://mint.example").execute().getQuoteId();
        deliverPayment(quoteId, REGULAR_AMOUNT, "preimage-failure");
        mint(quoteId, FIRST_OUTPUT);
        QuoteStatusUpdater broken = Mockito.mock(QuoteStatusUpdater.class);
        when(broken.record(any())).thenThrow(new IllegalStateException("no row for quote " + quoteId));
        WebhookSignatureValidator acceptsAnySignature = Mockito.mock(WebhookSignatureValidator.class);
        when(acceptsAnySignature.validate(any(), any(), any())).thenReturn(true);

        // Act
        ResponseEntity<WebhookResponse> failed = new PaymentWebhookController(broken, acceptsAnySignature)
                .handlePaymentWebhook("sig", null, "bolt11:" + quoteId, body(quoteId, REGULAR_AMOUNT, "preimage-x"));
        deliverPayment(quoteId, REGULAR_AMOUNT + 1, "preimage-mismatch");
        PostMintResponse replayed = mint(quoteId, FIRST_OUTPUT);
        Throwable secondMint = catchThrowable(() -> mint(quoteId, OTHER_OUTPUT));

        // Assert
        assertThat(failed.getStatusCode().is5xxServerError()).isTrue();
        assertThat(replayed.getBlindSignatures()).hasSize(1);
        assertThat(secondMint).isNotNull();
        assertNoLogNames(quoteId);
    }

    private ResponseEntity<WebhookResponse> deliverPayment(String quoteId, long amount, String preimage)
            throws Exception {
        return webhook.handlePaymentWebhook("sig", null, "bolt11:" + quoteId, body(quoteId, amount, preimage));
    }

    private static byte[] body(String quoteId, long amount, String preimage) throws Exception {
        PaymentNotification notification = PaymentNotification.builder()
                .quoteId(quoteId)
                .paymentMethod("BOLT11")
                .amount((int) amount)
                .preimage(preimage)
                .build();
        return JSON.writeValueAsString(notification).getBytes(StandardCharsets.UTF_8);
    }

    private PostMintResponse mint(String quoteId) throws Exception {
        return mint(quoteId, FIRST_OUTPUT);
    }

    private PostMintResponse mint(String quoteId, String blindedOutput) throws Exception {
        PostMintRequest<Secret> request = new PostMintRequest<>();
        request.setQuoteId(quoteId);
        request.setBlindedMessages(List.of(output(blindedOutput)));
        return new MintTask<>(request, PaymentMethod.BOLT11, "sat", durableMint(), service,
                new DefaultSignatureVaultService(), updater, stores.mintQuotes, stores.issuances).execute();
    }

    private void assertNoLogNames(String quoteId) {
        List<String> everything = new ArrayList<>();
        for (ILoggingEvent event : captured.list) {
            everything.add(event.getFormattedMessage());
            everything.add(String.valueOf(event.getMDCPropertyMap()));
            for (IThrowableProxy error = event.getThrowableProxy(); error != null; error = error.getCause()) {
                everything.add(error.getClassName() + ": " + error.getMessage());
            }
        }
        assertThat(captured.list).as("the flow logged nothing, so the check proves nothing").isNotEmpty();
        assertThat(everything).noneMatch(line -> line.contains(quoteId));
        assertThat(everything).anyMatch(line -> line.contains(QuoteRef.of(quoteId)));
    }

    private static Throwable catchThrowable(ThrowingCall call) {
        try {
            call.run();
            return null;
        } catch (Exception thrown) {
            return thrown;
        }
    }

    private static Mint durableMint() {
        Keys keys = new Keys();
        keys.put(BigInteger.valueOf(REGULAR_AMOUNT), PrivateKey.derivePublicKey(signingKey()));
        Mint mint = new Mint();
        mint.addKeySet(KeySet.builder().id(KEYSET_ID).unit("sat").keys(keys).build());
        return mint;
    }

    private static PrivateKey signingKey() {
        return PrivateKey.fromString(String.format("%064x", REGULAR_AMOUNT));
    }

    private static BlindedMessage output(String blindedOutput) {
        BlindedMessage message = new BlindedMessage();
        message.setAmount((int) REGULAR_AMOUNT);
        message.setKeySetId(KeysetId.fromString(KEYSET_ID));
        message.setBlindedMessage(PublicKey.fromString(blindedOutput));
        return message;
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }
}
