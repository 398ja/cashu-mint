package xyz.tcheeric.cashu.mint.rest.voucher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bouncycastle.util.encoders.Hex;
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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.util.SecretUtil;
import xyz.tcheeric.cashu.mint.jpa.repository.SwapHoldJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.SwapResponseCacheJpaRepository;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.rest.spec001.AbstractMintDurableIT;
import xyz.tcheeric.cashu.vault.api.VaultClientFactory;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import xyz.tcheeric.cashu.voucher.domain.VoucherDocuments;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

/**
 * A proof spent by one {@code POST /v1/swap} is refused when a second, different request presents
 * it again, with the proof store the mint really uses.
 *
 * <p>The voucher swap ITs mock {@code ProofVaultService} and {@code MintVaultService}, so they
 * cannot see a double spend: the mock claims whatever it is given. This class mocks neither. It
 * starts the real cashu-vault server as its own process on its own PostgreSQL, exactly as
 * {@code SpentProofCannotBeRevivedIT} does, and the mint's unmodified vault clients talk to it over
 * HTTP. What remains faked is only key material: the keyset is served from a fixed fixture whose
 * private key per denomination is the denomination, so a test can mint valid proofs offline.
 *
 * <p>Every replay uses fresh outputs, so it is a different request and the NUT-19 response cache
 * cannot answer it. The one identical replay is there to show the cache answers it without
 * signing anything new, which is not a second spend.
 */
@TestPropertySource(properties = {
    "cashu.mint.voucher.redemption-cap=enforce",
    "cashu.mint.voucher.issuer-binding=enforce",
    "cashu.mint.voucher.trusted-signers=" + VoucherRedemptionFixtures.ISSUER_PUB_X,
    "cashu.mint.voucher.redeemer-tokens=" + SwapReplayAgainstRealVaultIT.REDEEMER_TOKEN,
})
class SwapReplayAgainstRealVaultIT extends AbstractMintDurableIT {

  static final String REDEEMER_TOKEN = "it-real-vault-replay-redeemer-token";

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String KEYSET_ID = VoucherRedemptionFixtures.KEYSET_ID;
  private static final UUID MINT_UUID = UUID.fromString("00000001-0000-0000-0000-000000000064");
  /** NUT-00 {@code proof_already_used}. */
  private static final int PROOF_ALREADY_USED = 11001;
  private static final String VAULT_TOKEN = "it-vault-token-" + UUID.randomUUID();
  private static final Path VAULT_JAR = Path.of(
      System.getProperty("cashu.vault.server.jar", "target/cashu-vault-server.jar"));
  private static final Duration VAULT_STARTUP_TIMEOUT = Duration.ofSeconds(120);

  @SuppressWarnings("resource")
  private static final PostgreSQLContainer<?> VAULT_DB =
      new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("cashu_vault_replay_it");

  private static final Process VAULT_PROCESS;
  private static final String VAULT_BASE_URL;
  private static String originalVaultBaseUrl;
  private static MintProtocolService originalProtocolService;

  static {
    VAULT_DB.start();
    VAULT_BASE_URL = "http://localhost:" + freePort();
    System.setProperty("vault.api.token", VAULT_TOKEN);
    VAULT_PROCESS = startVault();
  }

  @Value("${local.server.port}")
  int port;

  @MockBean MintLoadService mintLoadService;

  @Autowired SwapResponseCacheJpaRepository swapResponseCache;
  @Autowired SwapHoldJpaRepository swapHolds;

  private final RestTemplate rest = new RestTemplate();

  @DynamicPropertySource
  static void vaultToken(DynamicPropertyRegistry registry) {
    registry.add("vault.api.token", () -> VAULT_TOKEN);
  }

  /**
   * The vault clients are static singletons that fix their base URL when first built, often by an
   * earlier IT in the same JVM, so the live instances are re-pointed here and restored after.
   */
  @BeforeAll
  static void pointTheMintAtThisVault() {
    originalVaultBaseUrl = VaultClientFactory.proofClient().getBaseUrl();
    VaultClientFactory.proofClient().setBaseUrl(VAULT_BASE_URL);
    VaultClientFactory.getClient(MintEntity.class).setBaseUrl(VAULT_BASE_URL);
  }

