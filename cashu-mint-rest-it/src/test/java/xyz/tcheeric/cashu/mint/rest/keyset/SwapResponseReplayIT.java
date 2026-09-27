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
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.mint.jpa.SwapResponseCachePurger;
import xyz.tcheeric.cashu.mint.jpa.entity.SwapHoldEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.SwapResponseCacheEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.SwapHoldJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.SwapResponseCacheJpaRepository;
import xyz.tcheeric.cashu.mint.proto.domain.SwapHoldPhase;
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
 * Issue #482 — {@code POST /v1/swap} is a NUT-19 cached endpoint, end to end.
 *
 * <p>Drives the real route against Postgres with the durable signature vault, which refuses a
 * blinded message it has already signed. That is what makes these assertions mean something: a
 * replay that comes back 200 can only have been answered from the cache, because the same request
 * processed again is refused with {@code outputs_already_signed}. The last test proves exactly
 * that by removing the cache entry and replaying.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/19.md">NUT-19</a>
 */
class SwapResponseReplayIT extends AbstractMintDurableIT {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String KEYSET_ID = "004cf8cba2f93266";
  private static final UUID MINT_UUID = UUID.fromString("00000001-0000-0000-0000-000000000001");

  /** NUT-00 wire code of {@code outputs_already_signed}. */
  private static final int OUTPUTS_ALREADY_SIGNED = 11003;

  @Value("${local.server.port}")
  int port;

  @MockBean MintLoadService mintLoadService;

  @MockBean ProofVaultService proofVaultService;

  @MockBean MintVaultService mintVaultService;

  @Autowired SwapResponseCacheJpaRepository swapResponseCache;

  @Autowired SwapResponseCachePurger purger;

  @Autowired SwapHoldJpaRepository swapHolds;

  private final RestTemplate restTemplate = new RestTemplate();

  private static MintProtocolService originalProtocolService;

  @BeforeAll
  static void stubSigningKeys() throws Exception {
    originalProtocolService = MintProtocolServiceFactory.getInstance();
    Gateway gateway = Mockito.mock(Gateway.class);
    MintProtocolService stub = Mockito.spy(originalProtocolService);
    Mockito.doReturn(gateway).when(stub).createGateway(any(PaymentMethod.class));
    Mockito.doReturn(gateway).when(stub).createGateway(any(PaymentMethod.class), anyString());
    // The keyset's private key for a denomination is that denomination as a scalar, so a test
    // can build proofs the mint's BDHKE verification accepts.
    Mockito.doAnswer(invocation -> privateKeyFor(invocation.getArgument(1, Integer.class)))
        .when(stub)
        .getPrivateKey(anyString(), anyInt(), any(Mint.class));
    Mockito.doAnswer(invocation -> privateKeyFor(invocation.getArgument(1, Integer.class)))
        .when(stub)
        .getPrivateKeyForSigning(anyString(), anyInt(), any(Mint.class));
    MintProtocolServiceFactory.setInstance(stub);
  }

  @AfterAll
  static void restoreProtocolService() {
    MintProtocolServiceFactory.setInstance(originalProtocolService);
  }

  @BeforeEach
  void serveAFreeKeySetAndAClaimingVault() throws Exception {
    swapResponseCache.deleteAllInBatch();
    swapHolds.deleteAllInBatch();
    wireFreeKeySet();
    stubVault();
  }

