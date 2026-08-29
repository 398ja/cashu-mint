package xyz.tcheeric.cashu.mint.proto.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.util.Utils;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintRequest;
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
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.math.BigInteger;
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
 * Spec 006 — fail-closed value-backing invariant for voucher issuance.
 *
 * <p>Drives {@code MintTask}'s voucher branch (via {@code MintTokensTask},
 * the same harness {@link MintTest#mockMint()} uses) with mocked voucher
 * repositories installed into {@link MintIntegrityContext}. Asserts that a
 * voucher is signed only when the attached funding covers the FACE VALUE,
 * and that fee-only / under-funded / wrong-unit / policy-denied IOU funding
 * is refused with the correct typed error <b>before</b> any promise is
 * signed and before the {@code FUNDED → ISSUING} CAS.
 */
class VoucherFaceValueBackingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String VALID_KEYSET_ID = "004cf8cba2f93266";
    private static final String QUOTE_ID = "61f9b403-3464-489c-97c3-48ca468c099a";
    private static final long FACE_VALUE = 100L; // 64 + 32 + 4
    private static final long FEE = 2L;
    private static final String FUNDING_ID = "f-1";

    private final VoucherQuoteRepository voucherQuoteRepo = Mockito.mock(VoucherQuoteRepository.class);
    private final VoucherFundingRepository voucherFundingRepo = Mockito.mock(VoucherFundingRepository.class);
    private final VoucherIssuanceRepository voucherIssuanceRepo = Mockito.mock(VoucherIssuanceRepository.class);

    @AfterEach
    void tearDown() {
        MintIntegrityContext.clear();
    }

    // ---------------------------------------------------------------
    // Reject cases — issuance MUST fail before signing
    // ---------------------------------------------------------------

    @Test
    void customer_payment_fee_only_is_not_backing_face_value() throws Exception {
        installVoucher("DENY");
        stubFundedQuote();
        stubFunding(VoucherFundingSource.CUSTOMER_PAYMENT, FEE, "sat");

        assertThatThrownBy(() -> task().execute())
                .isInstanceOf(CashuErrorException.class)
                .matches(ex -> code((CashuErrorException) ex).equals("face_value_not_backed"));

        // Bailed BEFORE the FUNDED → ISSUING CAS — no consumption, no signing.
        verify(voucherQuoteRepo, never()).casLifecycle(anyString(),
                eq(VoucherLifecycleState.FUNDED), eq(VoucherLifecycleState.ISSUING));
    }

    @Test
    void merchant_debit_below_face_value_is_rejected() throws Exception {
        installVoucher("DENY");
        stubFundedQuote();
        stubFunding(VoucherFundingSource.MERCHANT_DEBIT, FACE_VALUE - 1, "sat");

        assertThatThrownBy(() -> task().execute())
                .isInstanceOf(CashuErrorException.class)
                .matches(ex -> code((CashuErrorException) ex).equals("face_value_not_backed"));
        verify(voucherQuoteRepo, never()).casLifecycle(anyString(),
                eq(VoucherLifecycleState.FUNDED), eq(VoucherLifecycleState.ISSUING));
    }

    @Test
    void merchant_debit_wrong_unit_is_rejected() throws Exception {
        installVoucher("DENY");
        stubFundedQuote();
        stubFunding(VoucherFundingSource.MERCHANT_DEBIT, FACE_VALUE, "eur");

        assertThatThrownBy(() -> task().execute())
                .isInstanceOf(CashuErrorException.class)
                .matches(ex -> code((CashuErrorException) ex).equals("face_value_not_backed"));
    }

    @Test
    void merchant_iou_is_rejected_when_policy_is_deny() throws Exception {
        installVoucher("DENY");
        stubFundedQuote();
        stubFunding(VoucherFundingSource.MERCHANT_IOU, FACE_VALUE, "sat");

        assertThatThrownBy(() -> task().execute())
                .isInstanceOf(CashuErrorException.class)
                .matches(ex -> code((CashuErrorException) ex).equals("iou_not_permitted"));
        verify(voucherQuoteRepo, never()).casLifecycle(anyString(),
                eq(VoucherLifecycleState.FUNDED), eq(VoucherLifecycleState.ISSUING));
    }

    // ---------------------------------------------------------------
    // Accept cases — issuance proceeds and signs the face value
    // ---------------------------------------------------------------

    @Test
    void merchant_debit_covering_face_value_issues() throws Exception {
        installVoucher("DENY");
        stubFundedQuote();
        stubFunding(VoucherFundingSource.MERCHANT_DEBIT, FACE_VALUE, "sat");
        stubIssuanceAdvance();

        var response = task().execute();

        assertThat(response.getBlindSignatures()).hasSize(3);
        verify(voucherQuoteRepo).casLifecycle(QUOTE_ID,
                VoucherLifecycleState.FUNDED, VoucherLifecycleState.ISSUING);
    }

    @Test
    void merchant_iou_covering_face_value_issues_when_policy_allows() throws Exception {
        installVoucher("ALLOW");
        stubFundedQuote();
        stubFunding(VoucherFundingSource.MERCHANT_IOU, FACE_VALUE, "sat");
        stubIssuanceAdvance();

        var response = task().execute();

        assertThat(response.getBlindSignatures()).hasSize(3);
        verify(voucherQuoteRepo).casLifecycle(QUOTE_ID,
                VoucherLifecycleState.FUNDED, VoucherLifecycleState.ISSUING);
    }

    // ---------------------------------------------------------------
    // Spec 007 — output validation before the FUNDED → ISSUING CAS
    // ---------------------------------------------------------------

    @Test
    void output_sum_mismatch_is_rejected_before_issuing_cas() throws Exception {
        // Funding fully backs the face value (spec-006 gate passes), but the
        // blinded outputs (sum = 100 from task()) do NOT sum to the quote's
        // face value (200). The mint MUST reject with mint_amount_mismatch
        // BEFORE consuming the quote FUNDED → ISSUING — otherwise a malformed
        // output set strands the quote in ISSUING (spec 007).
        installVoucher("DENY");
        when(voucherQuoteRepo.findById(QUOTE_ID))
                .thenReturn(Optional.of(new VoucherQuoteStub(QUOTE_ID, /*face*/ 200L, FEE, "sat", FUNDING_ID,
                        VoucherLifecycleState.FUNDED)));
        stubFunding(VoucherFundingSource.MERCHANT_DEBIT, 200L, "sat");

        assertThatThrownBy(() -> task().execute())
                .isInstanceOf(CashuErrorException.class)
                .matches(ex -> code((CashuErrorException) ex).equals("mint_amount_mismatch"));

        // The quote was NEVER consumed into ISSUING.
        verify(voucherQuoteRepo, never()).casLifecycle(anyString(),
                eq(VoucherLifecycleState.FUNDED), eq(VoucherLifecycleState.ISSUING));
    }

    // ---------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------

    private void installVoucher(String iouPolicy) {
        MintIntegrityContext.installVoucher(voucherQuoteRepo, voucherFundingRepo,
                voucherIssuanceRepo, /*resolver*/ null, iouPolicy, "test");
    }

    private void stubFundedQuote() {
        when(voucherQuoteRepo.findById(QUOTE_ID))
                .thenReturn(Optional.of(new VoucherQuoteStub(QUOTE_ID, FACE_VALUE, FEE, "sat", FUNDING_ID,
                        VoucherLifecycleState.FUNDED)));
    }

    private void stubFunding(VoucherFundingSource source, long amount, String unit) {
        VoucherFunding funding = Mockito.mock(VoucherFunding.class);
        when(funding.fundingId()).thenReturn(FUNDING_ID);
        when(funding.fundingSource()).thenReturn(source);
        when(funding.amount()).thenReturn(amount);
        when(funding.unit()).thenReturn(unit);
        when(voucherFundingRepo.findById(FUNDING_ID)).thenReturn(Optional.of(funding));
    }

    private void stubIssuanceAdvance() {
        when(voucherQuoteRepo.casLifecycle(QUOTE_ID, VoucherLifecycleState.FUNDED, VoucherLifecycleState.ISSUING))
                .thenReturn(1);
        when(voucherQuoteRepo.casLifecycle(QUOTE_ID, VoucherLifecycleState.ISSUING, VoucherLifecycleState.ISSUED))
                .thenReturn(1);
        when(voucherIssuanceRepo.insertIfAbsent(any()))
                .thenAnswer(inv -> inv.getArgument(0, VoucherIssuance.class));
    }

    /** Builds the MintTokensTask harness — identical shape to {@link MintTest#mockMint()}. */
    private MintTokensTask<Secret> task() throws Exception {
        Secret secret = RandomStringSecret.fromString(
                "3130c5cd3c69402549fc50df36873251edbeaf7efcec7c618cd8d2955202b518");
        byte[] r = Utils.hexStringToBytes(
                "ea129258e052c096f08d394b40d93ba36e8074728677f0ce11efe1f3e06d2def");
        BlindedMessage bm1 = new BlindedMessage(64, KeysetId.fromString(VALID_KEYSET_ID),
                PublicKey.fromString("02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"), null);
        BlindedMessage bm2 = new BlindedMessage(32, KeysetId.fromString(VALID_KEYSET_ID),
                PublicKey.fromString("03a0434d9e47f3c86235477c7b1ae6ae5d3442d49b1943c2b752a68e2a47e247c7"), null);
        BlindedMessage bm3 = new BlindedMessage(4, KeysetId.fromString(VALID_KEYSET_ID),
                PublicKey.fromString("025f9d298d8d9e774c81ee64927a27e6e6b6e18f65447eb6a16808f92b84e44112"), null);

        PostMintRequest postMintRequest =
                new PostMintRequest(QUOTE_ID, List.of(bm1, bm2, bm3), List.of(secret, secret), List.of(r));

        Gateway mockGateway = Mockito.mock(Gateway.class);
        when(mockGateway.getAmount(anyString())).thenReturn((int) FACE_VALUE);
        when(mockGateway.checkPaymentStatus(anyString())).thenReturn(true);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.MOCK)).thenReturn(mockGateway);
        when(service.getPrivateKey(anyString(), anyInt(), any())).thenReturn(
                PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));
        when(service.getPrivateKeyForSigning(anyString(), anyInt(), any())).thenReturn(
                PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
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
        when(mintLoadService.load(any(UUID.class), Mockito.anyBoolean())).thenReturn(mint);

        return new MintTokensTask<>(UUID.randomUUID(), postMintRequest, PaymentMethod.MOCK,
                mintLoadService, service, new DefaultSignatureVaultService());
    }

    private static String code(CashuErrorException ex) {
        return ex.getErrorCode().name();
    }

    private record VoucherQuoteStub(String quoteId, long faceValue, long chargedAmount, String unit,
                                    String fundingId, VoucherLifecycleState lifecycleState)
            implements VoucherQuote {
        @Override public String voucherType() { return "customer_paid"; }
        @Override public long fee() { return chargedAmount; }
        @Override public String merchantId() { return null; }
        @Override public String customerId() { return null; }
        @Override public String idempotencyKey() { return null; }
        @Override public String requestHash() { return "0".repeat(64); }
        @Override public Instant createdAt() { return Instant.now(); }
        @Override public Instant updatedAt() { return Instant.now(); }
    }
}
