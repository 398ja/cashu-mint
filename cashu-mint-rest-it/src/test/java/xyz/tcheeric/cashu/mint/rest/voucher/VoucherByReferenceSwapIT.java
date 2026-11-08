package xyz.tcheeric.cashu.mint.rest.voucher;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.BackingStrategy;
import xyz.tcheeric.cashu.voucher.domain.VoucherDocuments;
import xyz.tcheeric.cashu.voucher.domain.VoucherSignatureService;
import xyz.tcheeric.cashu.voucher.domain.util.VoucherSerializationUtils;

/**
 * cashu-voucher#64 through the real {@code POST /v1/swap}: an unlocked voucher stored once per
 * request in {@code vouchers} and referenced from each proof by {@code SHA256(D)}.
 *
 * <p>Same fixture and properties as {@link VoucherRedemptionCapIT} (issuer binding and the
 * redemption cap both enforced), so every voucher check that refuses a forged inline voucher also
 * runs here, on the document. A by-reference secret's {@code data} is 32 bytes of hash: had the
 * mint read the terms or the issuer signature from it instead of from the document, every honest
 * case below would be refused.
 *
 * <p>Its own redeemer token keeps its Spring context its own. The mint installs the redemption cap
 * in a static when a context starts, so a class that reused another class's cached context would
 * see whichever cap the most recently started context installed.
 */
@TestPropertySource(properties = {
    "cashu.mint.voucher.redemption-cap=enforce",
    "cashu.mint.voucher.issuer-binding=enforce",
    "cashu.mint.voucher.trusted-signers=" + VoucherRedemptionFixtures.ISSUER_PUB_X,
    "cashu.mint.voucher.redeemer-tokens=" + VoucherByReferenceSwapIT.REDEEMER_TOKEN,
})
class VoucherByReferenceSwapIT extends VoucherRedemptionFixtures {

  static final String REDEEMER_TOKEN = "it-by-reference-redeemer-token";

  @Override
  String redeemerToken() {
    return REDEEMER_TOKEN;
  }

  // ------------------------------------------------------------------------------- honest flows

  /** v1 is unchanged: the inline voucher swaps exactly as before, with no {@code vouchers} map. */
  @Test
  void anInlineVoucherStillSwaps() throws Exception {
    String blob = signedBlobHex(UUID.randomUUID().toString());

    ResponseEntity<String> v1 = swap(voucherProofs(blob, 8, 2), outputs(8, 2), null, null);

    assertThat(v1.getStatusCode().is2xxSuccessful()).as("body=%s", v1.getBody()).isTrue();
  }

  /** v2: three proofs name one document, carried once, and the swap is accepted. */
  @Test
  void aByReferenceVoucherSwapsWithItsDocumentCarriedOnce() throws Exception {
    String blob = signedBlobHex(UUID.randomUUID().toString());

    ResponseEntity<String> v2 =
        swapV2(referenceProofs(blob, 8, 1, 1), outputs(8, 2), Map.of(key(blob), blob), null, null);

    assertThat(v2.getStatusCode().is2xxSuccessful()).as("body=%s", v2.getBody()).isTrue();
  }

  /** v1 and v2 inputs of one coupon mix in one swap, and the document map may ride along. */
  @Test
  void inlineAndByReferenceInputsMixInOneSwap() throws Exception {
    String blob = signedBlobHex(UUID.randomUUID().toString());
    List<Map<String, Object>> inputs = new ArrayList<>(voucherProofs(blob, 8));
    inputs.addAll(referenceProofs(blob, 2));

    ResponseEntity<String> mixed = swapV2(inputs, outputs(8, 2), Map.of(key(blob), blob), null, null);

    assertThat(mixed.getStatusCode().is2xxSuccessful()).as("body=%s", mixed.getBody()).isTrue();
  }

  /** A declared redemption of a v2 coupon is counted against the cap like a v1 one. */
  @Test
  void aDeclaredByReferenceRedemptionIsCounted() throws Exception {
    String voucherId = UUID.randomUUID().toString();
    String blob = signedBlobHex(voucherId);

    ResponseEntity<String> redeemed = swapV2(referenceProofs(blob, 8, 2), outputs(8, 2),
        Map.of(key(blob), blob), voucherId + ":10", redeemerToken());

    assertThat(redeemed.getStatusCode().is2xxSuccessful()).as("body=%s", redeemed.getBody()).isTrue();
    assertThat(redeemed(voucherId)).isEqualTo(CAP_SATS);
  }

