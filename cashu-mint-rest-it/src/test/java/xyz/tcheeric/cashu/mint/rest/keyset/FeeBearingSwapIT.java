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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.rest.spec001.AbstractMintDurableIT;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

/**
 * Issue #385 — a mint charges the NUT-02 {@code input_fee_ppk} its keyset is configured with.
 *
 * <p>This is the end-to-end half of the fee work. Unit tests pin the fee arithmetic; this drives
 * the real {@code POST /v1/swap} against a keyset priced at a non-zero fee and asserts what a
 * wallet actually experiences: a swap that pays the fee succeeds, a swap that does not is refused
 * as unbalanced, and the difference between what the mint took in and what it gave back is exactly
 * the fee it collected.
 *
 * <p>The fee reaches the mint on the keyset itself, which is why the assertion is worth making end
 * to end rather than against {@code VerifyFeesTask} alone: the value is set by an operator in the
 * admin, stored on the vault's keyset row, and read back into the {@code KeySet} the mint prices
 * with. A break anywhere along that path shows up here as a mint that charges nothing.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/02.md">NUT-02</a>
 */
class FeeBearingSwapIT extends AbstractMintDurableIT {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String KEYSET_ID = "004cf8cba2f93266";
  private static final UUID MINT_UUID = UUID.fromString("00000001-0000-0000-0000-000000000001");

  /**
   * 1000 parts per thousand is one satoshi per input spent, which makes the arithmetic legible:
   * two inputs cost exactly two sats. The rounding rule {@code (sum + 999) / 1000} is exercised
   * separately by {@code chargesAWholeSatForAFractionalFee}.
   */
  private static final int ONE_SAT_PER_INPUT_PPK = 1000;

  @Value("${local.server.port}")
  int port;

  @MockBean MintLoadService mintLoadService;

  private final RestTemplate restTemplate = new RestTemplate();

  private static MintProtocolService originalProtocolService;

