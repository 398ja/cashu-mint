package xyz.tcheeric.cashu.mint.rest.keyset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.mint.jpa.repository.KeysetRedemptionCutoffHistoryJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.KeysetRedemptionCutoffJpaRepository;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.rest.spec001.AbstractMintDurableIT;
import xyz.tcheeric.cashu.mint.rest.support.ProofVaultStubs;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

/**
 * A retired keyset stops redeeming at its cutoff (cashu-mint#551), end to end over HTTP against
 * the durable cutoff record in Postgres.
 *
 * <p>The mint holds an active keyset and an archived one. The cutoff is set through the mint's own
 * {@code /admin} endpoint (as mint-admin does after approval), then moved into the past directly in
 * the table, because the endpoint rightly refuses a cutoff that is not in the future.
 */
class KeysetRedemptionCutoffIT extends AbstractMintDurableIT {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String ACTIVE = "00f034be00000001";
  private static final String RETIRED = "00ef5b8300000002";
  private static final UUID MINT_UUID = UUID.fromString("00000001-0000-0000-0000-000000000001");

  @Value("${local.server.port}")
  int port;

  @MockBean MintLoadService mintLoadService;
  @MockBean ProofVaultService proofVaultService;
  @MockBean MintVaultService mintVaultService;

  @Autowired KeysetRedemptionCutoffJpaRepository cutoffs;
  @Autowired KeysetRedemptionCutoffHistoryJpaRepository history;
  @Autowired MeterRegistry meterRegistry;
  /** Observability is off in this context, so bind the real recorder to its registry ourselves. */

  private final RestTemplate restTemplate = new RestTemplate();
  private static MintProtocolService originalProtocolService;

  @BeforeAll
  static void stubKeys() throws Exception {
    originalProtocolService = MintProtocolServiceFactory.getInstance();
    Gateway gateway = Mockito.mock(Gateway.class);
    MintProtocolService stub = Mockito.spy(originalProtocolService);
    Mockito.doReturn(gateway).when(stub).createGateway(any(PaymentMethod.class));
    Mockito.doReturn(gateway).when(stub).createGateway(any(PaymentMethod.class), anyString());
    Mockito.doAnswer(inv -> privateKeyFor(inv.getArgument(1, Integer.class)))
        .when(stub).getPrivateKey(anyString(), anyInt(), any(Mint.class));
    Mockito.doAnswer(inv -> privateKeyFor(inv.getArgument(1, Integer.class)))
        .when(stub).getPrivateKeyForSigning(anyString(), anyInt(), any(Mint.class));
    MintProtocolServiceFactory.setInstance(stub);
  }

  @AfterAll
  static void restore() {
    MintProtocolServiceFactory.setInstance(originalProtocolService);
  }

  @BeforeEach
  void wireKeysets() throws Exception {
    xyz.tcheeric.cashu.mint.proto.metrics.MetricRecorders.registerKeysetRedemption(
        new xyz.tcheeric.cashu.mint.observability.metrics.MicrometerKeysetRedemptionMetricsRecorder(meterRegistry));
    history.deleteAllInBatch();
    cutoffs.deleteAllInBatch();
    KeySet active = keySet(ACTIVE);
    KeySet retired = keySet(RETIRED);
    Mint mint = new Mint(MINT_UUID.toString());
    mint.addKeySet(active);
    mint.addKeySet(retired);
    when(mintLoadService.load(any(UUID.class), anyBoolean())).thenReturn(mint);
    when(mintLoadService.load(anyBoolean())).thenReturn(List.of(mint));
    when(mintLoadService.keySet(ACTIVE)).thenReturn(active);
    when(mintLoadService.keySet(RETIRED)).thenReturn(retired);
    when(mintLoadService.keySets()).thenReturn(List.of(active, retired));
    when(mintLoadService.keySets(anyBoolean()))
        .thenAnswer(inv -> inv.getArgument(0, Boolean.class) ? List.of(retired) : List.of(active));

    MintEntity mintEntity = new MintEntity();
    mintEntity.setId(MINT_UUID);
    when(mintVaultService.retrieveMint(anyString())).thenReturn(mintEntity);
    ProofVaultStubs.keyProofsByIssuanceKey(proofVaultService);
    Map<String, Integer> held = new java.util.concurrent.ConcurrentHashMap<>();
    when(proofVaultService.insertOrClaimForHold(any(), anyString(), any(UUID.class)))
        .thenAnswer(inv -> {
          int claimed = inv.getArgument(0, List.class).size();
          held.put(inv.getArgument(1, String.class), claimed);
          return claimed;
        });
    when(proofVaultService.commitSpentForHold(anyString()))
        .thenAnswer(inv -> held.getOrDefault(inv.getArgument(0, String.class), 0));
  }

  @Test
  void aProofOnTheRetiredKeysetRedeemsBeforeTheCutoffAndIsRefusedAfterIt() throws Exception {
    ResponseEntity<String> set = putCutoff(RETIRED, Instant.now().plus(Duration.ofDays(14)));
    assertThat(set.getStatusCode().value()).as("body=%s", set.getBody()).isEqualTo(200);
    assertThat(history.findByKeysetIdOrderBySetAtAscIdAsc(RETIRED)).hasSize(1);

    ResponseEntity<String> before = postSwap(RETIRED);
    assertThat(before.getStatusCode().is2xxSuccessful())
        .as("before the cutoff an archived keyset still redeems — body=%s", before.getBody()).isTrue();
    JsonNode listedBefore = keysetById(RETIRED);
    assertThat(listedBefore).as("still advertised before the cutoff").isNotNull();
    assertThat(listedBefore.get("active").asBoolean()).isFalse();
    assertThat(listedBefore.get("final_expiry").asLong()).isPositive();

    moveCutoffIntoThePast(RETIRED);
    double refusedBefore = refusedCount(RETIRED);

    ResponseEntity<String> after = postSwap(RETIRED);
    assertThat(after.getStatusCode().value()).isEqualTo(400);
    assertThat(after.getBody()).contains("\"code\":12003");
    assertThat(refusedCount(RETIRED)).isEqualTo(refusedBefore + 1);
    assertThat(keysetById(RETIRED)).as("a retired keyset is no longer advertised").isNull();
    assertThat(keysById(RETIRED)).as("nor are its keys").isNull();
  }

