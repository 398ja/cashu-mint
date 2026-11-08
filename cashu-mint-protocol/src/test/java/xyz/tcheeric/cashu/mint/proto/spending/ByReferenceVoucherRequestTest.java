package xyz.tcheeric.cashu.mint.proto.spending;

import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;
import xyz.tcheeric.cashu.entities.rest.nut05.PostMeltRequest;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherRedemptionCapMode;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.tasks.MeltTask;
import xyz.tcheeric.cashu.mint.proto.util.SwapRequestFingerprint;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherDocumentContext;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherRedemptionCap;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherRedemptionDeclaration;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherTerms;
import xyz.tcheeric.cashu.voucher.domain.VoucherDocuments;
import xyz.tcheeric.cashu.voucher.domain.util.VoucherSerializationUtils;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * cashu-voucher#64 at the request boundary: the {@code vouchers} map is validated once and its
 * limits hold, a by-reference voucher can never be melted, the redemption cap reads the same terms
 * for v1 and v2, and the NUT-19 replay fingerprint's relation to the map is pinned.
 */
@DisplayName("voucher documents at the request boundary")
class ByReferenceVoucherRequestTest {

    private static byte[] document(long faceValue) {
        VoucherSecret voucher = ByReferenceVoucherSpendingConditionTest.voucher();
        voucher.setFaceValue(faceValue);
        return VoucherSerializationUtils.toCbor(ByReferenceVoucherSpendingConditionTest.blobOf(voucher));
    }

    private static Proof<VoucherSecret> reference(byte[] document) {
        return ByReferenceVoucherSpendingConditionTest.proofWithData(Hex.decode(VoucherDocuments.key(document)));
    }

    private static Proof<VoucherSecret> inline(byte[] document) {
        return ByReferenceVoucherSpendingConditionTest.proofWithData(document);
    }