  @BeforeAll
  static void serveTheFixtureSigningKeys() throws Exception {
    originalProtocolService = MintProtocolServiceFactory.getInstance();
    Gateway gateway = Mockito.mock(Gateway.class);
    MintProtocolService keys = Mockito.spy(originalProtocolService);
    Mockito.doReturn(gateway).when(keys).createGateway(any(PaymentMethod.class));
    Mockito.doReturn(gateway).when(keys).createGateway(any(PaymentMethod.class), anyString());
    Mockito.doAnswer(call -> VoucherRedemptionFixtures.privateKeyFor(call.getArgument(1, Integer.class)))
        .when(keys).getPrivateKey(anyString(), anyInt(), any(Mint.class));
    Mockito.doAnswer(call -> VoucherRedemptionFixtures.privateKeyFor(call.getArgument(1, Integer.class)))
        .when(keys).getPrivateKeyForSigning(anyString(), anyInt(), any(Mint.class));
    MintProtocolServiceFactory.setInstance(keys);
  }

  @AfterAll
  static void stopVault() {
    MintProtocolServiceFactory.setInstance(originalProtocolService);
    VaultClientFactory.proofClient().setBaseUrl(originalVaultBaseUrl);
    VaultClientFactory.getClient(MintEntity.class).setBaseUrl(originalVaultBaseUrl);
    System.clearProperty("vault.api.token");
    VAULT_PROCESS.destroy();
    VAULT_DB.stop();
  }

  @BeforeEach
  void freshMint() throws Exception {
    swapResponseCache.deleteAllInBatch();
    swapHolds.deleteAllInBatch();
    serveFixtureKeySet();
    registerMintWithVault();
    ensureVaultKnowsKeyset(MINT_UUID, KEYSET_ID);
  }

  // ----------------------------------------------------------------------------- plain proofs

  @Test
  void aPlainProofSpentByOneSwapIsRefusedInASecondRequest() throws Exception {
    List<Map<String, Object>> inputs = VoucherRedemptionFixtures.plainProofs(8, 2);
    assertSwapped(swap(inputs, VoucherRedemptionFixtures.outputs(8, 2), null));
    long signedAfterFirst = blindSignatureJpaRepository.count();

    ResponseEntity<String> replay = swap(inputs, VoucherRedemptionFixtures.outputs(8, 2), null);

    assertRefusedAsSpent(replay);
    assertThat(blindSignatureJpaRepository.count()).as("a refused replay signs nothing")
        .isEqualTo(signedAfterFirst);
    assertThat(vaultStateOf(inputs.get(0))).isEqualTo("SPENT");
    assertThat(vaultStateOf(inputs.get(1))).isEqualTo("SPENT");
  }

  /**
   * One spent proof hidden among fresh ones sinks the whole swap, and the fresh proofs are left
   * spendable: the refusal does not burn them.
   */
  @Test
  void aSpentProofMixedWithFreshOnesRefusesTheSwapAndLeavesTheFreshOnesSpendable() throws Exception {
    List<Map<String, Object>> spent = VoucherRedemptionFixtures.plainProofs(4);
    assertSwapped(swap(spent, VoucherRedemptionFixtures.outputs(4), null));
    List<Map<String, Object>> fresh = VoucherRedemptionFixtures.plainProofs(8, 2);
    List<Map<String, Object>> mixed = new ArrayList<>(fresh);
    mixed.add(spent.get(0));

    assertRefusedAsSpent(swap(mixed, VoucherRedemptionFixtures.outputs(8, 4, 2), null));

    assertSwapped(swap(fresh, VoucherRedemptionFixtures.outputs(8, 2), null));
  }

  /** An identical replay is answered from the NUT-19 cache: same signatures, nothing new signed. */
  @Test
  void anIdenticalReplayIsServedFromTheCacheWithoutSpendingAgain() throws Exception {
    List<Map<String, Object>> inputs = VoucherRedemptionFixtures.plainProofs(8);
    List<Map<String, Object>> outputs = VoucherRedemptionFixtures.outputs(8);
    ResponseEntity<String> original = swap(inputs, outputs, null);
    assertSwapped(original);
    long signed = blindSignatureJpaRepository.count();

    ResponseEntity<String> replay = swap(inputs, outputs, null);

    assertSwapped(replay);
    assertThat(MAPPER.readTree(replay.getBody()).get("signatures"))
        .isEqualTo(MAPPER.readTree(original.getBody()).get("signatures"));
    assertThat(blindSignatureJpaRepository.count()).isEqualTo(signed);

    swapResponseCache.deleteAllInBatch();
    assertThat(swap(inputs, outputs, null).getStatusCode().is4xxClientError())
        .as("without its cache entry the same request is a second spend, and is refused").isTrue();
  }