  @Test
  void theActiveKeysetIsNeverAffected() throws Exception {
    putCutoff(RETIRED, Instant.now().plus(Duration.ofDays(1)));
    moveCutoffIntoThePast(RETIRED);

    ResponseEntity<String> swap = postSwap(ACTIVE);
    assertThat(swap.getStatusCode().is2xxSuccessful()).as("body=%s", swap.getBody()).isTrue();
    assertThat(keysetById(ACTIVE).get("active").asBoolean()).isTrue();
  }

  @Test
  void aCutoffCannotBeSetOnTheActiveKeyset() {
    ResponseEntity<String> response = putCutoff(ACTIVE, Instant.now().plus(Duration.ofDays(14)));

    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getBody()).contains("keyset_active");
    assertThat(cutoffs.findById(ACTIVE)).isEmpty();
  }

  @Test
  void aCutoffEndpointRequiresTheAdminRole() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<String> response = exchange("/admin/keysets/" + RETIRED + "/redemption-cutoff",
        HttpMethod.PUT, new HttpEntity<>("{\"redeemUntil\":\"2099-01-01T00:00:00Z\"}", headers));

    assertThat(response.getStatusCode().value()).isEqualTo(401);
  }

  private void moveCutoffIntoThePast(String keysetId) {
    var row = cutoffs.findById(keysetId).orElseThrow();
    row.setRedeemUntil(Instant.now().minusSeconds(1));
    cutoffs.saveAndFlush(row);
  }

  private double refusedCount(String keysetId) {
    var counter = meterRegistry.find("cashu_mint_keyset_redemption_refused_total")
        .tags("keyset", keysetId, "operation", "swap").counter();
    return counter == null ? 0 : counter.count();
  }

  private ResponseEntity<String> putCutoff(String keysetId, Instant redeemUntil) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBasicAuth("admin-it", "it-admin-password", StandardCharsets.UTF_8);
    String body = "{\"redeemUntil\":\"" + redeemUntil + "\",\"reason\":\"it\",\"setBy\":\"it-operator\"}";
    return exchange("/admin/keysets/" + keysetId + "/redemption-cutoff", HttpMethod.PUT,
        new HttpEntity<>(body, headers));
  }

  private JsonNode keysetById(String keysetId) throws Exception {
    JsonNode keysets = MAPPER.readTree(exchange("/v1/keysets", HttpMethod.GET, null).getBody()).get("keysets");
    for (JsonNode keyset : keysets) {
      if (keysetId.equals(keyset.get("id").asText())) {
        return keyset;
      }
    }
    return null;
  }

  private JsonNode keysById(String keysetId) throws Exception {
    JsonNode keysets = MAPPER.readTree(exchange("/v1/keys", HttpMethod.GET, null).getBody()).get("keysets");
    for (JsonNode keyset : keysets) {
      if (keysetId.equals(keyset.get("id").asText())) {
        return keyset;
      }
    }
    return null;
  }

  private ResponseEntity<String> postSwap(String inputKeysetId) throws Exception {
    List<Map<String, Object>> inputs = new ArrayList<>(List.of(proofJson(8, inputKeysetId)));
    List<Map<String, Object>> outputs = List.of(blindedMessageJson(8, ACTIVE));
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    String body = MAPPER.writeValueAsString(Map.of("inputs", inputs, "outputs", outputs));
    return exchange("/v1/swap", HttpMethod.POST, new HttpEntity<>(body, headers));
  }

  private ResponseEntity<String> exchange(String path, HttpMethod method, HttpEntity<String> entity) {
    try {
      return restTemplate.exchange("http://localhost:" + port + path, method, entity, String.class);
    } catch (org.springframework.web.client.HttpStatusCodeException e) {
      return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
    }
  }

  private static KeySet keySet(String id) {
    Keys keys = new Keys();
    for (int amount : new int[] {1, 2, 4, 8, 16}) {
      keys.put(BigInteger.valueOf(amount), PrivateKey.derivePublicKey(privateKeyFor(amount)));
    }
    return KeySet.builder().id(id).unit("sat").keys(keys).build();
  }

  private static PrivateKey privateKeyFor(int amount) {
    return PrivateKey.fromString(String.format("%064x", BigInteger.valueOf(amount)));
  }

  private static Map<String, Object> proofJson(int amount, String keysetId) {
    String secret = UUID.randomUUID().toString().replace("-", "").repeat(2);
    byte[] c = BDHKEUtils.signBlindedMessage(BDHKEUtils.hashToCurve(secret), privateKeyFor(amount).toBytes());
    return Map.of("amount", amount, "id", keysetId, "secret", secret, "C", java.util.HexFormat.of().formatHex(c));
  }

  private static Map<String, Object> blindedMessageJson(int amount, String keysetId) {
    BigInteger scalar = new BigInteger(1, UUID.randomUUID().toString().getBytes())
        .mod(BigInteger.valueOf(Long.MAX_VALUE)).add(BigInteger.valueOf(1024));
    PrivateKey blindingKey = PrivateKey.fromString(String.format("%064x", scalar));
    return Map.of("amount", amount, "id", keysetId, "B_", PrivateKey.derivePublicKey(blindingKey).toString());
  }
}
