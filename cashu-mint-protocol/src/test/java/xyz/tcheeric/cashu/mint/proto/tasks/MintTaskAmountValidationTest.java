package xyz.tcheeric.cashu.mint.proto.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.mint.proto.metrics.CountingIssuanceRecorder;
import xyz.tcheeric.cashu.mint.proto.metrics.MetricRecorders;
import xyz.tcheeric.cashu.common.BlindSignature;
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
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintRequest;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintResponse;
import xyz.tcheeric.cashu.mint.proto.ports.IssuanceRecord;
import xyz.tcheeric.cashu.mint.proto.ports.IssuanceRecordRepository;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuoteRepository;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultSignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.util.SignatureTestData;
import xyz.tcheeric.cashu.mint.proto.util.VoucherQuoteRegistry;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec 001 T103 — drives {@link MintTask} with a mocked
 * {@link MintQuoteRepository} to confirm:
 *
 * <ul>
 *   <li>FR-001: a request whose output sum differs from {@code quote.amount}
 *       is rejected with {@code amount_mismatch} and zero signatures are
 *       persisted.</li>
 *   <li>FR-002: the exact-amount case advances
 *       {@code PAID → ISSUING → ISSUED} via two CAS calls and inserts one
 *       {@link IssuanceRecord} row.</li>
 *   <li>FR-003: a retry against an already-issued quote returns the cached
 *       signatures when the outputs hash matches.</li>
 * </ul>
 */
class MintTaskAmountValidationTest {

    private static final String VALID_KEYSET_ID = "004cf8cba2f93266";
    private static final String[] DISTINCT_PUBKEYS = {
            "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2",
            "03a0434d9e47f3c86235477c7b1ae6ae5d3442d49b1943c2b752a68e2a47e247c7",
            "025f9d298d8d9e774c81ee64927a27e6e6b6e18f65447eb6a16808f92b84e44112",
            "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
    };
    private final AtomicInteger pubkeyIndex = new AtomicInteger(0);

    private MintQuoteRepository mintQuoteRepository;
    private IssuanceRecordRepository issuanceRecordRepository;
    private MintProtocolService service;
    private SignatureVaultService signatureVaultService;
    private Mint mint;
    private Gateway gateway;
    private CountingIssuanceRecorder issuanceRecorder;

