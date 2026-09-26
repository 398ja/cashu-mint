package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintRequest;
import xyz.tcheeric.cashu.mint.proto.domain.SignatureSource;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #491: an output the mint has already signed must be refused before a paid quote is
 * consumed.
 *
 * <p>The signature vault refuses a second signature on the same blinded message. If that
 * refusal were the first place the mint noticed, the quote would already be in
 * {@code ISSUING} with nothing issued, stranding the customer's payment. So the mint checks
 * the vault while the quote is still {@code PAID}, and leaves it there.
 */
class MintTaskRefusesAlreadySignedOutputsTest {

    private static final String KEYSET_ID = "004cf8cba2f93266";
    private static final String QUOTE_ID = "q-already-signed";
    private static final String BLINDED_MESSAGE =
            "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2";

    private MintQuoteRepository mintQuoteRepository;
    private SignatureVaultService signatureVault;
    private MintProtocolService service;

    @BeforeEach
    void setUp() {
        mintQuoteRepository = Mockito.mock(MintQuoteRepository.class);
        when(mintQuoteRepository.findById(QUOTE_ID))
                .thenReturn(Optional.of(paidQuote()));

        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.checkPaymentStatus(anyString())).thenReturn(true);
        when(gateway.getAmount(anyString())).thenReturn(8);
        service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.BOLT11)).thenReturn(gateway);

        signatureVault = new DefaultSignatureVaultService();
    }

    @AfterEach
    void tearDown() {
        VoucherQuoteRegistry.clear();
    }

    /**
     * A paid quote presented with an output the mint signed for someone else is refused with
     * outputs_already_signed, and the quote is never moved out of PAID.
     */
    @Test
    void anAlreadySignedOutputIsRefusedWithoutConsumingThePaidQuote() throws CashuErrorException {
        BlindedMessage output = output();
        signatureVault.store(output, previouslyIssuedSignature(), SignatureSource.SWAP);

        assertThatThrownBy(() -> mintTask(output).execute())
                .isInstanceOf(CashuErrorException.class)
                .extracting(e -> ((CashuErrorException) e).getErrorCode())
                .isEqualTo(CashuErrorCode.outputs_already_signed);

        verify(mintQuoteRepository, never())
                .casLifecycle(eq(QUOTE_ID), eq(LifecycleState.PAID), eq(LifecycleState.ISSUING));
    }

    private MintTask<Secret> mintTask(BlindedMessage output) {
        PostMintRequest<Secret> request = new PostMintRequest<>();
        request.setQuoteId(QUOTE_ID);
        request.setBlindedMessages(List.of(output));
        return new MintTask<>(request, PaymentMethod.BOLT11, null, mintWithKeys(), service,
                signatureVault, null, mintQuoteRepository, Mockito.mock(IssuanceRecordRepository.class));
    }

    private static BlindedMessage output() {
        return new BlindedMessage(8, KeysetId.fromString(KEYSET_ID),
                PublicKey.fromString(BLINDED_MESSAGE), null);
    }

    private static BlindSignature previouslyIssuedSignature() {
        return new BlindSignature(8, KeysetId.fromString(KEYSET_ID),
                SignatureTestData.sampleSignature(), null);
    }

    private static MintQuote paidQuote() {
        return new PaidQuote(QUOTE_ID, 8L, "sat", "https://mint.example", "bolt11", null,
                LifecycleState.PAID, "0".repeat(64));
    }

    private static Mint mintWithKeys() {
        Mint mint = new Mint();
        Keys keys = new Keys();
        keys.put(BigInteger.valueOf(8), PrivateKey.derivePublicKey(
                PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000008")));
        mint.addKeySet(KeySet.builder().id(KEYSET_ID).unit("sat").keys(keys).build());
        return mint;
    }

    private record PaidQuote(String quoteId, long amount, String unit, String mintUrl,
                             String paymentMethod, String invoiceId, LifecycleState lifecycleState,
                             String requestHash) implements MintQuote {
        @Override
        public Instant createdAt() {
            return null;
        }

        @Override
        public Instant updatedAt() {
            return null;
        }
    }
}