  /**
   * The cap is per coupon, not per spelling: a coupon fully redeemed inline cannot be credited
   * again by relabelling its document onto fresh by-reference proofs, nor the other way round.
   */
  @Test
  void switchingSpellingDoesNotResetTheCap() throws Exception {
    String inlineFirst = UUID.randomUUID().toString();
    String blobA = signedBlobHex(inlineFirst);
    assertThat(redeem(inlineFirst, 10, voucherProofs(blobA, 8, 2)).getStatusCode().is2xxSuccessful()).isTrue();
    long signedBefore = blindSignatureJpaRepository.count();

    ResponseEntity<String> relabelV2 = swapV2(referenceProofs(blobA, 8, 2), outputs(8, 2),
        Map.of(key(blobA), blobA), inlineFirst + ":10", redeemerToken());

    assertThat(relabelV2.getStatusCode().is4xxClientError()).as("body=%s", relabelV2.getBody()).isTrue();
    assertThat(relabelV2.getBody()).contains("\"code\":90025");
    assertThat(blindSignatureJpaRepository.count()).as("a refused redemption signs nothing")
        .isEqualTo(signedBefore);

    String referenceFirst = UUID.randomUUID().toString();
    String blobB = signedBlobHex(referenceFirst);
    assertThat(swapV2(referenceProofs(blobB, 8, 2), outputs(8, 2), Map.of(key(blobB), blobB),
        referenceFirst + ":10", redeemerToken()).getStatusCode().is2xxSuccessful()).isTrue();

    ResponseEntity<String> relabelV1 = redeem(referenceFirst, 10, voucherProofs(blobB, 8, 2));

    assertThat(relabelV1.getStatusCode().is4xxClientError()).as("body=%s", relabelV1.getBody()).isTrue();
    assertThat(redeemed(inlineFirst)).isEqualTo(CAP_SATS);
    assertThat(redeemed(referenceFirst)).isEqualTo(CAP_SATS);
  }

  // ---------------------------------------------------------------------------------- refusals

  /** A reference whose document the request does not carry is refused with 90032, signing nothing. */
  @Test
  void aMissingDocumentIsRefusedWith90032() throws Exception {
    String blob = signedBlobHex(UUID.randomUUID().toString());
    long signedBefore = blindSignatureJpaRepository.count();

    ResponseEntity<String> missing = swapV2(referenceProofs(blob, 8, 2), outputs(8, 2), Map.of(), null, null);

    assertRefused(missing, 90032);
    assertThat(blindSignatureJpaRepository.count()).isEqualTo(signedBefore);
  }

  /** A map entry whose key is not the hash of its value is refused with 90032. */
  @Test
  void aKeyThatDoesNotHashItsDocumentIsRefusedWith90032() throws Exception {
    String blob = signedBlobHex(UUID.randomUUID().toString());
    String other = signedBlobHex(UUID.randomUUID().toString());

    ResponseEntity<String> swapped =
        swapV2(referenceProofs(blob, 8, 2), outputs(8, 2), Map.of(key(blob), other), null, null);

    assertRefused(swapped, 90032);
  }

  /** More documents than inputs is refused with 90032 before any voucher work. */
  @Test
  void moreDocumentsThanInputsIsRefusedWith90032() throws Exception {
    String a = signedBlobHex(UUID.randomUUID().toString());
    String b = signedBlobHex(UUID.randomUUID().toString());

    ResponseEntity<String> tooMany =
        swapV2(referenceProofs(a, 8), outputs(8), Map.of(key(a), a, key(b), b), null, null);

    assertRefused(tooMany, 90032);
  }

  /**
   * The issuer signature is checked on the document: a document whose face value was raised after
   * signing is carried under its own correct hash, and is refused as a forgery (90019), not as a
   * document fault.
   */
  @Test
  void aForgedDocumentUnderItsOwnHashIsRefusedAsAForgery() throws Exception {
    String forged = forgedBlobHex(UUID.randomUUID().toString());
    long signedBefore = blindSignatureJpaRepository.count();

    ResponseEntity<String> refused =
        swapV2(referenceProofs(forged, 8, 2), outputs(8, 2), Map.of(key(forged), forged), null, null);

    assertRefused(refused, 90019);
    assertThat(blindSignatureJpaRepository.count()).isEqualTo(signedBefore);
  }

