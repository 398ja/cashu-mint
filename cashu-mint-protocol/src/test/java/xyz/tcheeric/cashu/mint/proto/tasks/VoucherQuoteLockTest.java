package xyz.tcheeric.cashu.mint.proto.tasks;

import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.nut20.MintQuoteSignatureMessage;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.Schnorr;
import xyz.tcheeric.cashu.crypto.util.Utils;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteResponse;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintRequest;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintResponse;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherFundingSource;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.MintIntegrityContext;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherFunding;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherFundingRepository;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherIssuance;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherIssuanceRepository;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuote;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuoteRepository;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultSignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.util.VoucherQuoteRegistry;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NUT-20 locking for voucher mint quotes (cashu-mint#529).
 *
 * <p>A funded voucher quote used to be a bearer claim: the voucher branch of {@code MintTask}
 * never asked for a signature, so anyone who learned a quote id could mint its face value to
 * their own outputs. A voucher quote can now be locked to a key exactly as a regular quote can.
 */
class VoucherQuoteLockTest {

    private static final String KEYSET_ID = "004cf8cba2f93266";
    private static final String QUOTE_ID = "61f9b403-3464-489c-97c3-48ca468c099a";
    private static final int FACE_VALUE = 100;
    private static final long FEE = 10L;
    private static final String FUNDING_ID = "f-1";
    private static final byte[] LOCKING_PRIVATE_KEY =
            Hex.decode("0000000000000000000000000000000000000000000000000000000000000003");
    private static final byte[] ATTACKER_PRIVATE_KEY =
            Hex.decode("0000000000000000000000000000000000000000000000000000000000000007");
    private static final String LOCKING_KEY =
            "02f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9";
    private static final String SIGNING_KEY =
            "a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5";

    private final VoucherQuoteRepository voucherQuoteRepo = Mockito.mock(VoucherQuoteRepository.class);
    private final VoucherFundingRepository voucherFundingRepo = Mockito.mock(VoucherFundingRepository.class);
    private final VoucherIssuanceRepository voucherIssuanceRepo = Mockito.mock(VoucherIssuanceRepository.class);

    @BeforeEach
    void wireVoucherRepositories() {
        MintIntegrityContext.installVoucher(voucherQuoteRepo, voucherFundingRepo,
                voucherIssuanceRepo, null, "DENY", "test");
    }

    @AfterEach
    void unwire() {
        MintIntegrityContext.clear();
        VoucherQuoteRegistry.clear();
    }

    // ---------------------------------------------------------------
    // Quote creation
    // ---------------------------------------------------------------

    /** Ensures the key a wallet locks a voucher quote to is stored on the quote row. */
    @Test
    void shouldStoreTheLockingKeyOnTheVoucherQuote() throws Exception {
        // Arrange
        when(voucherQuoteRepo.save(any(VoucherQuote.class))).thenAnswer(call -> call.getArgument(0));

        // Act
        newQuote(LOCKING_KEY).execute();

        // Assert
        ArgumentCaptor<VoucherQuote> saved = ArgumentCaptor.forClass(VoucherQuote.class);
        verify(voucherQuoteRepo).save(saved.capture());
        assertThat(saved.getValue().pubkey()).isEqualTo(LOCKING_KEY);
    }

    /** Ensures the quote response echoes the locking key, as NUT-20 requires. */
    @Test
    void shouldEchoTheLockingKeyInTheQuoteResponse() throws Exception {
        // Arrange
        when(voucherQuoteRepo.save(any(VoucherQuote.class))).thenAnswer(call -> call.getArgument(0));

        // Act
        PostMintQuoteResponse response = newQuote(LOCKING_KEY).execute();

        // Assert
        assertThat(response.getPubkey()).isEqualTo(LOCKING_KEY);
    }

    /** Ensures a quote request without a key still yields an unlocked quote, as before. */
    @Test
    void shouldCreateAnUnlockedVoucherQuoteWhenNoKeyIsGiven() throws Exception {
        // Arrange
        when(voucherQuoteRepo.save(any(VoucherQuote.class))).thenAnswer(call -> call.getArgument(0));

        // Act
        PostMintQuoteResponse response = newQuote(null).execute();

        // Assert
        ArgumentCaptor<VoucherQuote> saved = ArgumentCaptor.forClass(VoucherQuote.class);
        verify(voucherQuoteRepo).save(saved.capture());
        assertThat(saved.getValue().pubkey()).isNull();
        assertThat(response.getPubkey()).isNull();
    }

    /**
     * Ensures an invalid key is refused with 20009 before anything is written or invoiced, so no
     * payable invoice exists for a quote that nobody could ever mint.
     */
    @Test
    void shouldRefuseAnInvalidKeyBeforeRecordingOrInvoicing() {
        // Arrange
        Gateway gateway = echoingGateway();

        // Act and Assert
        assertThatThrownBy(() -> newQuote("02deadbeef", gateway).execute())
                .isInstanceOf(CashuErrorException.class)
                .satisfies(thrown -> assertThat(codeOf(thrown))
                        .isEqualTo(CashuErrorCode.pubkey_required_for_mint_quote));
        verify(voucherQuoteRepo, never()).save(any(VoucherQuote.class));
        verify(gateway, never()).createMintQuote(anyString(), anyInt(), any());
    }

    // ---------------------------------------------------------------
    // Quote status
    // ---------------------------------------------------------------

    /** Ensures the voucher status route echoes the locking key, so a wallet can tell it is locked. */
    @Test
    void shouldEchoTheLockingKeyInTheVoucherQuoteStatus() throws Exception {
        // Arrange
        stubFundedQuote(LOCKING_KEY);
        Gateway gateway = echoingGateway();
        when(gateway.checkPaymentStatus(QUOTE_ID)).thenReturn(true);

        // Act
        PostMintQuoteResponse response = new MintQuoteStatusTask(QUOTE_ID, PaymentMethod.MOCK, null,
                MintQuoteStatusTask.Kind.VOUCHER, serviceFor(gateway)).execute();

        // Assert
        assertThat(response.getPubkey()).isEqualTo(LOCKING_KEY);
    }

    // ---------------------------------------------------------------
    // Minting
    // ---------------------------------------------------------------

    /**
     * Ensures a locked voucher quote refuses a mint request with no signature with 20008, and
     * consumes nothing: the quote stays FUNDED for its rightful holder.
     */
    @Test
    void shouldRefuseToMintALockedVoucherQuoteWithoutASignature() throws Exception {
        // Arrange
        stubFundedQuote(LOCKING_KEY);
        stubFunding();
        PostMintRequest<Secret> request = mintRequest();

        // Act and Assert
        assertThatThrownBy(() -> mint(request).execute())
                .isInstanceOf(CashuErrorException.class)
                .satisfies(thrown -> assertThat(codeOf(thrown))
                        .isEqualTo(CashuErrorCode.mint_signature_invalid));
        verify(voucherQuoteRepo, never()).casLifecycle(anyString(),
                eq(VoucherLifecycleState.FUNDED), eq(VoucherLifecycleState.ISSUING));
    }

    /**
     * Ensures someone who learned only the quote id cannot mint a locked voucher quote by signing
     * with their own key. This is the attack cashu-mint#529 describes.
     */
    @Test
    void shouldRefuseToMintALockedVoucherQuoteSignedByAnotherKey() throws Exception {
        // Arrange
        stubFundedQuote(LOCKING_KEY);
        stubFunding();
        PostMintRequest<Secret> request = mintRequest();
        request.setSignature(sign(ATTACKER_PRIVATE_KEY, request.getBlindedMessages()));

        // Act and Assert
        assertThatThrownBy(() -> mint(request).execute())
                .isInstanceOf(CashuErrorException.class)
                .satisfies(thrown -> assertThat(codeOf(thrown))
                        .isEqualTo(CashuErrorCode.mint_signature_invalid));
        verify(voucherQuoteRepo, never()).casLifecycle(anyString(),
                eq(VoucherLifecycleState.FUNDED), eq(VoucherLifecycleState.ISSUING));
    }

    /** Ensures the holder of the locking key can mint the locked voucher quote. */
    @Test
    void shouldMintALockedVoucherQuoteSignedByItsKey() throws Exception {
        // Arrange
        stubFundedQuote(LOCKING_KEY);
        stubFunding();
        stubIssuanceAdvance();
        PostMintRequest<Secret> request = mintRequest();
        request.setSignature(sign(LOCKING_PRIVATE_KEY, request.getBlindedMessages()));

        // Act
        PostMintResponse response = mint(request).execute();

        // Assert
        assertThat(response.getBlindSignatures()).hasSize(3);
    }

    /** Ensures an unlocked voucher quote still mints without a signature, as it always has. */
    @Test
    void shouldMintAnUnlockedVoucherQuoteWithoutASignature() throws Exception {
        // Arrange
        stubFundedQuote(null);
        stubFunding();
        stubIssuanceAdvance();

        // Act
        PostMintResponse response = mint(mintRequest()).execute();

        // Assert
        assertThat(response.getBlindSignatures()).hasSize(3);
    }

    // ---------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------

    private VoucherMintQuoteTask newQuote(String pubkey) {
        return newQuote(pubkey, echoingGateway());
    }

    private static VoucherMintQuoteTask newQuote(String pubkey, Gateway gateway) {
        return new VoucherMintQuoteTask(FACE_VALUE, PaymentMethod.MOCK, null, serviceFor(gateway), pubkey);
    }

    private static Gateway echoingGateway() {
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.createMintQuote(anyString(), anyInt(), Mockito.isNull()))
                .thenAnswer(call -> call.getArgument(0));
        when(gateway.getRequest(anyString())).thenReturn("lnbc...");
        when(gateway.getPaymentExpiry(anyString())).thenReturn(3600);
        return gateway;
    }

    private static MintProtocolService serviceFor(Gateway gateway) {
        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.MOCK)).thenReturn(gateway);
        return service;
    }

    private void stubFundedQuote(String pubkey) {
        when(voucherQuoteRepo.findById(QUOTE_ID)).thenReturn(Optional.of(
                new FundedVoucherQuote(QUOTE_ID, FACE_VALUE, FEE, FUNDING_ID, pubkey)));
    }

    private void stubFunding() {
        VoucherFunding funding = Mockito.mock(VoucherFunding.class);
        when(funding.fundingId()).thenReturn(FUNDING_ID);
        when(funding.fundingSource()).thenReturn(VoucherFundingSource.CUSTOMER_PAYMENT);
        when(funding.amount()).thenReturn(FEE);
        when(funding.unit()).thenReturn("sat");
        when(voucherFundingRepo.findById(FUNDING_ID)).thenReturn(Optional.of(funding));
    }

    private void stubIssuanceAdvance() {
        when(voucherQuoteRepo.casLifecycle(QUOTE_ID, VoucherLifecycleState.FUNDED, VoucherLifecycleState.ISSUING))
                .thenReturn(1);
        when(voucherQuoteRepo.casLifecycle(QUOTE_ID, VoucherLifecycleState.ISSUING, VoucherLifecycleState.ISSUED))
                .thenReturn(1);
        when(voucherIssuanceRepo.insertIfAbsent(any()))
                .thenAnswer(call -> call.getArgument(0, VoucherIssuance.class));
    }

    private static PostMintRequest<Secret> mintRequest() {
        Secret secret = RandomStringSecret.fromString(
                "3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518");
        byte[] blindingFactor = Utils.hexStringToBytes(
                "ea129258e052c096f08d394b40d93ba36e8074728677f0ce11efe1f3e06d2def");
        List<BlindedMessage> outputs = List.of(
                output(64, "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"),
                output(32, "03a0434d9e47f3c86235477c7b1ae6ae5d3442d49b1943c2b752a68e2a47e247c7"),
                output(4, "025f9d298d8d9e774c81ee64927a27e6e6b6e18f65447eb6a16808f92b84e44112"));
        return new PostMintRequest<>(QUOTE_ID, outputs, List.of(secret, secret), List.of(blindingFactor));
    }

    private static BlindedMessage output(int amount, String blindedPoint) {
        return new BlindedMessage(amount, KeysetId.fromString(KEYSET_ID), PublicKey.fromString(blindedPoint), null);
    }

    private static String sign(byte[] privateKey, List<BlindedMessage> outputs) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(MintQuoteSignatureMessage.forQuote(QUOTE_ID, outputs));
        return Hex.toHexString(Schnorr.sign(hash, privateKey));
    }

    private static MintTokensTask<Secret> mint(PostMintRequest<Secret> request) throws Exception {
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.getAmount(anyString())).thenReturn(FACE_VALUE);
        when(gateway.checkPaymentStatus(anyString())).thenReturn(true);

        MintProtocolService service = serviceFor(gateway);
        when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(PrivateKey.fromString(SIGNING_KEY));
        when(service.getPrivateKeyForSigning(anyString(), anyInt(), any()))
                .thenReturn(PrivateKey.fromString(SIGNING_KEY));

        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
        when(mintLoadService.load(any(UUID.class), Mockito.anyBoolean())).thenReturn(mintWithKeyset());

        return new MintTokensTask<>(UUID.randomUUID(), request, PaymentMethod.MOCK,
                mintLoadService, service, new DefaultSignatureVaultService());
    }

    private static Mint mintWithKeyset() {
        Keys keys = new Keys();
        for (int power = 0; power <= 7; power++) {
            BigInteger amount = BigInteger.ONE.shiftLeft(power);
            keys.put(amount, PrivateKey.derivePublicKey(PrivateKey.fromString(
                    String.format("%064x", amount))));
        }
        Mint mint = new Mint();
        mint.addKeySet(KeySet.builder().id(KEYSET_ID).unit("sat").keys(keys).build());
        return mint;
    }

    private static CashuErrorCode codeOf(Throwable thrown) {
        return ((CashuErrorException) thrown).getErrorCode();
    }

    private record FundedVoucherQuote(String quoteId, long faceValue, long chargedAmount, String fundingId,
                                      String pubkey) implements VoucherQuote {
        @Override public String voucherType() { return "customer_paid"; }
        @Override public long fee() { return chargedAmount; }
        @Override public String unit() { return "sat"; }
        @Override public String merchantId() { return null; }
        @Override public String customerId() { return null; }
        @Override public VoucherLifecycleState lifecycleState() { return VoucherLifecycleState.FUNDED; }
        @Override public String idempotencyKey() { return null; }
        @Override public String requestHash() { return "0".repeat(64); }
        @Override public Instant createdAt() { return Instant.now(); }
        @Override public Instant updatedAt() { return Instant.now(); }
    }
}