  // Acceptance: a replayed /v1/swap with an identical request returns the same blind signatures
  // rather than outputs_already_signed, and signs nothing further.
  @Test
  void anIdenticalReplayReturnsTheSameSignatures() throws Exception {
    String request = swapRequest(List.of(proofJson(8), proofJson(8)),
        List.of(blindedMessageJson(8), blindedMessageJson(8)));

    ResponseEntity<String> original = postSwap(request);
    long signedByTheOriginal = blindSignatureJpaRepository.count();
    ResponseEntity<String> replay = postSwap(request);

    assertThat(original.getStatusCode().is2xxSuccessful()).as("body=%s", original.getBody()).isTrue();
    assertThat(replay.getStatusCode().is2xxSuccessful())
        .as("a replay of a cached swap must succeed — body=%s", replay.getBody())
        .isTrue();
    assertThat(MAPPER.readTree(replay.getBody()).get("signatures"))
        .as("the replay carries the signatures the original swap issued")
        .isEqualTo(MAPPER.readTree(original.getBody()).get("signatures"));
    assertThat(blindSignatureJpaRepository.count())
        .as("a replay is answered from the cache, not signed again")
        .isEqualTo(signedByTheOriginal);
  }

  // Acceptance: two swaps with identical outputs but different inputs get different responses.
  // The second is processed, and refused because its outputs are already signed, rather than
  // being handed the first swap's signatures.
  @Test
  void aSwapWithTheSameOutputsButDifferentInputsIsProcessedNotReplayed() throws Exception {
    List<Map<String, Object>> outputs = List.of(blindedMessageJson(8), blindedMessageJson(8));

    ResponseEntity<String> first = postSwap(swapRequest(List.of(proofJson(8), proofJson(8)), outputs));
    ResponseEntity<String> second = postSwap(swapRequest(List.of(proofJson(8), proofJson(8)), outputs));

    assertThat(first.getStatusCode().is2xxSuccessful()).as("body=%s", first.getBody()).isTrue();
    assertThat(second.getStatusCode().is4xxClientError())
        .as("different inputs are a different swap — body=%s", second.getBody())
        .isTrue();
    assertThat(second.getBody()).contains("\"code\":" + OUTPUTS_ALREADY_SIGNED);
    assertThat(swapResponseCache.count()).as("only the successful swap is cached").isOne();
  }

  // Acceptance: /v1/swap appears in GetInfoResponse.nuts["19"].cached_endpoints.
  @Test
  void theInfoEndpointListsSwapAsCached() throws Exception {
    ResponseEntity<String> info =
        restTemplate.getForEntity("http://localhost:" + port + "/v1/info", String.class);

    JsonNode cached = MAPPER.readTree(info.getBody()).get("nuts").get("19").get("cached_endpoints");
    assertThat(cached.findValuesAsText("path")).contains("/v1/swap");
  }

  // The replay really comes from the cache: remove the entry and the same request is processed
  // again, which the durable vault refuses. This is the guard against a replay test passing for
  // some reason other than the cache.
  @Test
  void withoutItsCacheEntryAReplayIsRefused() throws Exception {
    String request = swapRequest(List.of(proofJson(8)), List.of(blindedMessageJson(8)));
    assertThat(postSwap(request).getStatusCode().is2xxSuccessful()).isTrue();

    swapResponseCache.deleteAllInBatch();
    ResponseEntity<String> replay = postSwap(request);

    assertThat(replay.getStatusCode().is4xxClientError()).as("body=%s", replay.getBody()).isTrue();
  }

  // An entry past its ttl is not replayed, whether or not the purge has run yet, and the purge
  // then removes it.
  @Test
  void anExpiredEntryIsNotReplayedAndIsPurged() throws Exception {
    String request = swapRequest(List.of(proofJson(8)), List.of(blindedMessageJson(8)));
    assertThat(postSwap(request).getStatusCode().is2xxSuccessful()).isTrue();
    expireEveryCachedResponse();

    ResponseEntity<String> replay = postSwap(request);
    purger.purgeExpired();

    assertThat(replay.getStatusCode().is4xxClientError())
        .as("an expired entry must not be replayed — body=%s", replay.getBody())
        .isTrue();
    assertThat(swapResponseCache.count()).as("the purge removes expired entries").isZero();
  }