  /** A by-reference voucher cannot be melted either, with or without its document. */
  @Test
  void aByReferenceVoucherCannotBeMelted() throws Exception {
    String voucherId = UUID.randomUUID().toString();
    String blob = signedBlobHex(voucherId);
    for (Map<String, String> vouchers : List.of(Map.<String, String>of(), Map.of(key(blob), blob))) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("quote", "q-" + UUID.randomUUID());
      body.put("inputs", referenceProofs(blob, 8, 2));
      if (!vouchers.isEmpty()) {
        body.put("vouchers", vouchers);
      }

      ResponseEntity<String> melt = post("/v1/melt/bolt11", MAPPER.writeValueAsString(body), new HttpHeaders());

      assertThat(melt.getStatusCode().is2xxSuccessful()).as("vouchers=%s body=%s", vouchers.size(), melt.getBody())
          .isFalse();
    }
    assertThat(redeemed(voucherId)).isZero();
  }

  // ----------------------------------------------------------------------------------- helpers

  private static void assertRefused(ResponseEntity<String> response, int code) {
    assertThat(response.getStatusCode().is4xxClientError()).as("body=%s", response.getBody()).isTrue();
    assertThat(response.getBody()).contains("\"code\":" + code);
  }

  /** {@code hex(SHA256(D))} for a document given as hex. */
  private static String key(String documentHex) {
    return VoucherDocuments.key(Hex.decode(documentHex));
  }

  /** v2 proofs: {@code data} is {@code SHA256(D)}, each with a fresh NUT-10 nonce. */
  private static List<Map<String, Object>> referenceProofs(String documentHex, int... amounts) {
    String reference = key(documentHex);
    List<Map<String, Object>> proofs = new ArrayList<>();
    for (int amount : amounts) {
      String secret = "[\"VOUCHER\",{\"nonce\":\"" + UUID.randomUUID().toString().replace("-", "")
          + "\",\"data\":\"" + reference + "\",\"tags\":[]}]";
      proofs.add(proof(amount, secret));
    }
    return proofs;
  }

  private ResponseEntity<String> swapV2(List<Map<String, Object>> inputs, List<Map<String, Object>> outputs,
                                        Map<String, String> vouchers, String declaration, String token)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("inputs", inputs);
    body.put("outputs", outputs);
    if (!vouchers.isEmpty()) {
      body.put("vouchers", vouchers);
    }
    return post("/v1/swap", MAPPER.writeValueAsString(body), redemptionHeaders(declaration, token));
  }

  /** {@link #signedBlobHex}, but with the face value raised after the issuer signed it. */
  private static String forgedBlobHex(String voucherId) {
    VoucherSecret secret = VoucherSecret.builder()
        .voucherId(UUID.fromString(voucherId))
        .issuerId(STALL)
        .unit("EUR")
        .faceValue(FACE_CENTS)
        .backingStrategy(BackingStrategy.MINIMAL.name())
        .issuanceRatio(RATIO)
        .faceDecimals(2)
        .build();
    String pub = Hex.toHexString(PrivateKey.derivePublicKey(PrivateKey.fromBytes(ISSUER_KEY)).getBytes())
        .substring(2);
    VoucherSignatureService.createSigned(secret, Hex.toHexString(ISSUER_KEY), pub);
    Map<String, Object> blob = new LinkedHashMap<>();
    blob.put("voucherId", voucherId);
    blob.put("issuerId", STALL);
    blob.put("unit", "EUR");
    blob.put("faceValue", FACE_CENTS * 100);
    blob.put("backingStrategy", BackingStrategy.MINIMAL.name());
    blob.put("issuanceRatio", RATIO);
    blob.put("faceDecimals", 2);
    blob.put("nonce", secret.getNonce());
    blob.put("issuerSignature", secret.getIssuerSignature());
    blob.put("issuerPublicKey", secret.getIssuerPublicKey());
    return Hex.toHexString(VoucherSerializationUtils.toCbor(blob));
  }
}
