package xyz.tcheeric.cashu.mint.rest.keyset;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.beans.factory.annotation.Autowired;
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
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.mint.jpa.entity.MintQuoteEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.IssuanceRecordJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.MintQuoteJpaRepository;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.rest.spec001.AbstractMintDurableIT;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

/**
 * Archiving a keyset retires it for issuance — see ADR-0004.
 *
 * <p>Drives {@code POST /v1/mint/bolt11} against a paid quote whose keyset the vault reports as
 * archived, and asserts what a wallet actually sees: a 4xx carrying {@code keyset_inactive}, not a
 * 500, and no tokens issued. The complementary half — that an archived keyset still yields keys for
 * redemption through {@code getPrivateKey} — is pinned in {@code
 * MintProtocolUtilArchivedKeysetTest}.
 */
class ArchivedKeysetSigningIT extends AbstractMintDurableIT {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String TEST_KEYSET_ID = "004cf8cba2f93266";
  private static final UUID TEST_MINT_UUID =
      UUID.fromString("00000001-0000-0000-0000-000000000001");
  private static final String B_AMT_8 =
      "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2";
  private static final String B_AMT_2 =
      "025f9d298d8d9e774c81ee64927a27e6e6b6e18f65447eb6a16808f92b84e44112";
  private static final String QUOTE_ID = "q-archived-keyset";

  @Value("${local.server.port}")
  int port;

  @MockBean MintLoadService mintLoadService;

  @Autowired MintQuoteJpaRepository mintQuoteJpaRepository;

  @Autowired IssuanceRecordJpaRepository issuanceRecordJpaRepository;

  private final RestTemplate restTemplate = new RestTemplate();

  private static MintProtocolService originalProtocolService;

  @BeforeAll
  static void archiveTheKeyset() throws Exception {
    originalProtocolService = MintProtocolServiceFactory.getInstance();
    Gateway gateway = Mockito.mock(Gateway.class);
    Mockito.when(gateway.checkPaymentStatus(Mockito.anyString())).thenReturn(true);
    Mockito.when(gateway.getAmount(Mockito.anyString())).thenReturn(10);

    MintProtocolService stub = Mockito.spy(originalProtocolService);
    Mockito.doReturn(gateway).when(stub).createGateway(Mockito.any(PaymentMethod.class));
    Mockito.doReturn(gateway)
        .when(stub)
        .createGateway(Mockito.any(PaymentMethod.class), Mockito.anyString());
    // Redemption still resolves keys for this keyset...
    Mockito.doAnswer(
            inv -> {
              Integer amount = inv.getArgument(1);
              return PrivateKey.fromString(String.format("%064x", BigInteger.valueOf(amount)));
            })
        .when(stub)
        .getPrivateKey(Mockito.anyString(), Mockito.anyInt(), Mockito.any(Mint.class));
    // ...but signing against it is refused, as the vault would refuse an
    // archived keyset.
    Mockito.doThrow(
            new CashuErrorException(
                CashuErrorCode.keyset_inactive,
                "Keyset "
                    + TEST_KEYSET_ID
                    + " is archived and no longer signs. "
                    + "Re-read /v1/keys and retry against an active keyset."))
        .when(stub)
        .getPrivateKeyForSigning(Mockito.anyString(), Mockito.anyInt(), Mockito.any(Mint.class));
    MintProtocolServiceFactory.setInstance(stub);
  }

  @AfterAll
  static void restoreProtocolService() {
    MintProtocolServiceFactory.setInstance(originalProtocolService);
  }

  @BeforeEach
  void seedPaidQuote() throws CashuErrorException {
    Mint mint = createMintWithKeys();
    Mockito.when(mintLoadService.load(Mockito.any(UUID.class), Mockito.anyBoolean()))
        .thenReturn(mint);
    Mockito.when(mintLoadService.load(Mockito.anyBoolean())).thenReturn(List.of(mint));

    MintQuoteEntity quote = new MintQuoteEntity();
    quote.setQuoteId(QUOTE_ID);
    quote.setAmount(10L);
    quote.setUnit("sat");
    quote.setMintUrl("https://mint.it.example");
    quote.setPaymentMethod("bolt11");
    quote.setLifecycleState(LifecycleState.PAID);
    quote.setRequestHash("0".repeat(64));
    mintQuoteJpaRepository.save(quote);
  }

  // Ensures a wallet asking an archived keyset to sign gets a 4xx it can act on,
  // and that no tokens are issued against it.
  @Test
  void refusesToMintAgainstAnArchivedKeyset() {
    ResponseEntity<String> response =
        postMint(
            QUOTE_ID,
            List.of(
                Map.of("amount", 8, "id", TEST_KEYSET_ID, "B_", B_AMT_8),
                Map.of("amount", 2, "id", TEST_KEYSET_ID, "B_", B_AMT_2)));

    assertThat(response.getStatusCode().is4xxClientError())
        .as(
            "an archived keyset is a client error, not a server fault — status=%s body=%s",
            response.getStatusCode(), response.getBody())
        .isTrue();
    assertThat(response.getBody())
        .as("the wallet must be told to refresh its keysets, not handed a generic failure")
        .contains("keyset_inactive");
    // Distinguishable from an unknown keyset, because the recovery differs.
    assertThat(response.getBody()).doesNotContain("keyset_not_found");
    assertThat(issuanceRecordJpaRepository.count())
        .as("nothing may be issued against an archived keyset")
        .isEqualTo(0L);
  }

  private static Mint createMintWithKeys() {
    Mint mint = new Mint(TEST_MINT_UUID.toString());
    Keys keys = new Keys();
    for (int amount : new int[] {1, 2, 4, 8}) {
      keys.put(
          BigInteger.valueOf(amount),
          PrivateKey.derivePublicKey(
              PrivateKey.fromString(String.format("%064x", BigInteger.valueOf(amount)))));
    }
    mint.addKeySet(KeySet.builder().id(TEST_KEYSET_ID).unit("sat").keys(keys).build());
    return mint;
  }

  private ResponseEntity<String> postMint(String quoteId, List<Map<String, Object>> outputs) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    try {
      String body = MAPPER.writeValueAsString(Map.of("quote", quoteId, "outputs", outputs));
      HttpEntity<String> entity = new HttpEntity<>(body, headers);
      return restTemplate.postForEntity(
          "http://localhost:" + port + "/v1/mint/bolt11", entity, String.class);
    } catch (org.springframework.web.client.HttpStatusCodeException e) {
      return ResponseEntity.status(e.getStatusCode())
          .headers(e.getResponseHeaders())
          .body(e.getResponseBodyAsString());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
