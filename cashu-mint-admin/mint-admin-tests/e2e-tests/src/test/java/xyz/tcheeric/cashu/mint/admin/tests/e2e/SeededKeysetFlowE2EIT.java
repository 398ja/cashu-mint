package xyz.tcheeric.cashu.mint.admin.tests.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.math.ec.ECPoint;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.mint.admin.tests.e2e.infrastructure.AbstractAdminE2EIT;
import xyz.tcheeric.cashu.mint.admin.tests.e2e.infrastructure.AdminE2EClient;

/**
 * cashu-mint#545, end to end: a mint mint-admin provisions after the fix works for real money flows,
 * and its keys cannot be recomputed from its mint id.
 *
 * <p>A NEW mint is created through the admin API, so its keyset comes from the seeded derivation
 * rather than a fixture. Against that keyset this runs the whole wallet loop through phoenixd-mock:
 * NUT-04 mint (quote, autopaid invoice, blinded outputs, unblind), NUT-03 swap, NUT-05 melt. Then it
 * replays the forgery the issue describes: sign a proof with {@code SHA-256(mintId|unit|amount)}, the
 * pre-fix key, and present it. The mint must refuse it.
 *
 * <p>Nothing here prints a private key. The only secrets the test handles are the wallet's own
 * blinding factors and the forger's guessed key, and neither is logged.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SeededKeysetFlowE2EIT extends AbstractAdminE2EIT {

  private static final X9ECParameters CURVE = CustomNamedCurves.getByName("secp256k1");
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String UNIT = "sat";
  private static final List<Integer> LADDER = List.of(1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024);

  private static final String MINT_ID = UUID.randomUUID().toString();
  private static String keysetId;
  private static Map<Integer, ECPoint> publicKeys;
  private static List<Map<String, Object>> wallet = new ArrayList<>();

  @Test
  @Order(1)
  void provisionsANewMintWithANut02V2Keyset() {
    final AdminE2EClient admin = adminApiClient();
    final ResponseEntity<JsonNode> created =
        admin.post(
            "/admin/lifecycle/mints",
            Map.of(
                "mintId", MINT_ID,
                "metadata", Map.of("displayName", "seeded e2e " + MINT_ID.substring(0, 8)),
                "configuration",
                    Map.of(
                        "versionTag", "v1",
                        "cashu.unit", UNIT,
                        "cashu.denominations", "1,2,4,8,16,32,64,128,256,512,1024")));
    assertThat(created.getStatusCode().value()).isEqualTo(200);

    Awaitility.await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofSeconds(2))
        .untilAsserted(
            () ->
                assertThat(
                        admin.get("/admin/lifecycle/mints/" + MINT_ID)
                            .getBody()
                            .path("lifecycleState")
                            .asText())
                    .isEqualTo("PROVISIONED"));

    final JsonNode keysets = admin.get("/admin/lifecycle/mints/" + MINT_ID + "/keysets").getBody();
    keysetId = firstActiveKeysetId(keysets);
    assertThat(keysetId).as("NUT-02 v2 id: 01 version byte, 33 bytes").hasSize(66).startsWith("01");

    // The mint itself must advertise it, with one public key per denomination.
    Awaitility.await()
        // The mint caches keysets for mint.keyset-cache.ttl-seconds (60 s by default).
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(mintApiClient().get("/v1/keys/" + keysetId).getStatusCode().value())
            .isEqualTo(200));
    publicKeys = readKeys(mintApiClient().get("/v1/keys/" + keysetId).getBody());
    assertThat(publicKeys.keySet()).containsExactlyInAnyOrderElementsOf(LADDER);
  }

  @Test
  @Order(2)
  void theProvisionedKeysAreNotTheUnseededDerivation() throws Exception {
    // What an attacker who knows the mint id computes: the pre-fix private key. Compare only the
    // resulting PUBLIC key with the advertised one; no private key is ever printed.
    int matches = 0;
    for (final int amount : LADDER) {
      final ECPoint guessed = CURVE.getG().multiply(unseededKey(MINT_ID, amount)).normalize();
      if (guessed.equals(publicKeys.get(amount))) {
        matches++;
      }
    }
    assertThat(matches).as("advertised keys that equal SHA-256(mintId|unit|amount)*G").isZero();
  }

  @Test
  @Order(3)
  void mintsTokensThroughPhoenixdMock() {
    final int amount = 100; // 64 + 32 + 4
    final JsonNode quote =
        mintApiClient().post("/v1/mint/quote/bolt11", Map.of("amount", amount, "unit", UNIT)).getBody();
    final String quoteId = quote.path("quote").asText();
    assertThat(quoteId).isNotBlank();

    // phoenixd-mock settles the invoice on its own (autopay) and posts the payment webhook.
    Awaitility.await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofSeconds(1))
        .untilAsserted(
            () ->
                assertThat(mintApiClient().get("/v1/mint/quote/bolt11/" + quoteId).getBody().path("state").asText())
                    .isEqualTo("PAID"));

    final List<Blinded> outputs = blind(split(amount));
    final ResponseEntity<JsonNode> minted =
        mintApiClient().post("/v1/mint/bolt11", Map.of("quote", quoteId, "outputs", wire(outputs)));
    assertThat(minted.getStatusCode().value()).as("mint response %s", minted.getBody()).isEqualTo(200);

    wallet = unblind(outputs, minted.getBody().path("signatures"));
    assertThat(wallet.stream().mapToInt(p -> (Integer) p.get("amount")).sum()).isEqualTo(amount);
  }

  @Test
  @Order(4)
  void swapsTheMintedProofs() {
    final int total = wallet.stream().mapToInt(p -> (Integer) p.get("amount")).sum();
    final List<Blinded> outputs = blind(ones(total, 4));
    final ResponseEntity<JsonNode> swapped =
        mintApiClient().post("/v1/swap", Map.of("inputs", wallet, "outputs", wire(outputs)));
    assertThat(swapped.getStatusCode().value()).as("swap response %s", swapped.getBody()).isEqualTo(200);
    wallet = unblind(outputs, swapped.getBody().path("signatures"));
    assertThat(wallet.stream().mapToInt(p -> (Integer) p.get("amount")).sum()).isEqualTo(total);
  }

  @Test
  @Order(5)
  void meltsThroughPhoenixdMock() {
    final JsonNode invoice =
        mintApiClient().post("/v1/mint/quote/bolt11", Map.of("amount", 10, "unit", UNIT)).getBody();
    final String bolt11 = invoice.path("request").asText();
    final JsonNode meltQuote =
        mintApiClient().post("/v1/melt/quote/bolt11", Map.of("request", bolt11, "unit", UNIT)).getBody();
    final String meltQuoteId = meltQuote.path("quote").asText();
    assertThat(meltQuoteId).as("melt quote %s", meltQuote).isNotBlank();
    final int needed = meltQuote.path("amount").asInt() + meltQuote.path("fee_reserve").asInt();
    final int held = wallet.stream().mapToInt(p -> (Integer) p.get("amount")).sum();
    assertThat(held).as("wallet balance covers the melt").isGreaterThanOrEqualTo(needed);

    final ResponseEntity<JsonNode> melted =
        mintApiClient().post("/v1/melt/bolt11", Map.of("quote", meltQuoteId, "inputs", wallet));
    assertThat(melted.getStatusCode().value()).as("melt response %s", melted.getBody()).isEqualTo(200);
    assertThat(melted.getBody().path("state").asText("PAID")).isEqualTo("PAID");
  }

  @Test
  @Order(6)
  void refusesAProofForgedWithTheUnseededKey() throws Exception {
    // The issue's proof of concept: k = SHA-256(mintId|sat|8), C = k * hash_to_curve(secret).
    final String secret = HexFormat.of().formatHex(randomBytes(32));
    final ECPoint y = CURVE.getCurve().decodePoint(BDHKEUtils.hashToCurve(secret));
    final ECPoint c = y.multiply(unseededKey(MINT_ID, 8)).normalize();
    final Map<String, Object> forged =
        Map.of("id", keysetId, "amount", 8, "secret", secret, "C", HexFormat.of().formatHex(c.getEncoded(true)));

    final List<Blinded> outputs = blind(List.of(8));
    final ResponseEntity<JsonNode> swap =
        mintApiClient().post("/v1/swap", Map.of("inputs", List.of(forged), "outputs", wire(outputs)));

    assertThat(swap.getStatusCode().value())
        .as("a proof signed with SHA-256(mintId|unit|amount) must be refused")
        .isBetween(400, 499);
    assertThat(swap.getBody().path("signatures").isMissingNode() || swap.getBody().path("signatures").isEmpty())
        .isTrue();
  }

  // ----------------------------------------------------------------------------------------------

  private record Blinded(int amount, String secret, BigInteger r, ECPoint b) {}

  private static BigInteger unseededKey(final String mintId, final int amount) throws Exception {
    final byte[] digest = MessageDigest.getInstance("SHA-256")
        .digest((mintId + "|" + UNIT + "|" + amount).getBytes(StandardCharsets.UTF_8));
    return new BigInteger(1, digest);
  }

  private static byte[] randomBytes(final int n) {
    final byte[] b = new byte[n];
    RANDOM.nextBytes(b);
    return b;
  }

  private static BigInteger randomScalar() {
    BigInteger r;
    do {
      r = new BigInteger(1, randomBytes(32));
    } while (r.signum() == 0 || r.compareTo(CURVE.getN()) >= 0);
    return r;
  }

  private static List<Integer> split(final int amount) {
    final List<Integer> parts = new ArrayList<>();
    for (int bit = 1; bit <= amount; bit <<= 1) {
      if ((amount & bit) != 0) {
        parts.add(bit);
      }
    }
    return parts;
  }

  /** {@code total} split into at least {@code minParts} outputs, all from the ladder. */
  private static List<Integer> ones(final int total, final int minParts) {
    final List<Integer> parts = new ArrayList<>(split(total));
    while (parts.size() < minParts) {
      final int largest = parts.stream().max(Integer::compare).orElseThrow();
      if (largest == 1) {
        break;
      }
      parts.remove(Integer.valueOf(largest));
      parts.add(largest / 2);
      parts.add(largest / 2);
    }
    return parts;
  }

  private static List<Blinded> blind(final List<Integer> amounts) {
    final List<Blinded> out = new ArrayList<>();
    for (final int amount : amounts) {
      final String secret = HexFormat.of().formatHex(randomBytes(32));
      final BigInteger r = randomScalar();
      final ECPoint y = CURVE.getCurve().decodePoint(BDHKEUtils.hashToCurve(secret));
      final ECPoint b = y.add(CURVE.getG().multiply(r)).normalize();
      out.add(new Blinded(amount, secret, r, b));
    }
    return out;
  }

  private static List<Map<String, Object>> wire(final List<Blinded> outputs) {
    return outputs.stream()
        .map(o -> Map.<String, Object>of(
            "id", keysetId, "amount", o.amount(), "B_", HexFormat.of().formatHex(o.b().getEncoded(true))))
        .toList();
  }

  private static List<Map<String, Object>> unblind(final List<Blinded> outputs, final JsonNode signatures) {
    assertThat(signatures.size()).isEqualTo(outputs.size());
    final List<Map<String, Object>> proofs = new ArrayList<>();
    for (int i = 0; i < outputs.size(); i++) {
      final Blinded o = outputs.get(i);
      final JsonNode sig = signatures.get(i);
      assertThat(sig.path("amount").asInt()).isEqualTo(o.amount());
      final ECPoint cBlind = CURVE.getCurve().decodePoint(HexFormat.of().parseHex(sig.path("C_").asText()));
      final ECPoint k = publicKeys.get(o.amount());
      final ECPoint c = cBlind.subtract(k.multiply(o.r())).normalize();
      proofs.add(Map.of(
          "id", sig.path("id").asText(keysetId),
          "amount", o.amount(),
          "secret", o.secret(),
          "C", HexFormat.of().formatHex(c.getEncoded(true))));
    }
    return proofs;
  }

  private static String firstActiveKeysetId(final JsonNode keysets) {
    for (final JsonNode keyset : keysets.path("items")) {
      if ("SIGNING".equals(keyset.path("state").asText()) && UNIT.equals(keyset.path("unit").asText())) {
        return keyset.path("keySetId").asText();
      }
    }
    throw new AssertionError("mint-admin reports no SIGNING " + UNIT + " keyset: " + keysets);
  }

  private static Map<Integer, ECPoint> readKeys(final JsonNode body) {
    final JsonNode keys = body.path("keysets").get(0).path("keys");
    final Map<Integer, ECPoint> out = new java.util.HashMap<>();
    keys.fields().forEachRemaining(e ->
        out.put(Integer.parseInt(e.getKey()), CURVE.getCurve().decodePoint(HexFormat.of().parseHex(e.getValue().asText()))));
    return out;
  }
}
