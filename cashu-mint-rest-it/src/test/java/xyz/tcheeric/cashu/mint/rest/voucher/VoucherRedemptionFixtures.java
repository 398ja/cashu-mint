package xyz.tcheeric.cashu.mint.rest.voucher;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.crypto.Schnorr;
import xyz.tcheeric.cashu.mint.jpa.repository.SwapHoldJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.SwapResponseCacheJpaRepository;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.rest.spec001.AbstractMintDurableIT;
import xyz.tcheeric.cashu.mint.rest.support.ProofVaultStubs;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import xyz.tcheeric.cashu.voucher.domain.BackingStrategy;
import xyz.tcheeric.cashu.voucher.domain.VoucherSignatureService;
import xyz.tcheeric.cashu.voucher.domain.util.VoucherSerializationUtils;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

/**
 * Shared fixture for the cashu-mint#540 ITs: the real {@code POST /v1/swap} against Postgres, a
 * stubbed keyset whose key per denomination is the denomination, and real gateway-signed
 * unlocked voucher blobs in the shape gateway-customer issues.
 */
abstract class VoucherRedemptionFixtures extends AbstractMintDurableIT {

  static final ObjectMapper MAPPER = new ObjectMapper();
  static final String KEYSET_ID = "004cf8cba2f93266";
  static final UUID MINT_UUID = UUID.fromString("00000001-0000-0000-0000-000000000540");
  static final String STALL = "5a11".repeat(16);
  static final byte[] ISSUER_KEY = Schnorr.generatePrivateKey();

  /**
   * The coupon: EUR 5.05 with MINIMAL-style backing of 10 sats, so 50.5 cents per sat.
   *
   * <p>Not the c1 PoC's exact EUR 5.00 / 50.0: the mint's cashu-voucher canonicalises a whole-number
   * {@code issuance_ratio} other than 1.0 differently when signing and verifying, so such a voucher
   * never verifies here at all (reported separately). A fractional ratio keeps this test about
   * #540 and not about that.
   */
  static final long FACE_CENTS = 505L;
  static final double RATIO = 50.5;
  static final long CAP_SATS = 10L;

  @Value("${local.server.port}")
  int port;

  @MockBean MintLoadService mintLoadService;
  @MockBean ProofVaultService proofVaultService;
  @MockBean MintVaultService mintVaultService;

  @Autowired SwapResponseCacheJpaRepository swapResponseCache;
  @Autowired SwapHoldJpaRepository swapHolds;

  @Autowired
  @Qualifier("mintJpaDataSource")
  DataSource dataSource;

  final RestTemplate restTemplate = new RestTemplate();
  static MintProtocolService originalProtocolService;

  @BeforeAll
  static void stubSigningKeys() throws Exception {
    originalProtocolService = MintProtocolServiceFactory.getInstance();
    Gateway gateway = Mockito.mock(Gateway.class);
    MintProtocolService stub = Mockito.spy(originalProtocolService);
    Mockito.doReturn(gateway).when(stub).createGateway(any(PaymentMethod.class));
    Mockito.doReturn(gateway).when(stub).createGateway(any(PaymentMethod.class), anyString());
    Mockito.doAnswer(invocation -> privateKeyFor(invocation.getArgument(1, Integer.class)))
        .when(stub).getPrivateKey(anyString(), anyInt(), any(Mint.class));
    Mockito.doAnswer(invocation -> privateKeyFor(invocation.getArgument(1, Integer.class)))
        .when(stub).getPrivateKeyForSigning(anyString(), anyInt(), any(Mint.class));
    MintProtocolServiceFactory.setInstance(stub);
  }

  @AfterAll
  static void restoreProtocolService() {
    MintProtocolServiceFactory.setInstance(originalProtocolService);
  }

  @BeforeEach
  void freshState() throws Exception {
    swapResponseCache.deleteAllInBatch();
    swapHolds.deleteAllInBatch();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    // Absent on a mint without the #540 migration; the red run tolerates that.
    jdbc.execute("DO $$ BEGIN IF to_regclass('voucher_redemption') IS NOT NULL THEN "
        + "DELETE FROM voucher_redemption; END IF; END $$");
    wireKeySet();
    stubVault();
  }

  /** The token this context trusts as a redeemer. */
  abstract String redeemerToken();

  // ----------------------------------------------------------------------------------- helpers

  boolean overspent(String voucherId) {
    return Boolean.TRUE.equals(new JdbcTemplate(dataSource).queryForObject(
        "SELECT overspent FROM voucher_redemption WHERE voucher_id = ?", Boolean.class, voucherId));
  }

  long redeemed(String voucherId) {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    if (jdbc.queryForObject("SELECT to_regclass('voucher_redemption') IS NOT NULL", Boolean.class) != Boolean.TRUE) {
      return 0L; // a mint without the #540 ledger records nothing
    }
    List<Long> rows = jdbc.queryForList(
        "SELECT redeemed_sats FROM voucher_redemption WHERE voucher_id = ?", Long.class, voucherId);
    return rows.isEmpty() ? 0L : rows.get(0);
  }