  /** Two different requests racing for the same proof: exactly one is signed. */
  @Test
  void twoConcurrentSwapsOfOneProofSignExactlyOne() throws Exception {
    List<Map<String, Object>> inputs = VoucherRedemptionFixtures.plainProofs(8, 4);
    int racers = 6;
    List<ResponseEntity<String>> results = race(racers,
        () -> swap(inputs, VoucherRedemptionFixtures.outputs(8, 4), null));

    long accepted = results.stream().filter(r -> r.getStatusCode().is2xxSuccessful()).count();
    assertThat(accepted).as("bodies=%s", results.stream().map(ResponseEntity::getBody).toList())
        .isEqualTo(1);
    assertThat(results).filteredOn(r -> !r.getStatusCode().is2xxSuccessful())
        .allSatisfy(r -> assertThat(r.getStatusCode().is4xxClientError()).as("body=%s", r.getBody()).isTrue());
    assertThat(blindSignatureJpaRepository.count()).as("one swap's two outputs").isEqualTo(2);
    assertThat(vaultStateOf(inputs.get(0))).isEqualTo("SPENT");
  }

  // ---------------------------------------------------------------------------------- vouchers

  @Test
  void anInlineVoucherProofIsRefusedInASecondRequest() throws Exception {
    String blob = VoucherRedemptionFixtures.signedBlobHex(UUID.randomUUID().toString());
    List<Map<String, Object>> inputs = VoucherRedemptionFixtures.voucherProofs(blob, 8, 2);
    assertSwapped(swap(inputs, VoucherRedemptionFixtures.outputs(8, 2), null));

    assertRefusedAsSpent(swap(inputs, VoucherRedemptionFixtures.outputs(8, 2), null));
  }

  @Test
  void aByReferenceVoucherProofIsRefusedInASecondRequestCarryingItsDocument() throws Exception {
    String blob = VoucherRedemptionFixtures.signedBlobHex(UUID.randomUUID().toString());
    List<Map<String, Object>> inputs = referenceProofs(blob, 8, 1, 1);
    assertSwapped(swap(inputs, VoucherRedemptionFixtures.outputs(8, 2), Map.of(key(blob), blob)));
    long signedAfterFirst = blindSignatureJpaRepository.count();

    ResponseEntity<String> replay =
        swap(inputs, VoucherRedemptionFixtures.outputs(8, 2), Map.of(key(blob), blob));

    assertRefusedAsSpent(replay);
    assertThat(blindSignatureJpaRepository.count()).isEqualTo(signedAfterFirst);
    for (Map<String, Object> proof : inputs) {
      assertThat(vaultStateOf(proof)).isEqualTo("SPENT");
    }
  }

  /**
   * The same by-reference proof is refused when replayed in the other spelling's company: next to
   * fresh proofs of the same coupon, inline and by reference, in a later request.
   */
  @Test
  void aSpentByReferenceProofCannotRideAlongWithFreshProofsOfItsCoupon() throws Exception {
    String blob = VoucherRedemptionFixtures.signedBlobHex(UUID.randomUUID().toString());
    List<Map<String, Object>> first = referenceProofs(blob, 8);
    assertSwapped(swap(first, VoucherRedemptionFixtures.outputs(8), Map.of(key(blob), blob)));
    List<Map<String, Object>> second = new ArrayList<>(VoucherRedemptionFixtures.voucherProofs(blob, 2));
    second.addAll(referenceProofs(blob, 1));
    second.add(first.get(0));

    assertRefusedAsSpent(swap(second, VoucherRedemptionFixtures.outputs(8, 2, 1), Map.of(key(blob), blob)));
  }