  // Issue #519: the replay reaches this instance while its original is still signing on another.
  // Simulated by putting the original's real hold back into SIGNING and storing its response only
  // after the replay has arrived. The replay waits for it instead of failing.
  @Test
  void aReplayRacingItsOriginalOnAnotherInstanceGetsTheOriginalsResponse() throws Exception {
    String request = swapRequest(List.of(proofJson(8)), List.of(blindedMessageJson(8)));
    ResponseEntity<String> original = postSwap(request);
    assertThat(original.getStatusCode().is2xxSuccessful()).as("body=%s", original.getBody()).isTrue();
    SwapResponseCacheEntity stored = swapResponseCache.findAll().get(0);
    putTheOriginalBackInFlight(stored.getRequestFingerprint());
    swapResponseCache.deleteAllInBatch();

    CompletableFuture<Void> originalFinishes = CompletableFuture.runAsync(
        () -> restore(stored), CompletableFuture.delayedExecutor(700, TimeUnit.MILLISECONDS));
    ResponseEntity<String> replay = postSwap(request);
    originalFinishes.join();

    assertThat(replay.getStatusCode().is2xxSuccessful())
        .as("a replay racing its in-flight original must get its response — body=%s", replay.getBody())
        .isTrue();
    assertThat(MAPPER.readTree(replay.getBody()).get("signatures"))
        .isEqualTo(MAPPER.readTree(original.getBody()).get("signatures"));
  }

  // Issue #519: a different request is not kept waiting because some other swap is in flight.
  // It asks for the same outputs as an in-flight swap, is refused, and is refused promptly.
  @Test
  void aDifferentRequestIsRefusedWithoutWaitingForAnInFlightSwap() throws Exception {
    List<Map<String, Object>> outputs = List.of(blindedMessageJson(8));
    assertThat(postSwap(swapRequest(List.of(proofJson(8)), outputs)).getStatusCode().is2xxSuccessful())
        .isTrue();
    putTheOriginalBackInFlight(swapResponseCache.findAll().get(0).getRequestFingerprint());

    long started = System.nanoTime();
    ResponseEntity<String> different = postSwap(swapRequest(List.of(proofJson(8)), outputs));
    Duration took = Duration.ofNanos(System.nanoTime() - started);

    assertThat(different.getBody()).contains("\"code\":" + OUTPUTS_ALREADY_SIGNED);
    assertThat(took).as("nothing of this request is in flight, so it must not wait")
        .isLessThan(Duration.ofSeconds(1));
  }

  /** Makes the hold taken by the given request look like a swap still signing elsewhere. */
  private void putTheOriginalBackInFlight(String requestFingerprint) {
    List<SwapHoldEntity> holds = swapHolds.findByRequestFingerprint(requestFingerprint);
    assertThat(holds).as("the swap recorded its hold under its request fingerprint").hasSize(1);
    SwapHoldEntity hold = holds.get(0);
    hold.setPhase(SwapHoldPhase.SIGNING);
    hold.setUpdatedAt(Instant.now());
    swapHolds.saveAndFlush(hold);
  }

  private void restore(SwapResponseCacheEntity stored) {
    SwapResponseCacheEntity again = new SwapResponseCacheEntity();
    again.setRequestFingerprint(stored.getRequestFingerprint());
    again.setResponseJson(stored.getResponseJson());
    again.setCreatedAt(Instant.now());
    again.setExpiresAt(Instant.now().plusSeconds(900));
    swapResponseCache.saveAndFlush(again);
  }

  private void expireEveryCachedResponse() {
    Instant past = Instant.now().minusSeconds(3600);
    for (SwapResponseCacheEntity entry : swapResponseCache.findAll()) {
      SwapResponseCacheEntity expired = new SwapResponseCacheEntity();
      expired.setRequestFingerprint(entry.getRequestFingerprint());
      expired.setResponseJson(entry.getResponseJson());
      expired.setCreatedAt(past);
      expired.setExpiresAt(past.plusSeconds(1));
      swapResponseCache.deleteById(entry.getRequestFingerprint());
      swapResponseCache.saveAndFlush(expired);
    }
  }