  ResponseEntity<String> redeem(String voucherId, long sats, List<Map<String, Object>> inputs)
      throws Exception {
    int[] amounts = inputs.stream().mapToInt(p -> (Integer) p.get("amount")).toArray();
    return swap(inputs, outputs(amounts), voucherId + ":" + sats, redeemerToken());
  }

  ResponseEntity<String> swap(List<Map<String, Object>> inputs, List<Map<String, Object>> outputs,
                                      String declaration, String token) throws Exception {
    return post("/v1/swap", swapBody(inputs, outputs), redemptionHeaders(declaration, token));
  }

  static HttpHeaders redemptionHeaders(String declaration, String token) {
    HttpHeaders headers = new HttpHeaders();
    if (declaration != null) {
      headers.add("X-Cashu-Voucher-Redemption", declaration);
    }
    if (token != null) {
      headers.add("X-Cashu-Voucher-Redeemer", token);
    }
    return headers;
  }

  ResponseEntity<String> post(String path, String body, HttpHeaders headers) {
    headers.setContentType(MediaType.APPLICATION_JSON);
    try {
      return restTemplate.postForEntity("http://localhost:" + port + path,
          new HttpEntity<>(body, headers), String.class);
    } catch (HttpStatusCodeException e) {
      return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
    }
  }

  static String swapBody(List<Map<String, Object>> inputs, List<Map<String, Object>> outputs)
      throws Exception {
    return MAPPER.writeValueAsString(Map.of("inputs", inputs, "outputs", outputs));
  }

  /**
   * The coupon's signed blob, exactly as gateway-customer puts it on the wire: CBOR in
   * {@code data}, tags empty. Signed once; every proof carries the same blob.
   */
  static String signedBlobHex(String voucherId) {
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
    blob.put("faceValue", FACE_CENTS);
    blob.put("backingStrategy", BackingStrategy.MINIMAL.name());
    blob.put("issuanceRatio", RATIO);
    blob.put("faceDecimals", 2);
    blob.put("nonce", secret.getNonce());
    blob.put("issuerSignature", secret.getIssuerSignature());
    blob.put("issuerPublicKey", secret.getIssuerPublicKey());
    return Hex.toHexString(VoucherSerializationUtils.toCbor(blob));
  }

  /** Proofs carrying the coupon's blob, each with a fresh NUT-10 nonce: what a relabel produces. */
  static List<Map<String, Object>> voucherProofs(String blobHex, int... amounts) {
    List<Map<String, Object>> proofs = new ArrayList<>();
    for (int amount : amounts) {
      String secret = "[\"VOUCHER\",{\"nonce\":\"" + UUID.randomUUID().toString().replace("-", "")
          + "\",\"data\":\"" + blobHex + "\",\"tags\":[]}]";
      proofs.add(proof(amount, secret));
    }
    return proofs;
  }

  static List<Map<String, Object>> plainProofs(int... amounts) {
    List<Map<String, Object>> proofs = new ArrayList<>();
    for (int amount : amounts) {
      proofs.add(proof(amount, UUID.randomUUID().toString().replace("-", "").repeat(2)));
    }
    return proofs;
  }

  static Map<String, Object> proof(int amount, String secret) {
    byte[] y = BDHKEUtils.hashToCurve(secret);
    byte[] c = BDHKEUtils.signBlindedMessage(y, privateKeyFor(amount).toBytes());
    return Map.of("amount", amount, "id", KEYSET_ID, "secret", secret, "C", HexFormat.of().formatHex(c));
  }

  static List<Map<String, Object>> outputs(int... amounts) {
    List<Map<String, Object>> outputs = new ArrayList<>();
    for (int amount : amounts) {
      BigInteger scalar = new BigInteger(1, UUID.randomUUID().toString().getBytes())
          .mod(BigInteger.valueOf(Long.MAX_VALUE)).add(BigInteger.valueOf(1024));
      PrivateKey blinding = PrivateKey.fromString(String.format("%064x", scalar));
      outputs.add(Map.of("amount", amount, "id", KEYSET_ID, "B_",
          PrivateKey.derivePublicKey(blinding).toString()));
    }
    return outputs;
  }

  static PrivateKey privateKeyFor(final int amount) {
    return PrivateKey.fromString(String.format("%064x", BigInteger.valueOf(amount)));
  }

  void stubVault() throws Exception {
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
    when(proofVaultService.refundForHold(anyString())).thenAnswer(invocation -> {
      Integer held = heldByHold.remove(invocation.getArgument(0, String.class));
      return held == null ? 0 : held;
    });
  }

  void wireKeySet() throws Exception {
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
        .thenAnswer(invocation -> invocation.getArgument(0, Boolean.class) ? List.of() : List.of(keySet));
  }
}