  @Test
  void twoConcurrentSwapsOfOneByReferenceProofSignExactlyOne() throws Exception {
    String blob = VoucherRedemptionFixtures.signedBlobHex(UUID.randomUUID().toString());
    List<Map<String, Object>> inputs = referenceProofs(blob, 8, 2);
    List<ResponseEntity<String>> results = race(6,
        () -> swap(inputs, VoucherRedemptionFixtures.outputs(8, 2), Map.of(key(blob), blob)));

    assertThat(results.stream().filter(r -> r.getStatusCode().is2xxSuccessful()).count())
        .as("bodies=%s", results.stream().map(ResponseEntity::getBody).toList())
        .isEqualTo(1);
    assertThat(blindSignatureJpaRepository.count()).isEqualTo(2);
  }

  // ----------------------------------------------------------------------------------- helpers

  private static void assertSwapped(ResponseEntity<String> response) {
    assertThat(response.getStatusCode().is2xxSuccessful()).as("body=%s", response.getBody()).isTrue();
  }

  private static void assertRefusedAsSpent(ResponseEntity<String> response) {
    assertThat(response.getStatusCode().is4xxClientError()).as("body=%s", response.getBody()).isTrue();
    assertThat(response.getBody()).as("refused as already spent")
        .contains("\"code\":" + PROOF_ALREADY_USED);
  }

  private static List<ResponseEntity<String>> race(int racers, Callable<ResponseEntity<String>> swap)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(racers);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
      for (int i = 0; i < racers; i++) {
        futures.add(pool.submit(() -> {
          start.await();
          return swap.call();
        }));
      }
      start.countDown();
      List<ResponseEntity<String>> results = new ArrayList<>();
      for (Future<ResponseEntity<String>> future : futures) {
        results.add(future.get());
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  private static String key(String documentHex) {
    return VoucherDocuments.key(Hex.decode(documentHex));
  }

  private static List<Map<String, Object>> referenceProofs(String documentHex, int... amounts) {
    String reference = key(documentHex);
    List<Map<String, Object>> proofs = new ArrayList<>();
    for (int amount : amounts) {
      String secret = "[\"VOUCHER\",{\"nonce\":\"" + UUID.randomUUID().toString().replace("-", "")
          + "\",\"data\":\"" + reference + "\",\"tags\":[]}]";
      proofs.add(VoucherRedemptionFixtures.proof(amount, secret));
    }
    return proofs;
  }

  private ResponseEntity<String> swap(List<Map<String, Object>> inputs, List<Map<String, Object>> outputs,
                                      Map<String, String> vouchers) throws IOException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("inputs", inputs);
    body.put("outputs", outputs);
    if (vouchers != null) {
      body.put("vouchers", vouchers);
    }
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    try {
      return rest.postForEntity("http://localhost:" + port + "/v1/swap",
          new HttpEntity<>(MAPPER.writeValueAsString(body), headers), String.class);
    } catch (HttpStatusCodeException e) {
      return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
    }
  }

  private void serveFixtureKeySet() throws Exception {
    Mint mint = new Mint(MINT_UUID.toString());
    Keys keys = new Keys();
    for (int amount : new int[] {1, 2, 4, 8, 16}) {
      keys.put(BigInteger.valueOf(amount),
          xyz.tcheeric.cashu.common.PrivateKey.derivePublicKey(VoucherRedemptionFixtures.privateKeyFor(amount)));
    }
    mint.addKeySet(KeySet.builder().id(KEYSET_ID).unit("sat").keys(keys).build());
    KeySet keySet = mint.getKeySets().iterator().next();
    when(mintLoadService.load(any(UUID.class), anyBoolean())).thenReturn(mint);
    when(mintLoadService.load(anyBoolean())).thenReturn(List.of(mint));
    when(mintLoadService.keySet(anyString())).thenReturn(keySet);
    when(mintLoadService.keySets()).thenReturn(List.of(keySet));
    when(mintLoadService.keySets(anyBoolean()))
        .thenAnswer(call -> call.getArgument(0, Boolean.class) ? List.of() : List.of(keySet));
  }

  private void registerMintWithVault() throws IOException {
    HttpStatusCode status = vaultCall(HttpMethod.POST, "/vault/mint", Map.of("id", MINT_UUID.toString()));
    assertThat(status.is2xxSuccessful() || status.value() == 409)
        .as("registering the mint with the vault answered %s", status).isTrue();
  }