  @BeforeAll
  static void stubSigningKeys() throws Exception {
    originalProtocolService = MintProtocolServiceFactory.getInstance();
    Gateway gateway = Mockito.mock(Gateway.class);
    when(gateway.checkPaymentStatus(anyString())).thenReturn(true);
    when(gateway.getAmount(anyString())).thenReturn(10);

    MintProtocolService stub = Mockito.spy(originalProtocolService);
    Mockito.doReturn(gateway).when(stub).createGateway(any(PaymentMethod.class));
    Mockito.doReturn(gateway).when(stub).createGateway(any(PaymentMethod.class), anyString());
    // The keyset's private key for a denomination is that denomination as a scalar, so a
    // test can build proofs the mint's BDHKE verification accepts.
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
  void serveAFeeBearingKeySet() throws Exception {
    wireKeySetPricedAt(ONE_SAT_PER_INPUT_PPK);
  }

  /**
   * Checks that the fee an operator configured is published, so a wallet can compute a balanced
   * transaction. A fee the mint charges but does not advertise makes every wallet build
   * transactions the mint then refuses.
   */
  @Test
  void publishesTheConfiguredFeeOnTheKeysetListing() throws Exception {
    ResponseEntity<String> response =
        restTemplate.getForEntity("http://localhost:" + port + "/v1/keysets", String.class);

    JsonNode keyset = MAPPER.readTree(response.getBody()).get("keysets").get(0);
    assertThat(keyset.get("id").asText()).isEqualTo(KEYSET_ID);
    assertThat(keyset.get("input_fee_ppk").asInt())
        .as("a wallet cannot pay a fee the mint does not tell it about")
        .isEqualTo(ONE_SAT_PER_INPUT_PPK);
  }

  /**
   * The acceptance criterion of issue #385. Swaps two 8-sat inputs against a keyset charging one
   * sat per input and asks for 14 sats back, and asserts the mint accepts it and keeps the
   * difference: 16 in, 14 out, 2 collected.
   */
  @Test
  void collectsTheFeeOnASwapThatPaysIt() throws Exception {
    int inputTotal = 8 + 8;
    int expectedFee = 2;
    int outputTotal = inputTotal - expectedFee;

    ResponseEntity<String> response =
        postSwap(
            List.of(proofJson(8), proofJson(8)),
            List.of(blindedMessageJson(8), blindedMessageJson(4), blindedMessageJson(2)));

    assertThat(response.getStatusCode().is2xxSuccessful())
        .as("a swap that pays the fee must succeed — status=%s body=%s",
            response.getStatusCode(), response.getBody())
        .isTrue();

    JsonNode signatures = MAPPER.readTree(response.getBody()).get("signatures");
    int signedTotal = 0;
    for (JsonNode signature : signatures) {
      signedTotal += signature.get("amount").asInt();
    }

    assertThat(signedTotal)
        .as("the mint must issue exactly the outputs it was asked for")
        .isEqualTo(outputTotal);
    assertThat(inputTotal - signedTotal)
        .as("what the mint took in less what it gave back is the fee it collected")
        .isEqualTo(expectedFee);
  }

  /**
   * Checks that a wallet which ignores the fee is refused rather than served for free. Asking for
   * the full 16 sats back from 16 sats of inputs leaves the 2-sat fee unpaid, which is the mint
   * giving away value.
   */
  @Test
  void refusesASwapThatDoesNotPayTheFee() throws Exception {
    ResponseEntity<String> response =
        postSwap(
            List.of(proofJson(8), proofJson(8)),
            List.of(blindedMessageJson(8), blindedMessageJson(8)));

    assertThat(response.getStatusCode().is4xxClientError())
        .as("an unpaid fee is the wallet's error to fix — status=%s", response.getStatusCode())
        .isTrue();
    assertThat(response.getBody()).contains("transaction_not_balanced");
  }

  /**
   * Checks that a fee below one satoshi per input is rounded up to a whole satoshi rather than
   * truncated to nothing. NUT-02 computes {@code (sum(ppk) + 999) / 1000}, so two inputs at 1 ppk
   * owe 1 sat; truncating would let a mint advertise a fee it never actually collects.
   */
  @Test
  void chargesAWholeSatForAFractionalFee() throws Exception {
    wireKeySetPricedAt(1);

    ResponseEntity<String> response =
        postSwap(
            List.of(proofJson(8), proofJson(8)),
            List.of(blindedMessageJson(8), blindedMessageJson(4), blindedMessageJson(2),
                blindedMessageJson(1)));

    assertThat(response.getStatusCode().is2xxSuccessful())
        .as("15 out of 16 in pays the rounded-up 1-sat fee — status=%s body=%s",
            response.getStatusCode(), response.getBody())
        .isTrue();
  }

  /**
   * Checks that a keyset nobody has priced still swaps for free. Fees are off unless an operator
   * configures one, so a mint that never sets a fee must behave exactly as it did before fees
   * existed.
   */
  @Test
  void swapsFreeOfChargeWhenNoFeeIsConfigured() throws Exception {
    wireKeySetPricedAt(0);

    ResponseEntity<String> response =
        postSwap(
            List.of(proofJson(8), proofJson(8)),
            List.of(blindedMessageJson(8), blindedMessageJson(8)));

    assertThat(response.getStatusCode().is2xxSuccessful())
        .as("an unpriced keyset charges nothing — status=%s body=%s",
            response.getStatusCode(), response.getBody())
        .isTrue();
  }

  private void wireKeySetPricedAt(final int inputFeePpk) throws Exception {
    Mint mint = mintPricedAt(inputFeePpk);
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

  private static Mint mintPricedAt(final int inputFeePpk) {
    Mint mint = new Mint(MINT_UUID.toString());
    Keys keys = new Keys();
    for (int amount : new int[] {1, 2, 4, 8, 16}) {
      keys.put(BigInteger.valueOf(amount), PrivateKey.derivePublicKey(privateKeyFor(amount)));
    }
    mint.addKeySet(
        KeySet.builder()
            .id(KEYSET_ID)
            .unit("sat")
            .keys(keys)
            .partPerThousand(inputFeePpk)
            .build());
    return mint;
  }

  private static PrivateKey privateKeyFor(final int amount) {
    return PrivateKey.fromString(String.format("%064x", BigInteger.valueOf(amount)));
  }

  /**
   * A proof the mint's BDHKE verification accepts: {@code C = priv * hashToCurve(secret)}, which
   * is what unblinding yields, so the blinding round trip can be skipped.
   */
  private static Map<String, Object> proofJson(final int amount) {
    String secret = UUID.randomUUID().toString().replace("-", "").repeat(2);
    byte[] y = BDHKEUtils.hashToCurve(secret);
    byte[] c = BDHKEUtils.signBlindedMessage(y, privateKeyFor(amount).toBytes());
    return Map.of(
        "amount", amount,
        "id", KEYSET_ID,
        "secret", secret,
        "C", java.util.HexFormat.of().formatHex(c));
  }

  /** A distinct curve point per call, so no two outputs of a swap collide. */
  private static Map<String, Object> blindedMessageJson(final int amount) {
    BigInteger scalar = new BigInteger(1, UUID.randomUUID().toString().getBytes()).mod(
        BigInteger.valueOf(Long.MAX_VALUE)).add(BigInteger.valueOf(1024));
    PrivateKey blindingKey = PrivateKey.fromString(String.format("%064x", scalar));
    return Map.of(
        "amount", amount,
        "id", KEYSET_ID,
        "B_", PrivateKey.derivePublicKey(blindingKey).toString());
  }

  private ResponseEntity<String> postSwap(
      final List<Map<String, Object>> inputs, final List<Map<String, Object>> outputs) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    try {
      String body = MAPPER.writeValueAsString(Map.of("inputs", inputs, "outputs", outputs));
      return restTemplate.postForEntity(
          "http://localhost:" + port + "/v1/swap", new HttpEntity<>(body, headers), String.class);
    } catch (org.springframework.web.client.HttpStatusCodeException e) {
      return ResponseEntity.status(e.getStatusCode())
          .headers(e.getResponseHeaders())
          .body(e.getResponseBodyAsString());
    } catch (Exception e) {
      throw new IllegalStateException("Failed to POST /v1/swap", e);
    }
  }
}
