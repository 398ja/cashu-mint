package xyz.tcheeric.cashu.mint.admin.tests.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
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
 * #556: NUT-07 must report a proof the mint refunded after a refused hold as UNSPENT, not SPENT.
 *
 * <p>Opt-in, like the other admin e2e ITs. Needs a vault-backed mint (MINT_PRELOAD_ENABLED=false).
 * A melt or swap that carries one already-spent input among fresh ones fails closed, and the
 * vault refunds the fresh rows to UNSPENT (hold NULL). Those proofs must read UNSPENT, and must
 * then actually spend. No key material is printed.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CheckStateRefundedProofsE2EIT extends AbstractAdminE2EIT {

  private static final X9ECParameters CURVE = CustomNamedCurves.getByName("secp256k1");
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String UNIT = "sat";
  private static final String PROJECT = System.getProperty("e2e.compose.project", "cashu-e2e");
  private static final String MINT_ID = UUID.randomUUID().toString();
  private static String k2;
  private static final Map<String, Map<Integer, ECPoint>> KEYS = new HashMap<>();
  private static List<Map<String, Object>> walletK2 = new ArrayList<>();

  @Test
  @Order(1)
  void provisionsAVaultBackedMint() {
    k2 = provision(MINT_ID, "0");
    awaitMintAdvertises(k2);
    KEYS.put(k2, readKeys(mintApiClient().get("/v1/keys/" + k2).getBody()));
    walletK2 = mintTokens(k2, 4095);
    log("keyset %s wallet=%d proofs", shortId(k2), walletK2.size());
  }

  @Test
  @Order(2)
  void failedMeltRefundsFreshInputsAsUnspentAndTheyThenMeltOnANewQuote() {
    final List<Map<String, Object>> spent = spendSome();
    final JsonNode q = meltQuote();
    final List<Map<String, Object>> fresh = take(walletK2, need(q), List.of());
    final List<Map<String, Object>> replay = new ArrayList<>(fresh);
    replay.add(spent.get(0));
    final ResponseEntity<JsonNode> refused = mintApiClient().post("/v1/melt/bolt11",
        Map.of("quote", q.path("quote").asText(), "inputs", replay));
    log("melt[fresh + spent] status=%d body=%s", refused.getStatusCode().value(), brief(refused.getBody()));
    assertThat(refused.getStatusCode().value()).isGreaterThanOrEqualTo(400);
    log("checkstate[refunded melt inputs] %s", statesOf(fresh));
    assertUnspent(fresh);
    assertSpent(List.of(spent.get(0)));

    final JsonNode q2 = meltQuote();
    final ResponseEntity<JsonNode> melted = mintApiClient().post("/v1/melt/bolt11",
        Map.of("quote", q2.path("quote").asText(), "inputs", fresh));
    log("melt[refunded inputs, new quote] status=%d", melted.getStatusCode().value());
    assertThat(melted.getStatusCode().value()).as("refunded inputs really spend").isEqualTo(200);
    assertSpent(fresh);
  }

  @Test
  @Order(3)
  void inFlightHoldReadsPendingAndARefusedSwapHoldRefundsItsOtherInputsAsUnspent() throws Exception {
    // A decoy with a vault row, put under another hold: the in-flight state of a melt whose
    // payment is out. It passes swap validation (only SPENT is terminal) but cannot be claimed.
    final Map<String, Object> decoy = refundedByAFailedMelt().get(0);
    final String other = UUID.randomUUID().toString().replace("-", "");
    assertThat(psql("UPDATE t_proof SET state = 'PENDING', hold_id = '" + other + "' WHERE " + rowOf(decoy)
        + " AND state = 'UNSPENT' AND hold_id IS NULL")).as("decoy held").contains("UPDATE 1");
    log("checkstate[decoy under an in-flight hold] %s", statesOf(List.of(decoy)));
    assertStates(List.of(decoy), "PENDING");

    // Fresh proofs the vault has never seen. The swap inserts and claims their rows, fails to
    // claim the decoy (proofs_not_bound) and SwapProofHold.release refunds the rows it bound.
    final List<Map<String, Object>> fresh = take(walletK2, 64, List.of());
    final List<Map<String, Object>> inputs = new ArrayList<>(fresh);
    inputs.add(decoy);
    final ResponseEntity<JsonNode> refused = mintApiClient().post("/v1/swap",
        Map.of("inputs", inputs, "outputs", wire(blind(ladder(sum(inputs))), k2)));
    log("swap[fresh + held decoy] status=%d body=%s", refused.getStatusCode().value(), brief(refused.getBody()));
    assertThat(refused.getStatusCode().value()).isGreaterThanOrEqualTo(400);
    final String rows = psql("SELECT count(*) FROM t_proof WHERE state = 'UNSPENT' AND hold_id IS NULL AND ("
        + String.join(" OR ", fresh.stream().map(CheckStateRefundedProofsE2EIT::rowOf).toList()) + ")").trim();
    log("refunded swap rows in vault: %s of %d", rows, fresh.size());
    assertThat(rows).as("the swap hold wrote and refunded a row per fresh input").isEqualTo(String.valueOf(fresh.size()));
    log("checkstate[refunded swap inputs] %s", statesOf(fresh));
    assertUnspent(fresh);

    final List<Blinded> outs = blind(ladder(sum(fresh)));
    final ResponseEntity<JsonNode> swapped = mintApiClient().post("/v1/swap",
        Map.of("inputs", fresh, "outputs", wire(outs, k2)));
    log("swap[refunded inputs] status=%d", swapped.getStatusCode().value());
    assertThat(swapped.getStatusCode().value()).as("refunded inputs really swap").isEqualTo(200);
    assertSpent(fresh);
    walletK2.addAll(unblind(outs, swapped.getBody().path("signatures"), k2));

    // The decoy's hold resolves without a spend: it is UNSPENT again, then SPENT after a swap.
    psql("UPDATE t_proof SET state = 'UNSPENT', hold_id = NULL WHERE hold_id = '" + other + "'");
    assertUnspent(List.of(decoy));
    final ResponseEntity<JsonNode> decoySwap = mintApiClient().post("/v1/swap",
        Map.of("inputs", List.of(decoy), "outputs", wire(blind(ladder(sum(List.of(decoy)))), k2)));
    assertThat(decoySwap.getStatusCode().value()).isEqualTo(200);
    assertSpent(List.of(decoy));
  }

  // ---------------------------------------------------------------------------------- helpers

  /** Fresh proofs that a melt with one spent input bound and then refunded (#556 path). */
  private List<Map<String, Object>> refundedByAFailedMelt() {
    final List<Map<String, Object>> spent = spendSome();
    final JsonNode q = meltQuote();
    final List<Map<String, Object>> fresh = take(walletK2, need(q), List.of());
    final List<Map<String, Object>> replay = new ArrayList<>(fresh);
    replay.add(spent.get(0));
    assertThat(mintApiClient().post("/v1/melt/bolt11", Map.of("quote", q.path("quote").asText(), "inputs", replay))
        .getStatusCode().value()).isGreaterThanOrEqualTo(400);
    return fresh;
  }

  /** SQL predicate for a proof's vault row, keyed on Y (the storage key) or, legacy, the secret. */
  private static String rowOf(final Map<String, Object> p) {
    return "secret IN ('" + yOf(p) + "', '" + p.get("secret") + "')";
  }

  /** Melts a few proofs so the test has a really SPENT proof to replay. */
  private List<Map<String, Object>> spendSome() {
    final JsonNode q = meltQuote();
    refillTo(2 * need(q) + 64);
    final List<Map<String, Object>> first = take(walletK2, need(q), List.of());
    assertThat(mintApiClient().post("/v1/melt/bolt11", Map.of("quote", q.path("quote").asText(), "inputs", first))
        .getStatusCode().value()).isEqualTo(200);
    assertSpent(first);
    return first;
  }

  private void refillTo(final int target) {
    while (sum(walletK2) < target) {
      walletK2.addAll(mintTokens(k2, 4095));
    }
  }

  private static int need(final JsonNode quote) {
    return quote.path("amount").asInt() + quote.path("fee_reserve").asInt();
  }

  private static String yOf(final Map<String, Object> p) {
    return HexFormat.of().formatHex(BDHKEUtils.hashToCurve((String) p.get("secret")));
  }

  private List<String> statesOf(final List<Map<String, Object>> proofs) {
    final List<String> ys = proofs.stream()
        .map(p -> HexFormat.of().formatHex(BDHKEUtils.hashToCurve((String) p.get("secret")))).toList();
    final List<String> out = new ArrayList<>();
    mintApiClient().post("/v1/checkstate", Map.of("Ys", ys)).getBody().path("states")
        .forEach(st -> out.add(st.path("state").asText()));
    return out;
  }

  private void awaitState(final List<Map<String, Object>> proofs, final String expected) {
    Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1))
        .untilAsserted(() -> assertStates(proofs, expected));
  }

  private static String psql(final String sql) throws Exception {
    return docker("exec", PROJECT + "-cashu-vault-db-1", "psql", "-U", "postgres", "-d", "cashu_vault", "-tAc", sql);
  }

  private record Blinded(int amount, String secret, BigInteger r, ECPoint b) {}

  private static void log(final String fmt, final Object... args) {
    System.out.println("E2E556 " + String.format(fmt, args));
  }

  private static String shortId(final String id) {
    return id == null ? "null" : id.substring(0, Math.min(10, id.length()));
  }

  private static String brief(final JsonNode body) {
    if (body == null) {
      return "null";
    }
    if (body.has("code") || body.has("detail")) {
      return "{code=" + body.path("code").asText() + ", detail=" + body.path("detail").asText() + "}";
    }
    return "{state=" + body.path("state").asText() + ", change=" + body.path("change").size() + "}";
  }

  private String provision(final String mintId, final String feePpk) {
    final AdminE2EClient admin = adminApiClient();
    final ResponseEntity<JsonNode> created = admin.post("/admin/lifecycle/mints", Map.of(
        "mintId", mintId,
        "metadata", Map.of("displayName", "e2e556 " + mintId.substring(0, 8)),
        "configuration", Map.of("versionTag", "v1", "cashu.unit", UNIT,
            "cashu.denominations", "1,2,4,8,16,32,64,128,256,512,1024", "cashu.input_fee_ppk", feePpk)));
    assertThat(created.getStatusCode().value()).as("create %s", created.getBody()).isEqualTo(200);
    Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() ->
        assertThat(admin.get("/admin/lifecycle/mints/" + mintId).getBody().path("lifecycleState").asText())
            .isEqualTo("PROVISIONED"));
    return firstActiveKeysetId(admin.get("/admin/lifecycle/mints/" + mintId + "/keysets").getBody());
  }

  private void awaitMintAdvertises(final String keysetId) {
    Awaitility.await().atMost(Duration.ofSeconds(150)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
      assertThat(mintApiClient().get("/v1/keys/" + keysetId).getStatusCode().value()).isEqualTo(200);
      assertThat(keysetFlag(keysetId, "id").asText()).isEqualTo(keysetId);
    });
  }

  private JsonNode keysetFlag(final String keysetId, final String field) {
    for (final JsonNode ks : mintApiClient().get("/v1/keysets").getBody().path("keysets")) {
      if (keysetId.equals(ks.path("id").asText())) {
        return ks.path(field);
      }
    }
    return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
  }

  private List<Map<String, Object>> mintTokens(final String keysetId, final int amount) {
    final JsonNode quote = mintApiClient().post("/v1/mint/quote/bolt11", Map.of("amount", amount, "unit", UNIT)).getBody();
    final String quoteId = quote.path("quote").asText();
    assertThat(quoteId).as("mint quote %s", quote).isNotBlank();
    Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() ->
        assertThat(mintApiClient().get("/v1/mint/quote/bolt11/" + quoteId).getBody().path("state").asText())
            .isEqualTo("PAID"));
    final List<Blinded> outputs = blind(ladder(amount));
    final ResponseEntity<JsonNode> minted = mintApiClient().post("/v1/mint/bolt11",
        Map.of("quote", quoteId, "outputs", wire(outputs, keysetId)));
    assertThat(minted.getStatusCode().value()).as("mint %s", minted.getBody()).isEqualTo(200);
    final JsonNode sigs = minted.getBody().path("signatures");
    for (int i = 0; i < sigs.size(); i++) {
      assertThat(verifyDleq(sigs.get(i), outputs.get(i), KEYS.get(keysetId))).as("mint DLEQ").isTrue();
    }
    return unblind(outputs, sigs, keysetId);
  }

  private JsonNode meltQuote() {
    final JsonNode invoice = mintApiClient().post("/v1/mint/quote/bolt11", Map.of("amount", 10, "unit", UNIT)).getBody();
    final JsonNode quote = mintApiClient().post("/v1/melt/quote/bolt11",
        Map.of("request", invoice.path("request").asText(), "unit", UNIT)).getBody();
    assertThat(quote.path("quote").asText()).as("melt quote %s", quote).isNotBlank();
    return quote;
  }

  private static int sum(final List<Map<String, Object>> proofs) {
    return proofs.stream().mapToInt(p -> (Integer) p.get("amount")).sum();
  }

  /** Removes and returns proofs, largest first, until at least {@code target}. */
  private static List<Map<String, Object>> take(final List<Map<String, Object>> wallet, final int target,
                                                final List<Map<String, Object>> exclude) {
    wallet.sort((a, b) -> (Integer) b.get("amount") - (Integer) a.get("amount"));
    final List<Map<String, Object>> out = new ArrayList<>();
    int s = 0;
    for (final Map<String, Object> p : new ArrayList<>(wallet)) {
      if (s >= target) {
        break;
      }
      if (exclude.contains(p)) {
        continue;
      }
      out.add(p);
      s += (Integer) p.get("amount");
    }
    assertThat(s).as("wallet covers %d", target).isGreaterThanOrEqualTo(target);
    wallet.removeAll(out);
    return out;
  }
  private static List<Integer> ladder(final int amount) {
    final List<Integer> parts = new ArrayList<>();
    int left = amount;
    while (left >= 1024) {
      parts.add(1024);
      left -= 1024;
    }
    for (int bit = 512; bit >= 1; bit >>= 1) {
      if ((left & bit) != 0) {
        parts.add(bit);
      }
    }
    return parts;
  }

  private static byte[] randomBytes(final int n) {
    final byte[] b = new byte[n];
    RANDOM.nextBytes(b);
    return b;
  }

  private static List<Blinded> blind(final List<Integer> amounts) {
    final List<Blinded> out = new ArrayList<>();
    for (final int amount : amounts) {
      final String secret = HexFormat.of().formatHex(randomBytes(32));
      BigInteger r;
      do {
        r = new BigInteger(1, randomBytes(32));
      } while (r.signum() == 0 || r.compareTo(CURVE.getN()) >= 0);
      final ECPoint y = CURVE.getCurve().decodePoint(BDHKEUtils.hashToCurve(secret));
      out.add(new Blinded(amount, secret, r, y.add(CURVE.getG().multiply(r)).normalize()));
    }
    return out;
  }

  private static List<Map<String, Object>> wire(final List<Blinded> outputs, final String keysetId) {
    return outputs.stream().map(o -> Map.<String, Object>of("id", keysetId, "amount", o.amount(),
        "B_", HexFormat.of().formatHex(o.b().getEncoded(true)))).toList();
  }

  private static List<Map<String, Object>> unblind(final List<Blinded> outputs, final JsonNode signatures,
                                                   final String keysetId) {
    assertThat(signatures.size()).isEqualTo(outputs.size());
    final List<Map<String, Object>> proofs = new ArrayList<>();
    for (int i = 0; i < outputs.size(); i++) {
      final Blinded o = outputs.get(i);
      final JsonNode sig = signatures.get(i);
      assertThat(sig.path("id").asText()).isEqualTo(keysetId);
      final ECPoint cBlind = CURVE.getCurve().decodePoint(HexFormat.of().parseHex(sig.path("C_").asText()));
      final ECPoint c = cBlind.subtract(KEYS.get(keysetId).get(o.amount()).multiply(o.r())).normalize();
      final Map<String, Object> proof = new HashMap<>();
      proof.put("id", keysetId);
      proof.put("amount", o.amount());
      proof.put("secret", o.secret());
      proof.put("C", HexFormat.of().formatHex(c.getEncoded(true)));
      proofs.add(proof);
    }
    return proofs;
  }

  /** NUT-12 DLEQ on a blind signature: R1 = sG - eA, R2 = sB' - eC', e == H(R1,R2,A,C'). */
  private static boolean verifyDleq(final JsonNode sig, final Blinded out, final Map<Integer, ECPoint> keys) {
    try {
      final JsonNode dleq = sig.path("dleq");
      if (dleq.isMissingNode() || dleq.isNull()) {
        return false;
      }
      final BigInteger e = new BigInteger(dleq.path("e").asText(), 16);
      final BigInteger s = new BigInteger(dleq.path("s").asText(), 16);
      final ECPoint a = keys.get(sig.path("amount").asInt());
      final ECPoint cBlind = CURVE.getCurve().decodePoint(HexFormat.of().parseHex(sig.path("C_").asText()));
      final ECPoint r1 = CURVE.getG().multiply(s).subtract(a.multiply(e)).normalize();
      final ECPoint r2 = out.b().multiply(s).subtract(cBlind.multiply(e)).normalize();
      final String cat = hex(r1) + hex(r2) + hex(a) + hex(cBlind);
      final BigInteger h = new BigInteger(1, MessageDigest.getInstance("SHA-256")
          .digest(cat.getBytes(StandardCharsets.UTF_8))).mod(CURVE.getN());
      return h.equals(e);
    } catch (final Exception ex) {
      return false;
    }
  }

  private static String hex(final ECPoint p) {
    return HexFormat.of().formatHex(p.normalize().getEncoded(false));
  }
  private void assertStates(final List<Map<String, Object>> proofs, final String expected) {
    final List<String> ys = proofs.stream()
        .map(p -> HexFormat.of().formatHex(BDHKEUtils.hashToCurve((String) p.get("secret")))).toList();
    final JsonNode states = mintApiClient().post("/v1/checkstate", Map.of("Ys", ys)).getBody().path("states");
    assertThat(states.size()).isEqualTo(ys.size());
    for (final JsonNode st : states) {
      assertThat(st.path("state").asText()).as("proof state").isEqualTo(expected);
    }
  }

  private void assertSpent(final List<Map<String, Object>> proofs) {
    assertStates(proofs, "SPENT");
  }

  private void assertUnspent(final List<Map<String, Object>> proofs) {
    assertStates(proofs, "UNSPENT");
  }

  private static String firstActiveKeysetId(final JsonNode keysets) {
    for (final JsonNode keyset : keysets.path("items")) {
      if ("SIGNING".equals(keyset.path("state").asText()) && UNIT.equals(keyset.path("unit").asText())) {
        return keyset.path("keySetId").asText();
      }
    }
    throw new AssertionError("no SIGNING keyset: " + keysets);
  }

  private static Map<Integer, ECPoint> readKeys(final JsonNode body) {
    final JsonNode keys = body.path("keysets").get(0).path("keys");
    final Map<Integer, ECPoint> out = new HashMap<>();
    keys.fields().forEachRemaining(en -> out.put(Integer.parseInt(en.getKey()),
        CURVE.getCurve().decodePoint(HexFormat.of().parseHex(en.getValue().asText()))));
    return out;
  }

  private static String docker(final String... args) throws Exception {
    final List<String> cmd = new ArrayList<>();
    cmd.add("docker");
    cmd.addAll(List.of(args));
    final Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    final String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    p.waitFor();
    return out;
  }
}