  /**
   * Registers the fixture keyset with the vault, once. Since cashu-vault 0.16.0 the vault refuses a
   * proof whose keyset is not one of its mint's (cashu-vault#156), and a production mint always
   * stores its keysets there first, so a fixture that skips this tests a mint that cannot exist.
   */
  private void ensureVaultKnowsKeyset(UUID mintId, String keysetId) throws IOException {
    String known;
    try {
      known = rest.exchange(VAULT_BASE_URL + "/vault/keyset/mint/" + mintId,
          HttpMethod.GET, new HttpEntity<>(vaultHeaders()), String.class).getBody();
    } catch (HttpStatusCodeException e) {
      known = null; // 404: the mint has no keysets yet
    }
    if (known != null && known.contains("\"" + keysetId + "\"")) {
      return;
    }
    HttpStatusCode status = vaultCall(HttpMethod.POST, "/vault/keyset", Map.of(
        "keySetId", keysetId, "unit", "sat", "input_fee_ppk", 0,
        "mint", Map.of("id", mintId.toString())));
    assertThat(status.is2xxSuccessful())
        .as("registering keyset %s with the vault answered %s", keysetId, status).isTrue();
  }

  @SuppressWarnings("unchecked")
  private String vaultStateOf(Map<String, Object> proof) throws IOException {
    String y = SecretUtil.toYFromString((String) proof.get("secret"));
    ResponseEntity<String> response = rest.exchange(
        VAULT_BASE_URL + "/vault/proof/mint/" + MINT_UUID + "/secret/" + y,
        HttpMethod.GET, new HttpEntity<>(vaultHeaders()), String.class);
    assertThat(response.getBody()).as("the vault has no row for this proof").isNotBlank();
    return (String) MAPPER.readValue(response.getBody(), Map.class).get("state");
  }

  private HttpStatusCode vaultCall(HttpMethod method, String path, Object body) throws IOException {
    HttpHeaders headers = vaultHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    try {
      return rest.exchange(VAULT_BASE_URL + path, method,
          new HttpEntity<>(MAPPER.writeValueAsString(body), headers), String.class).getStatusCode();
    } catch (HttpStatusCodeException e) {
      return e.getStatusCode();
    }
  }

  private static HttpHeaders vaultHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(VAULT_TOKEN);
    return headers;
  }

  private static Process startVault() {
    if (!Files.isRegularFile(VAULT_JAR)) {
      throw new IllegalStateException(VAULT_JAR.toAbsolutePath() + " is missing. It is copied by "
          + "maven-dependency-plugin under -Pintegration-tests; run this IT through Maven.");
    }
    String vaultPort = VAULT_BASE_URL.substring(VAULT_BASE_URL.lastIndexOf(':') + 1);
    ProcessBuilder builder = new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-jar", VAULT_JAR.toAbsolutePath().toString())
        .redirectErrorStream(true)
        .redirectOutput(Path.of("target", "cashu-vault-server-replay.log").toFile());
    Map<String, String> env = builder.environment();
    env.put("SPRING_PROFILES_ACTIVE", "prod");
    env.put("DATABASE_URL", VAULT_DB.getJdbcUrl());
    env.put("DATABASE_USER", VAULT_DB.getUsername());
    env.put("DATABASE_PASSWORD", VAULT_DB.getPassword());
    env.put("VAULT_API_TOKEN", VAULT_TOKEN);
    env.put("VAULT_BACKEND", "jpa");
    env.put("cashu_vault_port", vaultPort);
    try {
      Process process = builder.start();
      awaitHealthy(process);
      return process;
    } catch (IOException e) {
      throw new IllegalStateException("could not start the cashu-vault server", e);
    }
  }

  private static void awaitHealthy(Process process) {
    RestTemplate probe = new RestTemplate();
    Instant deadline = Instant.now().plus(VAULT_STARTUP_TIMEOUT);
    while (Instant.now().isBefore(deadline)) {
      if (!process.isAlive()) {
        throw new IllegalStateException("cashu-vault exited during startup; see "
            + "target/cashu-vault-server-replay.log");
      }
      try {
        if (probe.getForEntity(VAULT_BASE_URL + "/actuator/health", String.class)
            .getStatusCode().is2xxSuccessful()) {
          return;
        }
      } catch (RuntimeException notYetUp) {
        try {
          Thread.sleep(500);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
    }
    process.destroy();
    throw new IllegalStateException("cashu-vault did not become healthy within " + VAULT_STARTUP_TIMEOUT);
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
