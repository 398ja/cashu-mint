package xyz.tcheeric.cashu.mint.proto.tasks;

import xyz.tcheeric.cashu.mint.proto.voucher.VoucherDocumentContext;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.Signature;
import xyz.tcheeric.cashu.common.Witness;
import xyz.tcheeric.cashu.common.nut11.P2PKSecret;
import xyz.tcheeric.cashu.common.nut11.P2PKVoucherSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.crypto.Schnorr;
import xyz.tcheeric.cashu.crypto.util.Utils;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapResponse;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherIssuerBindingMode;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.P2PKTransaction;
import xyz.tcheeric.cashu.mint.proto.util.SignatureTestData;
import xyz.tcheeric.cashu.mint.proto.voucher.InstalledVoucherIssuerBinding;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherIssuerBinding;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherSignerTrustList;
import xyz.tcheeric.cashu.voucher.domain.SignedLockedVoucher;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * imani-wallet#295 phase 1: the mint behaviour a link-locked Cashlink and its pre-signed refund
 * rely on, pinned before anything is built on it.
 *
 * <p>The gift is a {@code P2PK_VOUCHER} locked to a one-time link key, with a {@code locktime}
 * {@code x}, one {@code refund} key belonging to the sender, and {@code sigflag = SIG_ALL}. The
 * sender pre-signs, with the refund key, a swap of each gift proof into plain outputs only the
 * sender can unblind, and hands that swap to a relay which posts it after {@code x}. Design:
 * {@code design-295-cashlink-return.md} §2 and §3.2.
 *
 * <p>Everything here runs the mint's real dispatch ({@link VerifyProofsTask}) and, for the swap
 * shape, the real {@link SwapTask}. Nothing that decides the outcome is stubbed: the mint's
 * unblinded signature {@code C} is a genuine BDHKE signature, the issuer signature is a genuine
 * BIP-340 signature checked in {@code enforce} mode, and every witness is a genuine NUT-11
 * {@code SIG_ALL} signature over the aggregated message. Only the vault lookups and the signing
 * of outputs are stubbed, because neither is the subject.
 *
 * <p>NUT-11, which these tests check the mint against:
 * <ul>
 *   <li>{@code now <= locktime}: the lock is active and only the main pathway applies.</li>
 *   <li>{@code now > locktime} with a {@code refund} tag: the main pathway <em>continues</em> to
 *       apply, and the refund keys may spend <em>in addition</em>. So the link key keeps working
 *       after {@code x}; that is the {@code x + ε} window the owner accepted (#295, Q2).</li>
 *   <li>{@code now > locktime} with no {@code refund} tag: the proof is unlocked.</li>
 *   <li>{@code SIG_ALL}: every input must share {@code kind}, {@code data} and {@code tags}.</li>
 * </ul>
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/11.md">NUT-11</a>
 */
@DisplayName("imani-wallet#295: a link-locked voucher's refund path")
class LinkLockedVoucherRefundPathTest {

    private static final String KEYSET_ID = "0123456789abcdef";
    private static final String MERCHANT = "corner-cafe";
    private static final int PROOF_AMOUNT = 8;
    private static final String MAX_SATS = "max_sats";

    private static final long ONE_HOUR = 3_600L;
    private static final long ONE_DAY = 86_400L;

    private static final PrivateKey MINT_KEY = newKey();
    private static final PrivateKey GATEWAY = newKey();

    private PrivateKey linkKey;
    private PrivateKey refundKey;
    private MintProtocolService mintProtocolService;
    private MockedConstruction<DefaultProofVaultService> proofVault;

    /** Fresh link and refund keys per test, and a mint that holds the one keyset key. */
    @BeforeEach
    void setUp() throws CashuErrorException {
        linkKey = newKey();
        refundKey = newKey();
        mintProtocolService = Mockito.mock(MintProtocolService.class);
        Mockito.when(mintProtocolService.getPrivateKey(anyString(), anyInt(), any(Mint.class)))
                .thenReturn(MINT_KEY);
        proofVault = Mockito.mockConstruction(DefaultProofVaultService.class);
        InstalledVoucherIssuerBinding.install(enforcingBinding());
    }

    /** Restores the global binding so no other test inherits enforce mode. */
    @AfterEach
    void tearDown() {
        proofVault.close();
        InstalledVoucherIssuerBinding.install(null);
    }

    // ===== the six properties of design §8 phase 1 =====

    /**
     * 1. Before {@code x} the refund key cannot spend. This is what stops the relay, which holds a
     * valid refund-key witness from the moment the link is made, from taking the gift early.
     */
    @Test
    void aRefundKeySpendBeforeTheLocktimeIsRefused() throws Exception {
        Proof<Secret> gift = giftProof(linkLockedSecret(inAnHour(), aDayFromNow()));
        List<BlindedMessage> outputs = plainOutputs(4, 4);
        signSigAll(List.of(gift), outputs, refundKey);

        CashuErrorException refusal = assertThrows(CashuErrorException.class,
                () -> verifySwap(List.of(gift), outputs));

        assertEquals("verify_invalid_number_of_signatures", refusal.getErrorCode().name(),
                "before the locktime only the main pathway exists, so the refund witness counts for nothing");
    }

    /**
     * 2. After {@code x} the refund key spends. This is the relay posting the sender's pre-signed
     * refund swap.
     */
    @Test
    void aRefundKeySpendAfterTheLocktimeIsAccepted() throws Exception {
        Proof<Secret> gift = giftProof(linkLockedSecret(anHourAgo(), aDayFromNow()));
        List<BlindedMessage> outputs = plainOutputs(4, 4);
        signSigAll(List.of(gift), outputs, refundKey);

        assertDoesNotThrow(() -> verifySwap(List.of(gift), outputs));
    }

    /**
     * 3. After {@code x} the link key STILL spends. Not a bug: NUT-11 says the main pathway
     * "continue[s] to apply" once the lock expires, and the refund keys are an addition, not a
     * replacement. So a recipient who opens the link after {@code x} but before the relay posts
     * can still claim it. This is the {@code x + ε} window (design §2.1), accepted by the owner in
     * #295 Q2. A change here means the window has closed and the design must be revisited.
     */
    @Test
    void theLinkKeyAfterTheLocktimeIsStillAccepted() throws Exception {
        Proof<Secret> gift = giftProof(linkLockedSecret(anHourAgo(), aDayFromNow()));
        List<BlindedMessage> outputs = plainOutputs(PROOF_AMOUNT);
        signSigAll(List.of(gift), outputs, linkKey);

        assertDoesNotThrow(() -> verifySwap(List.of(gift), outputs),
                "NUT-11: the main pathway continues to apply after the locktime");
    }

    /**
     * 4. The refund swap's exact shape, through the whole swap: one voucher input, refund-signed
     * under {@code SIG_ALL}, swapped to plain outputs that sum to it. The mint refuses only mixed
     * <em>inputs</em> and unbalanced totals, so a voucher may leave as plain sats, and every
     * output is signed.
     */
    @Test
    void aVoucherInputSwapsToPlainOutputsUnderSigAll() throws Exception {
        Proof<Secret> gift = giftProof(linkLockedSecret(anHourAgo(), aDayFromNow()));
        List<BlindedMessage> outputs = plainOutputs(2, 2, 4);
        signSigAll(List.of(gift), outputs, refundKey);

        PostSwapResponse response = executeSwap(List.of(gift), outputs);

        assertThat(response.getBlindSignatures())
                .extracting(BlindSignature::getAmount)
                .containsExactly(2, 2, 4);
    }

    /**
     * 5. Two proofs of ONE voucher cannot share a {@code SIG_ALL} swap. Each carries its own
     * {@code issuer_sig}, because the issuer signs the proof's nonce, so their tags differ even
     * with every other term equal (here even {@code max_sats} is equal, the most favourable case).
     * NUT-11 requires identical {@code data} and {@code tags} on every {@code SIG_ALL} input, so
     * the refund is one swap per proof (design §2.3). The witness is a valid refund-key signature
     * after {@code x}, so uniformity is the only reason left to refuse.
     */
    @Test
    void aTwoProofSigAllSwapWhoseTagsDifferIsRefused() throws Exception {
        String voucherId = UUID.randomUUID().toString();
        long locktime = anHourAgo();
        long expiresAt = aDayFromNow();
        P2PKVoucherSecret firstSecret = linkLockedSecret(voucherId, locktime, expiresAt, PROOF_AMOUNT);
        P2PKVoucherSecret secondSecret = linkLockedSecret(voucherId, locktime, expiresAt, PROOF_AMOUNT);
        assertThat(firstSecret.getTags()).isNotEqualTo(secondSecret.getTags());
        List<Proof<Secret>> inputs = List.of(giftProof(firstSecret), giftProof(secondSecret));
        List<BlindedMessage> outputs = plainOutputs(PROOF_AMOUNT, PROOF_AMOUNT);
        signSigAll(inputs, outputs, refundKey);

        CashuErrorException refusal = assertThrows(CashuErrorException.class,
                () -> verifySwap(inputs, outputs));

        assertEquals("verify_proof_failed_error", refusal.getErrorCode().name());
        assertThat(refusal.getMessage()).contains("same P2PK spending condition");
    }

    /**
     * 6. The coupon's own expiry beats the refund. A voucher past {@code expires_at} is refused on
     * every path, the refund included, so if {@code x} is not well inside the coupon's expiry the
     * value is gone for everyone. The witness is a valid refund-key signature after {@code x}.
     */
    @Test
    void anExpiredVoucherIsRefusedOnTheRefundPath() throws Exception {
        Proof<Secret> gift = giftProof(linkLockedSecret(anHourAgo() - ONE_HOUR, aMinuteAgo()));
        List<BlindedMessage> outputs = plainOutputs(PROOF_AMOUNT);
        signSigAll(List.of(gift), outputs, refundKey);

        CashuErrorException refusal = assertThrows(CashuErrorException.class,
                () -> verifySwap(List.of(gift), outputs));

        assertEquals("voucher_expired", refusal.getErrorCode().name());
    }

    // ===== the hazard of design §2.2 =====

    /**
     * HAZARD, pinned as the mint's real behaviour: a {@code P2PK_VOUCHER} whose locktime has
     * passed and which carries NO {@code refund} tag is spendable by anyone, with no witness at
     * all, even under {@code SIG_ALL} and even in issuer-binding {@code enforce} mode. NUT-11 says
     * exactly this ("otherwise the proof is considered unlocked"), and the mint follows it for
     * proofs it did not create, so it is not a mint bug.
     *
     * <p>The wallet and gc must therefore never create a link-locked gift with a {@code locktime}
     * and no {@code refund}, and the gc signing route must refuse to sign one. Nothing refuses it
     * today: the {@code VoucherIssuanceRules} that {@code P2PKLocktimeUnlockTest} once credited
     * with preventing it does not exist in cashu-mint, cashu-voucher, cashu-lib or
     * imani-gateway-customer.
     */
    @Test
    void aPastLocktimeVoucherWithNoRefundKeyIsSpendableByAnyone() throws Exception {
        P2PKVoucherSecret secret = unsignedLinkLockedSecret(
                UUID.randomUUID().toString(), anHourAgo(), aDayFromNow());
        SignedLockedVoucher.createSigned(secret, privateHex(GATEWAY), xOnlyHex(GATEWAY));
        Proof<Secret> anyonesGift = giftProof(secret);
        anyonesGift.setWitness(null);

        assertDoesNotThrow(() -> verifySwap(List.of(anyonesGift), plainOutputs(PROOF_AMOUNT)),
                "documents an anyone-can-spend gift; the wallet must never create one");
    }

    // ===== what the relay cannot do =====

    /**
     * After {@code x} a key that is neither the link key nor the refund key is still refused. Pins
     * that the refund path checks WHO signed, so test 2 means "the refund key", not "anyone after
     * {@code x}".
     */
    @Test
    void aStrangersKeyAfterTheLocktimeIsRefused() throws Exception {
        Proof<Secret> gift = giftProof(linkLockedSecret(anHourAgo(), aDayFromNow()));
        List<BlindedMessage> outputs = plainOutputs(PROOF_AMOUNT);
        signSigAll(List.of(gift), outputs, newKey());

        CashuErrorException refusal = assertThrows(CashuErrorException.class,
                () -> verifySwap(List.of(gift), outputs));

        assertEquals("verify_invalid_refund_signature", refusal.getErrorCode().name());
    }

    /**
     * A relay that swaps one of the sender's outputs for its own is refused: the refund witness
     * covers every {@code B_} and amount, so the pre-signed refund can only pay the sender.
     */
    @Test
    void aRelayThatSubstitutesAnOutputIsRefused() throws Exception {
        Proof<Secret> gift = giftProof(linkLockedSecret(anHourAgo(), aDayFromNow()));
        List<BlindedMessage> sendersOutputs = plainOutputs(4, 4);
        signSigAll(List.of(gift), sendersOutputs, refundKey);
        List<BlindedMessage> relaysOutputs = List.of(sendersOutputs.get(0), plainOutputs(4).get(0));

        CashuErrorException refusal = assertThrows(CashuErrorException.class,
                () -> verifySwap(List.of(gift), relaysOutputs));

        assertEquals("verify_invalid_refund_signature", refusal.getErrorCode().name());
    }

    // ===== fixtures: the gift =====

    /** A single-proof gift secret with a fresh voucher id and a {@code max_sats} of the proof amount. */
    private P2PKVoucherSecret linkLockedSecret(long locktime, long expiresAt) {
        return linkLockedSecret(UUID.randomUUID().toString(), locktime, expiresAt, PROOF_AMOUNT);
    }

    /**
     * The gift as design §3.2 shapes it: locked to the link key, with {@code locktime},
     * one {@code refund} key, {@code SIG_ALL}, a {@code max_sats} bound, issuer-signed by the gateway.
     */
    private P2PKVoucherSecret linkLockedSecret(String voucherId, long locktime, long expiresAt, long maxSats) {
        P2PKVoucherSecret secret = unsignedLinkLockedSecret(voucherId, locktime, expiresAt);
        secret.setRefund(List.of(compressedHex(refundKey)));
        secret.addTag(MAX_SATS, List.<Object>of(String.valueOf(maxSats)));
        SignedLockedVoucher.createSigned(secret, privateHex(GATEWAY), xOnlyHex(GATEWAY));
        return secret;
    }

    /** The lock and voucher terms without a refund key or an issuer signature. */
    private P2PKVoucherSecret unsignedLinkLockedSecret(String voucherId, long locktime, long expiresAt) {
        P2PKVoucherSecret secret = new P2PKVoucherSecret(PrivateKey.derivePublicKey(linkKey).getBytes());
        secret.setVoucherId(voucherId);
        secret.setIssuerId(MERCHANT);
        secret.setUnit("sat");
        secret.setFaceValue(1000L);
        secret.setExpiresAt(expiresAt);
        secret.setSigFlag(P2PKSecret.SignatureFlag.SIG_ALL);
        secret.setLockTime(Math.toIntExact(locktime));
        return secret;
    }

    /**
     * A proof of {@code secret} carrying the unblinded signature this mint really produced for it,
     * {@code C = k·hash_to_curve(secret)}. Real, because {@code C} is part of the {@code SIG_ALL}
     * message and {@link ProofAuthenticity} checks it before any spending condition runs.
     */
    private static Proof<Secret> giftProof(P2PKVoucherSecret secret) {
        byte[] c = BDHKEUtils.signBlindedMessage(
                        BDHKEUtils.hashToCurve(secret.toString().getBytes(StandardCharsets.UTF_8)),
                        Utils.bigIntFromBytes(MINT_KEY.toBytes()))
                .getEncoded(true);
        Proof<Secret> proof = new Proof<>();
        proof.setAmount(PROOF_AMOUNT);
        proof.setKeySetId(KEYSET_ID);
        proof.setSecret(secret);
        proof.setUnblindedSignature(Signature.fromBytes(c));
        return proof;
    }

    /** Plain outputs in the active keyset: blinded messages carry no secret, so any {@code B_} will do. */
    private static List<BlindedMessage> plainOutputs(int... amounts) {
        List<BlindedMessage> outputs = new ArrayList<>(amounts.length);
        for (int amount : amounts) {
            BlindedMessage output = new BlindedMessage();
            output.setAmount(amount);
            output.setKeySetId(KeysetId.fromString(KEYSET_ID));
            output.setBlindedMessage(PublicKey.derivePublicKey(newKey()));
            outputs.add(output);
        }
        return outputs;
    }

    /** Signs the NUT-11 {@code SIG_ALL} swap message and puts the witness on the first input. */
    private static void signSigAll(List<Proof<Secret>> inputs, List<BlindedMessage> outputs, PrivateKey signer)
            throws NoSuchAlgorithmException {
        byte[] hash = Utils.sha256(P2PKTransaction.forSwap(inputs, outputs).sigAllMessage().toBytes());
        Witness witness = new Witness();
        witness.addSignature(Hex.toHexString(Schnorr.sign(hash, signer.toBytes())));
        inputs.get(0).setWitness(witness);
    }

    // ===== fixtures: the mint =====

    /** Runs the mint's real proof verification for a swap, dispatch included. */
    @SuppressWarnings("unchecked")
    private void verifySwap(List<Proof<Secret>> inputs, List<BlindedMessage> outputs) throws CashuErrorException {
        PostSwapRequest<Secret> request = Mockito.mock(PostSwapRequest.class);
        Mockito.when(request.getInputs()).thenReturn(inputs);
        Mockito.when(request.getBlindedMessages()).thenReturn(outputs);
        new VerifyProofsTask<>(new Mint(UUID.randomUUID().toString()), request, mintProtocolService,
                enforcingBinding(), VoucherDocumentContext.none()).execute();
    }

    /**
     * Runs the whole {@link SwapTask}: mixed-input rule, voucher totals, transaction validation and
     * proof verification are real; holding the inputs and signing the outputs are stubbed.
     */
    private PostSwapResponse executeSwap(List<Proof<Secret>> inputs, List<BlindedMessage> outputs)
            throws CashuErrorException {
        PostSwapRequest<Secret> request = new PostSwapRequest<>();
        request.setInputs(inputs);
        request.setBlindedMessages(outputs);
        MintLoadService mintLoadService = Mockito.mock(MintLoadService.class);
        Mockito.when(mintLoadService.load(any(UUID.class), Mockito.eq(false)))
                .thenReturn(new Mint(UUID.randomUUID().toString()));

        try (MockedStatic<MintProtocolServiceFactory> factory = Mockito.mockStatic(MintProtocolServiceFactory.class);
             MockedConstruction<SwapProofHold> hold = Mockito.mockConstruction(SwapProofHold.class);
             MockedConstruction<SignBlindedMessageTask> signer = Mockito.mockConstruction(
                     SignBlindedMessageTask.class, (task, context) -> signsWhatItIsGiven(task, context))) {
            factory.when(MintProtocolServiceFactory::getInstance).thenReturn(mintProtocolService);
            return new SwapTask<>(UUID.randomUUID(), request, mintLoadService,
                    Mockito.mock(SignatureVaultService.class)).execute();
        }
    }

    /** Stubs one output's signing to return a blind signature of that output's amount. */
    private static void signsWhatItIsGiven(SignBlindedMessageTask task, MockedConstruction.Context context)
            throws CashuErrorException {
        BlindedMessage output = (BlindedMessage) context.arguments().get(1);
        Mockito.doReturn(new BlindSignature(output.getAmount(), KeysetId.fromString(KEYSET_ID),
                SignatureTestData.sampleSignature(), null)).when(task).execute();
    }

    /** Enforce mode trusting only the gateway, so a passing issuer check is a real one. */
    private static VoucherIssuerBinding enforcingBinding() {
        return new VoucherIssuerBinding(VoucherIssuerBindingMode.ENFORCE,
                new VoucherSignerTrustList(Map.of(), List.of(xOnlyHex(GATEWAY))));
    }

    // ===== fixtures: time and keys =====

    /** Seconds since the epoch, the unit of both {@code locktime} and {@code expires_at}. */
    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    /** A locktime that has passed, by a margin no test run can close. */
    private static long anHourAgo() {
        return now() - ONE_HOUR;
    }

    /** A locktime still in the future, by a margin no test run can reach. */
    private static long inAnHour() {
        return now() + ONE_HOUR;
    }

    /** A coupon expiry that has passed. */
    private static long aMinuteAgo() {
        return now() - 60;
    }

    /** A coupon expiry comfortably after every locktime used here. */
    private static long aDayFromNow() {
        return now() + ONE_DAY;
    }

    /** A fresh secp256k1 key. */
    private static PrivateKey newKey() {
        return PrivateKey.fromBytes(Schnorr.generatePrivateKey());
    }

    /** The key's compressed public key in hex, the form NUT-11 tags carry. */
    private static String compressedHex(PrivateKey key) {
        return Hex.toHexString(PrivateKey.derivePublicKey(key).getBytes());
    }

    /** The key's x-only public key in hex, the form {@code issuer_pubkey} carries. */
    private static String xOnlyHex(PrivateKey key) {
        return compressedHex(key).substring(2);
    }

    /** The private key in hex, as the issuer signer takes it. */
    private static String privateHex(PrivateKey key) {
        return Hex.toHexString(key.toBytes());
    }
}