    @BeforeEach
    void setUp() throws CashuErrorException {
        mintQuoteRepository = Mockito.mock(MintQuoteRepository.class);
        issuanceRecordRepository = Mockito.mock(IssuanceRecordRepository.class);
        gateway = Mockito.mock(Gateway.class);
        when(gateway.checkPaymentStatus(anyString())).thenReturn(true);
        // FR-010 cross-check default: gateway agrees with the test stubs (10 sats).
        when(gateway.getAmount(anyString())).thenReturn(10);

        service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.BOLT11)).thenReturn(gateway);
        when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(
                PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

        signatureVaultService = new DefaultSignatureVaultService();
        mint = createMintWithKeys();
        issuanceRecorder = new CountingIssuanceRecorder();
        MetricRecorders.registerIssuance(issuanceRecorder);
    }

    @AfterEach
    void tearDown() {
        MetricRecorders.registerIssuance(null);
        VoucherQuoteRegistry.clear();
        pubkeyIndex.set(0);
    }

    @Test
    void rejects_under_mint_with_amount_mismatch_and_no_signatures() throws CashuErrorException {
        String quoteId = "fr001-under";
        when(mintQuoteRepository.findById(quoteId)).thenReturn(Optional.of(stub(quoteId, 10L, LifecycleState.PAID)));

        PostMintRequest<Secret> request = new PostMintRequest<>();
        request.setQuoteId(quoteId);
        request.setBlindedMessages(List.of(blinded(8), blinded(1))); // sum=9 != 10

        try (MockedConstruction<SignBlindedMessageTask> ignored = signBlindedConstruction()) {
            MintTask<Secret> task = newTask(request);
            CashuErrorException ex = assertThrows(CashuErrorException.class, task::execute);
            assertThat(errorCode(ex)).isEqualTo("amount_mismatch");
        }

        verify(mintQuoteRepository, never()).casLifecycle(anyString(), any(), any());
        verify(issuanceRecordRepository, never()).insertIfAbsent(any());
    }

    @Test
    void rejects_over_mint_with_amount_mismatch_and_no_signatures() throws CashuErrorException {
        String quoteId = "fr001-over";
        when(mintQuoteRepository.findById(quoteId)).thenReturn(Optional.of(stub(quoteId, 10L, LifecycleState.PAID)));

        PostMintRequest<Secret> request = new PostMintRequest<>();
        request.setQuoteId(quoteId);
        request.setBlindedMessages(List.of(blinded(8), blinded(2), blinded(1))); // sum=11 != 10

        try (MockedConstruction<SignBlindedMessageTask> ignored = signBlindedConstruction()) {
            MintTask<Secret> task = newTask(request);
            assertThrows(CashuErrorException.class, task::execute);
        }

        verify(mintQuoteRepository, never()).casLifecycle(anyString(), any(), any());
        verify(issuanceRecordRepository, never()).insertIfAbsent(any());
    }

    @Test
    void exact_mint_transitions_PAID_to_ISSUING_to_ISSUED_and_writes_one_issuance_record() throws CashuErrorException {
        String quoteId = "fr002-exact";
        when(mintQuoteRepository.findById(quoteId)).thenReturn(Optional.of(stub(quoteId, 10L, LifecycleState.PAID)));
        when(mintQuoteRepository.casLifecycle(eq(quoteId), eq(LifecycleState.PAID), eq(LifecycleState.ISSUING)))
                .thenReturn(1);
        when(mintQuoteRepository.casLifecycle(eq(quoteId), eq(LifecycleState.ISSUING), eq(LifecycleState.ISSUED)))
                .thenReturn(1);
        when(issuanceRecordRepository.insertIfAbsent(any())).thenAnswer(inv ->
                IssuanceRecordRepository.InsertResult.newlyInserted(inv.getArgument(0)));

        PostMintRequest<Secret> request = new PostMintRequest<>();
        request.setQuoteId(quoteId);
        request.setBlindedMessages(List.of(blinded(8), blinded(2))); // sum=10

        PostMintResponse response;
        try (MockedConstruction<SignBlindedMessageTask> ignored = signBlindedConstruction()) {
            response = newTask(request).execute();
        }

        assertThat(response.getBlindSignatures()).hasSize(2);
        verify(mintQuoteRepository).casLifecycle(quoteId, LifecycleState.PAID, LifecycleState.ISSUING);
        verify(mintQuoteRepository).casLifecycle(quoteId, LifecycleState.ISSUING, LifecycleState.ISSUED);
        verify(issuanceRecordRepository, times(1)).insertIfAbsent(any());
    }

    @Test
    void replay_with_same_outputs_returns_cached_signatures() throws CashuErrorException {
        String quoteId = "fr003-replay";
        when(mintQuoteRepository.findById(quoteId))
                .thenReturn(Optional.of(stub(quoteId, 10L, LifecycleState.ISSUED)));
        when(mintQuoteRepository.casLifecycle(eq(quoteId), eq(LifecycleState.PAID), eq(LifecycleState.ISSUING)))
                .thenReturn(0);

        PostMintRequest<Secret> request = new PostMintRequest<>();
        request.setQuoteId(quoteId);
        List<BlindedMessage> outputs = List.of(blinded(8), blinded(2));
        request.setBlindedMessages(outputs);

        // Pre-compute the outputs hash the way MintTask will to set up the
        // existing IssuanceRecord stub.
        String expectedHash = xyz.tcheeric.cashu.mint.proto.util.OutputsHash.compute(outputs);
        String signaturesJson = "[{\"amount\":8,\"id\":\"" + VALID_KEYSET_ID + "\",\"C_\":\""
                + SignatureTestData.sampleSignature().toString() + "\"},"
                + "{\"amount\":2,\"id\":\"" + VALID_KEYSET_ID + "\",\"C_\":\""
                + SignatureTestData.sampleSignature().toString() + "\"}]";

        when(issuanceRecordRepository.findById(quoteId))
                .thenReturn(Optional.of(issuanceRecord(quoteId, expectedHash, signaturesJson)));

        PostMintResponse response = newTask(request).execute();
        assertThat(response.getBlindSignatures()).hasSize(2);
        verify(issuanceRecordRepository, never()).insertIfAbsent(any());
    }

    @Test
    void replay_with_different_outputs_against_issued_quote_is_rejected() throws CashuErrorException {
        String quoteId = "fr003-tamper";
        when(mintQuoteRepository.findById(quoteId))
                .thenReturn(Optional.of(stub(quoteId, 10L, LifecycleState.ISSUED)));
        when(mintQuoteRepository.casLifecycle(eq(quoteId), eq(LifecycleState.PAID), eq(LifecycleState.ISSUING)))
                .thenReturn(0);

        PostMintRequest<Secret> request = new PostMintRequest<>();
        request.setQuoteId(quoteId);
        request.setBlindedMessages(List.of(blinded(8), blinded(2))); // valid sum

        // Record exists but its outputs_hash refers to a *different* output set
        when(issuanceRecordRepository.findById(quoteId))
                .thenReturn(Optional.of(issuanceRecord(quoteId,
                        "0000000000000000000000000000000000000000000000000000000000000000",
                        "[]")));

        try (MockedConstruction<SignBlindedMessageTask> ignored = signBlindedConstruction()) {
            CashuErrorException ex = assertThrows(CashuErrorException.class, newTask(request)::execute);
            assertThat(errorCode(ex)).isEqualTo("quote_already_issued");
        }
    }

    @Test
    void cross_check_mismatch_with_gateway_fails_closed_and_increments_counter() throws CashuErrorException {
        String quoteId = "fr010-mismatch";
        when(mintQuoteRepository.findById(quoteId)).thenReturn(Optional.of(stub(quoteId, 10L, LifecycleState.PAID)));
        // Gateway disagrees with the durable record: durable = 10, gateway reports 7.
        when(gateway.getAmount(quoteId)).thenReturn(7);

        PostMintRequest<Secret> request = new PostMintRequest<>();
        request.setQuoteId(quoteId);
        request.setBlindedMessages(List.of(blinded(8), blinded(2))); // valid sum against durable 10

        try (MockedConstruction<SignBlindedMessageTask> ignored = signBlindedConstruction()) {
            CashuErrorException ex = assertThrows(CashuErrorException.class, newMeteredTask(request)::execute);
            assertThat(errorCode(ex)).isEqualTo("quote_amount_cross_check_failed");
        }

        verify(mintQuoteRepository, never()).casLifecycle(anyString(), any(), any());
        verify(issuanceRecordRepository, never()).insertIfAbsent(any());
        assertThat(issuanceRecorder.count(CountingIssuanceRecorder.Event.CROSS_CHECK_FAILURE)).isEqualTo(1);
    }

    @Test
    void cross_check_gateway_failure_fails_closed() throws CashuErrorException {
        String quoteId = "fr010-throw";
        when(mintQuoteRepository.findById(quoteId)).thenReturn(Optional.of(stub(quoteId, 10L, LifecycleState.PAID)));
        when(gateway.getAmount(quoteId)).thenThrow(new RuntimeException("upstream unavailable"));

        PostMintRequest<Secret> request = new PostMintRequest<>();
        request.setQuoteId(quoteId);
        request.setBlindedMessages(List.of(blinded(8), blinded(2)));

        try (MockedConstruction<SignBlindedMessageTask> ignored = signBlindedConstruction()) {
            CashuErrorException ex = assertThrows(CashuErrorException.class, newMeteredTask(request)::execute);
            assertThat(errorCode(ex)).isEqualTo("quote_amount_cross_check_failed");
        }

        assertThat(issuanceRecorder.count(CountingIssuanceRecorder.Event.CROSS_CHECK_FAILURE)).isEqualTo(1);
    }

    @Test
    void amount_mismatch_increments_counter() throws CashuErrorException {
        String quoteId = "fr115-counter";
        when(mintQuoteRepository.findById(quoteId)).thenReturn(Optional.of(stub(quoteId, 10L, LifecycleState.PAID)));

        PostMintRequest<Secret> request = new PostMintRequest<>();
        request.setQuoteId(quoteId);
        request.setBlindedMessages(List.of(blinded(8), blinded(1))); // sum=9 != 10

        try (MockedConstruction<SignBlindedMessageTask> ignored = signBlindedConstruction()) {
            assertThrows(CashuErrorException.class, newMeteredTask(request)::execute);
        }

        assertThat(issuanceRecorder.count(CountingIssuanceRecorder.Event.AMOUNT_MISMATCH)).isEqualTo(1);
    }

    // ---------------------- helpers ----------------------

    private MintTask<Secret> newTask(PostMintRequest<Secret> request) {
        return new MintTask<>(request, PaymentMethod.BOLT11, null, mint, service,
                signatureVaultService, null, mintQuoteRepository, issuanceRecordRepository);
    }

    private MintTask<Secret> newMeteredTask(PostMintRequest<Secret> request) {
        return new MintTask<>(request, PaymentMethod.BOLT11, null, mint, service,
                signatureVaultService, null, mintQuoteRepository, issuanceRecordRepository);
    }

    private MockedConstruction<SignBlindedMessageTask> signBlindedConstruction() {
        return Mockito.mockConstruction(SignBlindedMessageTask.class,
                (mockTask, ctx) -> when(mockTask.execute()).thenReturn(new BlindSignature(
                        ((BlindedMessage) ctx.arguments().get(1)).getAmount(),
                        KeysetId.fromString(VALID_KEYSET_ID),
                        SignatureTestData.sampleSignature(),
                        null)));
    }

    private BlindedMessage blinded(int amount) {
        String pubkey = DISTINCT_PUBKEYS[pubkeyIndex.getAndIncrement() % DISTINCT_PUBKEYS.length];
        return new BlindedMessage(amount,
                KeysetId.fromString(VALID_KEYSET_ID),
                PublicKey.fromString(pubkey),
                null);
    }

    private static String errorCode(CashuErrorException ex) {
        try {
            ErrorResponse error = new ObjectMapper().readValue(ex.getMessage(), ErrorResponse.class);
            return error.code();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static MintQuote stub(String quoteId, long amount, LifecycleState state) {
        return new MintQuoteStub(quoteId, amount, "sat", "https://mint.example", "bolt11", null,
                state, "0".repeat(64));
    }

    private static IssuanceRecord issuanceRecord(String quoteId, String outputsHash, String json) {
        return new IssuanceRecordStub(quoteId, outputsHash, json, VALID_KEYSET_ID, 10L, Instant.now());
    }

    private record MintQuoteStub(String quoteId, long amount, String unit, String mintUrl,
                                 String paymentMethod, String invoiceId, LifecycleState lifecycleState,
                                 String requestHash) implements MintQuote {
        @Override public Instant createdAt() { return null; }
        @Override public Instant updatedAt() { return null; }
    }

    private record IssuanceRecordStub(String quoteId, String outputsHash, String signaturesJson,
                                      String keysetId, long totalAmount, Instant issuedAt) implements IssuanceRecord {
    }

    private static Mint createMintWithKeys() {
        Mint mint = new Mint();
        Keys keys = new Keys();
        keys.put(BigInteger.valueOf(1), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000001")));
        keys.put(BigInteger.valueOf(2), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000002")));
        keys.put(BigInteger.valueOf(4), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000004")));
        keys.put(BigInteger.valueOf(8), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000008")));
        keys.put(BigInteger.valueOf(16), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000010")));
        mint.addKeySet(KeySet.builder().id(VALID_KEYSET_ID).unit("sat").keys(keys).build());
        return mint;
    }
}