    // More documents than inputs is refused with 90032 before any voucher work.
    @Test
    @DisplayName("more documents than inputs is refused with 90032")
    void tooManyDocumentsAreRefused() {
        PostSwapRequest<Secret> request = ByReferenceRequests.swapCarrying(1, document(1), document(2));

        assertThatThrownBy(() -> VoucherDocumentContext.of(request))
                .isInstanceOfSatisfying(CashuErrorException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CashuErrorCode.voucher_document_invalid));
    }

    // A key that is not the hash of its value is refused with 90032.
    @Test
    @DisplayName("a key that does not hash its value is refused with 90032")
    void mismatchedKeyIsRefused() {
        PostSwapRequest<Secret> request = ByReferenceRequests.swapCarrying(2);
        request.setVouchers(new TreeMap<>(Map.of(VoucherDocuments.key(document(1)),
                HexFormat.of().formatHex(document(2)))));

        assertThatThrownBy(() -> VoucherDocumentContext.of(request))
                .isInstanceOfSatisfying(CashuErrorException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CashuErrorCode.voucher_document_invalid));
    }

    // A map whose total size exceeds the 1 MiB bound is refused with 90032 (64 KiB entries, 17 of them).
    @Test
    @DisplayName("an oversized vouchers map is refused with 90032")
    void oversizedMapIsRefused() {
        List<byte[]> documents = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            byte[] big = new byte[VoucherDocuments.MAX_DOCUMENT_BYTES];
            big[0] = (byte) i;
            big[1] = 1;
            documents.add(big);
        }
        PostSwapRequest<Secret> request =
                ByReferenceRequests.swapCarrying(20, documents.toArray(new byte[0][]));

        assertThatThrownBy(() -> VoucherDocumentContext.of(request))
                .isInstanceOfSatisfying(CashuErrorException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CashuErrorCode.voucher_document_invalid));
    }

    // A request without a vouchers map validates to the empty context, exactly as before the feature.
    @Test
    @DisplayName("no vouchers map means no documents, and v1 secrets pass through untouched")
    void absentMapIsEmpty() throws CashuErrorException {
        PostSwapRequest<Secret> request = ByReferenceRequests.swapCarrying(1);
        request.setVouchers(null);
        VoucherDocumentContext context = VoucherDocumentContext.of(request);
        Proof<VoucherSecret> v1 = inline(document(5));

        assertThat(context.size()).isZero();
        assertThat(context.termsSecret(v1.getSecret())).isSameAs(v1.getSecret());
    }

    // Melt refuses a by-reference voucher (Model B) whether or not its document is carried.
    @Test
    @DisplayName("melt refuses a v2 voucher, with or without its document")
    void meltRefusesV2() {
        byte[] doc = document(1000);
        for (Map<String, String> vouchers : List.of(Map.<String, String>of(), ByReferenceRequests.hexMap(doc))) {
            PostMeltRequest<VoucherSecret> request = new PostMeltRequest<>();
            request.setQuoteId("q-1");
            request.setInputs(new ArrayList<>(List.of(reference(doc))));
            request.setOutputs(new ArrayList<>());
            request.setVouchers(new TreeMap<>(vouchers));

            assertThatThrownBy(() -> melt(request).execute())
                    .isInstanceOfSatisfying(CashuErrorException.class, e ->
                            assertThat(e.getErrorCode()).isIn(CashuErrorCode.voucher_not_accepted));
        }
    }

    // A melt carrying an invalid vouchers map is refused with 90032 before anything is loaded.
    @Test
    @DisplayName("melt refuses an invalid vouchers map with 90032 before loading anything")
    void meltRefusesInvalidMap() {
        MintLoadService loads = Mockito.mock(MintLoadService.class);
        PostMeltRequest<VoucherSecret> request = new PostMeltRequest<>();
        request.setQuoteId("q-1");
        request.setInputs(new ArrayList<>(List.of(reference(document(1)))));
        request.setOutputs(new ArrayList<>());
        request.setVouchers(new TreeMap<>(Map.of("00".repeat(32), "aa")));

        assertThatThrownBy(() -> melt(request, loads).execute())
                .isInstanceOfSatisfying(CashuErrorException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CashuErrorCode.voucher_document_invalid));
        verifyNoInteractions(loads);
    }

    // The redemption cap reads the same terms off a v2 input (with its document) as off the v1 input.
    @Test
    @DisplayName("the redemption cap matches v2 exactly as it matches v1")
    void redemptionCapMatchesV2LikeV1() throws CashuErrorException {
        byte[] doc = document(1000);
        Proof<VoucherSecret> v1 = inline(doc);
        Proof<VoucherSecret> v2 = reference(doc);
        Optional<VoucherTerms> v1Terms = VoucherTerms.of(v1.getSecret());
        VoucherRedemptionCap cap = VoucherRedemptionCap.unconfigured();
        VoucherRedemptionCap.Authorised authorised = new VoucherRedemptionCap.Authorised(
                new VoucherRedemptionDeclaration(v1Terms.orElseThrow().voucherId(), 8), "ab".repeat(32));
        VoucherDocumentContext context = ByReferenceVoucherSpendingConditionTest.contextWith(doc);

        assertThat(cap.match(authorised, List.of(v2), context)).isEqualTo(cap.match(authorised, List.of(v1)));
        assertThat(cap.match(authorised, List.of(v1))).isPresent();
    }

    // Without its document, a v2 input's terms are unreadable to the cap: refused with 90032, never matched.
    @Test
    @DisplayName("the redemption cap refuses a v2 input whose document is missing")
    void redemptionCapRefusesV2WithoutDocument() {
        byte[] doc = document(1000);
        VoucherRedemptionCap cap = new VoucherRedemptionCap(VoucherRedemptionCapMode.LOG, null, List.of());
        VoucherRedemptionCap.Authorised authorised = new VoucherRedemptionCap.Authorised(
                new VoucherRedemptionDeclaration(UUID.randomUUID().toString(), 8), "ab".repeat(32));

        assertThatThrownBy(() -> cap.match(authorised, List.of(reference(doc)), VoucherDocumentContext.none()))
                .isInstanceOfSatisfying(CashuErrorException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CashuErrorCode.voucher_document_invalid));
    }

    // The legacy terms reader never decodes a hash as a document: v2 without context names no voucher.
    @Test
    @DisplayName("VoucherTerms.of reads no terms from a bare v2 secret")
    void bareV2HasNoTerms() {
        assertThat(VoucherTerms.of(reference(document(1)).getSecret())).isEmpty();
    }

    /*
     * NUT-19 replay: the fingerprint covers inputs (amount, keyset, wire secret, C) and outputs, not
     * the vouchers map. That is safe: a document is pinned by its hash, which is inside the wire
     * secret, so two requests with the same inputs can only differ in the map by carrying, or not
     * carrying, documents nobody references, which are ignored. A replay with the same inputs and
     * outputs therefore asks for the same swap. Pinned here so a future change is deliberate.
     */
    // Adding or dropping documents does not change the swap request fingerprint.
    @Test
    @DisplayName("the replay fingerprint does not depend on the vouchers map")
    void fingerprintIgnoresTheVouchersMap() {
        byte[] doc = document(1000);
        PostSwapRequest<VoucherSecret> without = new PostSwapRequest<>(List.of(reference(doc)), List.of());
        PostSwapRequest<VoucherSecret> with = new PostSwapRequest<>(List.of(reference(doc)), List.of());
        with.setVouchers(new TreeMap<>(ByReferenceRequests.hexMap(doc)));

        assertThat(SwapRequestFingerprint.of(with)).isEqualTo(SwapRequestFingerprint.of(without));
    }

    // A v1 and a v2 proof of one voucher are different inputs, so their swaps fingerprint differently.
    @Test
    @DisplayName("v1 and v2 forms of one voucher fingerprint differently")
    void fingerprintSeparatesV1FromV2() {
        byte[] doc = document(1000);

        assertThat(SwapRequestFingerprint.of(new PostSwapRequest<>(List.of(reference(doc)), List.of())))
                .isNotEqualTo(SwapRequestFingerprint.of(new PostSwapRequest<>(List.of(inline(doc)), List.of())));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static MeltTask<VoucherSecret> melt(PostMeltRequest<VoucherSecret> request) {
        return melt(request, Mockito.mock(MintLoadService.class));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static MeltTask<VoucherSecret> melt(PostMeltRequest<VoucherSecret> request, MintLoadService loads) {
        return new MeltTask(request, PaymentMethod.MOCK, new Mint(UUID.randomUUID().toString()),
                Mockito.mock(MintProtocolService.class), loads, Mockito.mock(MintVaultService.class),
                Mockito.mock(ProofVaultService.class));
    }
}
