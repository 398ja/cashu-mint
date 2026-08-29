package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.mint.proto.metrics.CountingIssuanceRecorder;
import xyz.tcheeric.cashu.mint.proto.metrics.MetricRecorders;
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
import xyz.tcheeric.cashu.mint.proto.util.OutputsHash;
import xyz.tcheeric.cashu.mint.proto.util.VoucherQuoteRegistry;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec 001 T301 — drives the {@link MintTask} replay loop when a concurrent
 * writer is between {@code PAID} and {@code ISSUED}. The first
 * {@code findById} call returns {@code ISSUING}; a subsequent call (after the
 * bounded backoff) returns {@code ISSUED} with a matching
 * {@code outputs_hash}. The task MUST replay the cached signatures and
 * record an idempotent replay on the issuance recorder.
 */
class IssuingConcurrencyTest {

    private static final String VALID_KEYSET_ID = "004cf8cba2f93266";
    private static final String[] DISTINCT_PUBKEYS = {
            "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2",
            "03a0434d9e47f3c86235477c7b1ae6ae5d3442d49b1943c2b752a68e2a47e247c7"
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
    void concurrent_issuing_is_replayed_once_state_reaches_issued() throws CashuErrorException {
        String quoteId = "fr003-concurrent";
        // The concurrent writer is mid-flight: first findById sees ISSUING,
        // the next two see ISSUED. The mint task should fall through the
        // backoff loop and replay against the existing IssuanceRecord.
        when(mintQuoteRepository.findById(quoteId))
                .thenReturn(Optional.of(stub(quoteId, 10L, LifecycleState.PAID)))   // initial load
                .thenReturn(Optional.of(stub(quoteId, 10L, LifecycleState.ISSUING)))// first poll
                .thenReturn(Optional.of(stub(quoteId, 10L, LifecycleState.ISSUING)))// second poll
                .thenReturn(Optional.of(stub(quoteId, 10L, LifecycleState.ISSUED)));// after backoff
        when(mintQuoteRepository.casLifecycle(eq(quoteId), eq(LifecycleState.PAID), eq(LifecycleState.ISSUING)))
                .thenReturn(0);

        PostMintRequest<Secret> request = new PostMintRequest<>();
        request.setQuoteId(quoteId);
        List<BlindedMessage> outputs = List.of(blinded(8), blinded(2));
        request.setBlindedMessages(outputs);

        String expectedHash = OutputsHash.compute(outputs);
        String signaturesJson = "[]"; // empty list is enough for decoding to succeed; replay path returns this verbatim
        when(issuanceRecordRepository.findById(quoteId))
                .thenReturn(Optional.of(issuanceRecord(quoteId, expectedHash, signaturesJson)));

        PostMintResponse response = newMeteredTask(request).execute();
        assertThat(response.getBlindSignatures()).isNotNull();
        verify(mintQuoteRepository, atLeast(3)).findById(quoteId);
        assertThat(issuanceRecorder.count(CountingIssuanceRecorder.Event.IDEMPOTENT_REPLAY)).isEqualTo(1);
    }

    @Test
    void exhausted_polling_window_surfaces_issuance_in_progress() throws CashuErrorException {
        String quoteId = "fr003-stuck";
        // Always ISSUING — concurrent writer is stuck or the operator pinned
        // it. After the bounded retries we must surface issuance_in_progress.
        when(mintQuoteRepository.findById(quoteId))
                .thenReturn(Optional.of(stub(quoteId, 10L, LifecycleState.PAID)))
                .thenAnswer(inv -> Optional.of(stub(quoteId, 10L, LifecycleState.ISSUING)));
        when(mintQuoteRepository.casLifecycle(eq(quoteId), eq(LifecycleState.PAID), eq(LifecycleState.ISSUING)))
                .thenReturn(0);

        PostMintRequest<Secret> request = new PostMintRequest<>();
        request.setQuoteId(quoteId);
        request.setBlindedMessages(List.of(blinded(8), blinded(2)));

        CashuErrorException ex = assertThrows(CashuErrorException.class, newMeteredTask(request)::execute);
        assertThat(errorCode(ex)).isEqualTo("issuance_in_progress");
        // Counter not incremented when no replay happens.
        assertThat(issuanceRecorder.count(CountingIssuanceRecorder.Event.IDEMPOTENT_REPLAY)).isZero();
    }

    // ---------------------- helpers ----------------------

    private MintTask<Secret> newMeteredTask(PostMintRequest<Secret> request) {
        return new MintTask<>(request, PaymentMethod.BOLT11, null, mint, service,
                signatureVaultService, null, mintQuoteRepository, issuanceRecordRepository);
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
            CashuErrorCode errorCode = ex.getErrorCode();
            return errorCode.name();
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
        keys.put(BigInteger.valueOf(2), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000002")));
        keys.put(BigInteger.valueOf(8), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000008")));
        mint.addKeySet(KeySet.builder().id(VALID_KEYSET_ID).unit("sat").keys(keys).build());
        return mint;
    }
}