  /**
   * Makes the vault report every submitted input as claimed, and commit exactly what the same
   * hold claimed, so a swap completes without a live cashu-vault.
   */
  private void stubVault() throws Exception {
    MintEntity mintEntity = new MintEntity();
    mintEntity.setId(MINT_UUID);
    when(mintVaultService.retrieveMint(anyString())).thenReturn(mintEntity);
    ProofVaultStubs.keyProofsByIssuanceKey(proofVaultService);
    Map<String, Integer> heldByHold = new ConcurrentHashMap<>();
    when(proofVaultService.insertOrClaimForHold(any(), anyString(), any(UUID.class)))
        .thenAnswer(invocation -> {
          int claimed = invocation.getArgument(0, List.class).size();
          heldByHold.put(invocation.getArgument(1, String.class), claimed);
          return claimed;
        });
    when(proofVaultService.commitSpentForHold(anyString()))
        .thenAnswer(invocation -> heldByHold.getOrDefault(invocation.getArgument(0, String.class), 0));
  }

  private void wireFreeKeySet() throws Exception {
    Mint mint = new Mint(MINT_UUID.toString());
    Keys keys = new Keys();
    for (int amount : new int[] {1, 2, 4, 8, 16}) {
      keys.put(BigInteger.valueOf(amount), PrivateKey.derivePublicKey(privateKeyFor(amount)));
    }
    mint.addKeySet(KeySet.builder().id(KEYSET_ID).unit("sat").keys(keys).build());
    KeySet keySet = mint.getKeySets().iterator().next();
    when(mintLoadService.load(any(UUID.class), anyBoolean())).thenReturn(mint);
    when(mintLoadService.load(anyBoolean())).thenReturn(List.of(mint));
    when(mintLoadService.keySet(anyString())).thenReturn(keySet);
    when(mintLoadService.keySets()).thenReturn(List.of(keySet));
    when(mintLoadService.keySets(anyBoolean()))
        .thenAnswer(invocation -> invocation.getArgument(0, Boolean.class)
            ? List.of()
            : List.of(keySet));
  }

  private static PrivateKey privateKeyFor(final int amount) {
    return PrivateKey.fromString(String.format("%064x", BigInteger.valueOf(amount)));
  }

  /** A proof the mint's BDHKE verification accepts, with a fresh secret per call. */
  private static Map<String, Object> proofJson(final int amount) {
    String secret = UUID.randomUUID().toString().replace("-", "").repeat(2);
    byte[] y = BDHKEUtils.hashToCurve(secret);
    byte[] c = BDHKEUtils.signBlindedMessage(y, privateKeyFor(amount).toBytes());
    return Map.of("amount", amount, "id", KEYSET_ID, "secret", secret, "C", HexFormat.of().formatHex(c));
  }

  /** A distinct curve point per call, so no two outputs collide unless a test reuses one. */
  private static Map<String, Object> blindedMessageJson(final int amount) {
    BigInteger scalar = new BigInteger(1, UUID.randomUUID().toString().getBytes())
        .mod(BigInteger.valueOf(Long.MAX_VALUE)).add(BigInteger.valueOf(1024));
    PrivateKey blindingKey = PrivateKey.fromString(String.format("%064x", scalar));
    return Map.of("amount", amount, "id", KEYSET_ID, "B_", PrivateKey.derivePublicKey(blindingKey).toString());
  }

  private static String swapRequest(List<Map<String, Object>> inputs, List<Map<String, Object>> outputs)
      throws Exception {
    return MAPPER.writeValueAsString(Map.of("inputs", inputs, "outputs", outputs));
  }

  private ResponseEntity<String> postSwap(String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    try {
      return restTemplate.postForEntity(
          "http://localhost:" + port + "/v1/swap", new HttpEntity<>(body, headers), String.class);
    } catch (HttpStatusCodeException e) {
      return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
    }
  }
}
