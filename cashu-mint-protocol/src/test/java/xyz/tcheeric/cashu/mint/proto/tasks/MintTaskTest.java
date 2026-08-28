package xyz.tcheeric.cashu.mint.proto.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.error.ErrorResponse;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintRequest;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultSignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.util.SignatureTestData;
import xyz.tcheeric.cashu.mint.proto.util.VoucherQuoteRegistry;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for MintTask voucher mock payment behavior.
 */
public class MintTaskTest {

    private static final String VALID_KEYSET_ID = "004cf8cba2f93266";

    // Distinct valid secp256k1 public keys so each blinded message gets a unique vault storage key
    private static final String[] DISTINCT_PUBKEYS = {
            "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2",
            "03a0434d9e47f3c86235477c7b1ae6ae5d3442d49b1943c2b752a68e2a47e247c7",
            "025f9d298d8d9e774c81ee64927a27e6e6b6e18f65447eb6a16808f92b84e44112",
            "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
    };
    private final AtomicInteger pubkeyIndex = new AtomicInteger(0);

    @AfterEach
    void tearDown() {
        VoucherQuoteRegistry.clear();
        pubkeyIndex.set(0);
    }

    private Mint createMintWithKeys() {
        Mint mint = new Mint();
        Keys keys = new Keys();
        keys.put(BigInteger.valueOf(1), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000001")));
        keys.put(BigInteger.valueOf(2), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000002")));
        keys.put(BigInteger.valueOf(4), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000004")));
        keys.put(BigInteger.valueOf(8), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000008")));
        keys.put(BigInteger.valueOf(16), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000010")));
        keys.put(BigInteger.valueOf(32), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000020")));
        keys.put(BigInteger.valueOf(64), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000040")));
        keys.put(BigInteger.valueOf(128), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000080")));
        mint.addKeySet(KeySet.builder().id(VALID_KEYSET_ID).unit("sat").keys(keys).build());
        return mint;
    }

    private BlindedMessage createBlindedMessage(int amount) {
        // Use distinct public keys to avoid vault storage key collisions
        String pubkey = DISTINCT_PUBKEYS[pubkeyIndex.getAndIncrement() % DISTINCT_PUBKEYS.length];
        return new BlindedMessage(
                amount,
                KeysetId.fromString(VALID_KEYSET_ID),
                PublicKey.fromString(pubkey),
                null
        );
    }

    /**
     * P2-01: Verifies that voucher quotes skip payment verification.
     * When a quote is registered in VoucherQuoteRegistry, MintTask should NOT
     * call gateway.checkPaymentStatus() and should proceed with minting.
     */
    @Test
    public void execute_VoucherQuote_SkipsPaymentCheck() throws CashuErrorException {
        String quoteId = "voucher-quote-123";
        long faceValue = 100L;

        // Register as voucher quote
        VoucherQuoteRegistry.storeFaceValue(quoteId, faceValue);

        // Create blinded messages totaling face value: 64 + 32 + 4 = 100
        BlindedMessage bm1 = createBlindedMessage(64);
        BlindedMessage bm2 = createBlindedMessage(32);
        BlindedMessage bm3 = createBlindedMessage(4);

        PostMintRequest<Secret> request = new PostMintRequest<>();
        request.setQuoteId(quoteId);
        request.setBlindedMessages(List.of(bm1, bm2, bm3));

        Gateway mockGateway = Mockito.mock(Gateway.class);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.BOLT11)).thenReturn(mockGateway);
        when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(
                PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

        Mint mint = createMintWithKeys();
        SignatureVaultService signatureVaultService = new DefaultSignatureVaultService();

        try (MockedConstruction<SignBlindedMessageTask> signCons = Mockito.mockConstruction(
                SignBlindedMessageTask.class,
                (mock, ctx) -> when(mock.execute()).thenReturn(new BlindSignature(
                        ((BlindedMessage) ctx.arguments().get(1)).getAmount(),
                        KeysetId.fromString(VALID_KEYSET_ID),
                        SignatureTestData.sampleSignature(),
                        null)))) {

            MintTask<Secret> task = new MintTask<>(request, PaymentMethod.BOLT11, mint, service, signatureVaultService);
            PostMintResponse response = task.execute();

            // Verify gateway.checkPaymentStatus was NEVER called (mock payment for voucher)
            verify(mockGateway, never()).checkPaymentStatus(anyString());

            // Verify signatures were generated
            assertNotNull(response);
            assertEquals(3, response.getBlindSignatures().size());
        }
    }

    /**
     * P2-02: Verifies that regular quotes require actual payment verification.
     * When a quote is NOT in VoucherQuoteRegistry, MintTask must call
     * gateway.checkPaymentStatus() and reject if not paid.
     */
    @Test
    public void execute_RegularQuote_RequiresPayment() throws CashuErrorException {
        String quoteId = "regular-quote-456";

        // Do NOT register in VoucherQuoteRegistry - this is a regular quote

        // Create blinded messages: 64 + 32 + 4 = 100
        BlindedMessage bm1 = createBlindedMessage(64);
        BlindedMessage bm2 = createBlindedMessage(32);
        BlindedMessage bm3 = createBlindedMessage(4);

        PostMintRequest<Secret> request = new PostMintRequest<>();
        request.setQuoteId(quoteId);
        request.setBlindedMessages(List.of(bm1, bm2, bm3));

        Gateway mockGateway = Mockito.mock(Gateway.class);
        // Simulate unpaid invoice
        when(mockGateway.checkPaymentStatus(quoteId)).thenReturn(false);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.BOLT11)).thenReturn(mockGateway);

        Mint mint = createMintWithKeys();
        SignatureVaultService signatureVaultService = new DefaultSignatureVaultService();

        MintTask<Secret> task = new MintTask<>(request, PaymentMethod.BOLT11, mint, service, signatureVaultService);

        // Should throw error because payment not received
        CashuErrorException exception = assertThrows(CashuErrorException.class, task::execute);

        // Verify gateway.checkPaymentStatus WAS called
        verify(mockGateway).checkPaymentStatus(quoteId);

        // Verify correct error
        try {
            ErrorResponse error = new ObjectMapper().readValue(exception.getMessage(), ErrorResponse.class);
            assertEquals("mint_invoice_not_paid_error", error.code());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Verifies that regular quotes with paid invoice succeed.
     * This confirms the payment verification flow works correctly when payment is received.
     */
    @Test
    public void execute_RegularQuote_PaidInvoice_Succeeds() throws CashuErrorException {
        String quoteId = "regular-quote-789";

        // Do NOT register in VoucherQuoteRegistry - this is a regular quote

        // Create blinded messages: 64 + 32 + 4 = 100
        BlindedMessage bm1 = createBlindedMessage(64);
        BlindedMessage bm2 = createBlindedMessage(32);
        BlindedMessage bm3 = createBlindedMessage(4);

        PostMintRequest<Secret> request = new PostMintRequest<>();
        request.setQuoteId(quoteId);
        request.setBlindedMessages(List.of(bm1, bm2, bm3));

        Gateway mockGateway = Mockito.mock(Gateway.class);
        // Simulate paid invoice
        when(mockGateway.checkPaymentStatus(quoteId)).thenReturn(true);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.BOLT11)).thenReturn(mockGateway);
        when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(
                PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

        Mint mint = createMintWithKeys();
        SignatureVaultService signatureVaultService = new DefaultSignatureVaultService();

        try (MockedConstruction<SignBlindedMessageTask> signCons = Mockito.mockConstruction(
                SignBlindedMessageTask.class,
                (mock, ctx) -> when(mock.execute()).thenReturn(new BlindSignature(
                        ((BlindedMessage) ctx.arguments().get(1)).getAmount(),
                        KeysetId.fromString(VALID_KEYSET_ID),
                        SignatureTestData.sampleSignature(),
                        null)))) {

            MintTask<Secret> task = new MintTask<>(request, PaymentMethod.BOLT11, mint, service, signatureVaultService);
            PostMintResponse response = task.execute();

            // Verify gateway.checkPaymentStatus WAS called
            verify(mockGateway).checkPaymentStatus(quoteId);

            // Verify signatures were generated
            assertNotNull(response);
            assertEquals(3, response.getBlindSignatures().size());
        }
    }

    /**
     * Verifies that voucher quote amount mismatch is rejected.
     * If blinded message amounts don't match the registered face value, mint should fail.
     */
    @Test
    public void execute_VoucherQuote_AmountMismatch_Rejected() throws CashuErrorException {
        String quoteId = "voucher-quote-mismatch";
        long faceValue = 100L;

        // Register as voucher quote with face value 100
        VoucherQuoteRegistry.storeFaceValue(quoteId, faceValue);

        // Create blinded messages totaling 50 (not 100) - MISMATCH
        BlindedMessage bm1 = createBlindedMessage(32);
        BlindedMessage bm2 = createBlindedMessage(16);
        BlindedMessage bm3 = createBlindedMessage(2);

        PostMintRequest<Secret> request = new PostMintRequest<>();
        request.setQuoteId(quoteId);
        request.setBlindedMessages(List.of(bm1, bm2, bm3));

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mint mint = createMintWithKeys();
        SignatureVaultService signatureVaultService = new DefaultSignatureVaultService();

        MintTask<Secret> task = new MintTask<>(request, PaymentMethod.BOLT11, mint, service, signatureVaultService);

        // Should throw error due to amount mismatch
        CashuErrorException exception = assertThrows(CashuErrorException.class, task::execute);

        try {
            ErrorResponse error = new ObjectMapper().readValue(exception.getMessage(), ErrorResponse.class);
            assertEquals("mint_amount_mismatch", error.code());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * P4-06: Verifies that voucher quotes accept arbitrary (non-power-of-2) denomination amounts.
     * Unlike regular Cashu tokens, vouchers can use any positive amount.
     */
    @Test
    public void execute_VoucherQuote_AcceptsArbitraryDenominations() throws CashuErrorException {
        String quoteId = "voucher-arbitrary-amounts";
        long faceValue = 100L;

        // Register as voucher quote
        VoucherQuoteRegistry.storeFaceValue(quoteId, faceValue);

        // Create blinded messages with arbitrary amounts: 33 + 67 = 100 (non-power-of-2)
        BlindedMessage bm1 = createBlindedMessage(33);  // Not a power of 2
        BlindedMessage bm2 = createBlindedMessage(67);  // Not a power of 2

        PostMintRequest<Secret> request = new PostMintRequest<>();
        request.setQuoteId(quoteId);
        request.setBlindedMessages(List.of(bm1, bm2));

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mint mint = createMintWithKeys();
        SignatureVaultService signatureVaultService = new DefaultSignatureVaultService();

        try (MockedConstruction<SignBlindedMessageTask> signCons = Mockito.mockConstruction(
                SignBlindedMessageTask.class,
                (mock, ctx) -> when(mock.execute()).thenReturn(new BlindSignature(
                        ((BlindedMessage) ctx.arguments().get(1)).getAmount(),
                        KeysetId.fromString(VALID_KEYSET_ID),
                        SignatureTestData.sampleSignature(),
                        null)))) {

            MintTask<Secret> task = new MintTask<>(request, PaymentMethod.BOLT11, mint, service, signatureVaultService);
            PostMintResponse response = task.execute();

            // Verify signatures were generated for arbitrary amounts
            assertNotNull(response);
            assertEquals(2, response.getBlindSignatures().size());
            assertEquals(33, response.getBlindSignatures().get(0).getAmount());
            assertEquals(67, response.getBlindSignatures().get(1).getAmount());
        }
    }

    /**
     * P4-08: Verifies that regular quotes still enforce power-of-2 denominations.
     * Regular Cashu tokens must use standard denominations per NUT-00.
     */
    @Test
    public void execute_RegularQuote_RejectsArbitraryDenominations() throws CashuErrorException {
        String quoteId = "regular-arbitrary-amounts";

        // NOT a voucher quote - regular quote

        // Create blinded messages with arbitrary amounts: 33 + 67 = 100 (non-power-of-2)
        BlindedMessage bm1 = createBlindedMessage(33);  // Not a power of 2
        BlindedMessage bm2 = createBlindedMessage(67);  // Not a power of 2

        PostMintRequest<Secret> request = new PostMintRequest<>();
        request.setQuoteId(quoteId);
        request.setBlindedMessages(List.of(bm1, bm2));

        Gateway mockGateway = Mockito.mock(Gateway.class);
        when(mockGateway.checkPaymentStatus(quoteId)).thenReturn(true);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.BOLT11)).thenReturn(mockGateway);

        Mint mint = createMintWithKeys();
        SignatureVaultService signatureVaultService = new DefaultSignatureVaultService();

        MintTask<Secret> task = new MintTask<>(request, PaymentMethod.BOLT11, mint, service, signatureVaultService);

        // Should throw error because regular tokens require power-of-2 denominations
        CashuErrorException exception = assertThrows(CashuErrorException.class, task::execute);

        // The error could be "invalid_denominations" or similar
        assertNotNull(exception.getMessage());
    }

    // ----- Dalia Phase 9: zero-value IOU keyset issuance -----

    private static final String IOU_KEYSET_ID = "0011aa22bb33cc44";

    private Mint createMintWithIouKeyset() {
        Mint mint = createMintWithKeys(); // adds the standard "sat" keyset
        Keys iouKeys = new Keys();
        // The IOU keyset carries a single zero-value denomination (Dalia Phase 9).
        iouKeys.put(BigInteger.ZERO, PrivateKey.derivePublicKey(PrivateKey.fromString(
                "0000000000000000000000000000000000000000000000000000000000000003")));
        mint.addKeySet(KeySet.builder().id(IOU_KEYSET_ID).unit("iou").keys(iouKeys).build());
        return mint;
    }

    private BlindedMessage createIouBlindedMessage(int amount) {
        String pubkey = DISTINCT_PUBKEYS[pubkeyIndex.getAndIncrement() % DISTINCT_PUBKEYS.length];
        return new BlindedMessage(amount, KeysetId.fromString(IOU_KEYSET_ID), PublicKey.fromString(pubkey), null);
    }

    /**
     * Dalia Phase 9: a zero-value IOU issuance mints an amount==0 marker and skips the Lightning
     * payment check entirely (like a voucher quote); the mint signs the blinded point only.
     */
    @Test
    public void execute_IouKeyset_ZeroAmount_SkipsPaymentAndMints() throws CashuErrorException {
        BlindedMessage iou = createIouBlindedMessage(0);
        PostMintRequest<Secret> request = new PostMintRequest<>();
        request.setQuoteId("iou-quote-1");
        request.setBlindedMessages(List.of(iou));

        Gateway mockGateway = Mockito.mock(Gateway.class);
        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.BOLT11)).thenReturn(mockGateway);

        Mint mint = createMintWithIouKeyset();
        SignatureVaultService signatureVaultService = new DefaultSignatureVaultService();

        try (MockedConstruction<SignBlindedMessageTask> signCons = Mockito.mockConstruction(
                SignBlindedMessageTask.class,
                (mock, ctx) -> when(mock.execute()).thenReturn(new BlindSignature(
                        ((BlindedMessage) ctx.arguments().get(1)).getAmount(),
                        KeysetId.fromString(IOU_KEYSET_ID),
                        SignatureTestData.sampleSignature(),
                        null)))) {

            MintTask<Secret> task = new MintTask<>(request, PaymentMethod.BOLT11, mint, service, signatureVaultService);
            PostMintResponse response = task.execute();

            // Zero-value IOU issuance never checks Lightning payment.
            verify(mockGateway, never()).checkPaymentStatus(anyString());
            assertNotNull(response);
            assertEquals(1, response.getBlindSignatures().size());
            assertEquals(0, response.getBlindSignatures().get(0).getAmount());
        }
    }

    /**
     * Dalia Phase 9: the value ("sat") keyset must still reject amount==0 — zero-value is IOU-only.
     */
    @Test
    public void execute_SatKeyset_ZeroAmount_Rejected() {
        BlindedMessage zero = createBlindedMessage(0); // sat keyset
        PostMintRequest<Secret> request = new PostMintRequest<>();
        request.setQuoteId("regular-zero");
        request.setBlindedMessages(List.of(zero));

        Gateway mockGateway = Mockito.mock(Gateway.class);
        when(mockGateway.checkPaymentStatus("regular-zero")).thenReturn(true);
        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.BOLT11)).thenReturn(mockGateway);

        Mint mint = createMintWithKeys();
        SignatureVaultService signatureVaultService = new DefaultSignatureVaultService();
        MintTask<Secret> task = new MintTask<>(request, PaymentMethod.BOLT11, mint, service, signatureVaultService);

        CashuErrorException ex = assertThrows(CashuErrorException.class, task::execute);
        try {
            ErrorResponse error = new ObjectMapper().readValue(ex.getMessage(), ErrorResponse.class);
            assertEquals("invalid_output_amount", error.code());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
