package xyz.tcheeric.cashu.mint.rest.spec001;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.jpa.entity.MintQuoteEntity;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.payment.adapter.core.common.Gateway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 001 T100 (partial) — drives {@code POST /v1/mint/bolt11} against the
 * Testcontainers Postgres harness with under-mint and over-mint output sets,
 * and asserts the durable {@code amount_mismatch} contract (FR-001) fires
 * through the full REST stack.
 *
 * <p>The mismatched-amount cases reject INSIDE {@code MintTask} BEFORE the
 * gateway cross-check + signing run, so this IT doesn't need a configured
 * payment gateway or a real {@code KeySetVaultClient}. It only needs:
 * <ul>
 *   <li>The Testcontainers Postgres harness (inherited from
 *       {@link AbstractMintDurableIT}).</li>
 *   <li>A {@link MintLoadService} bean that returns a {@link Mint} with the
 *       test keyset so {@code CashuController#findMintIdByKeysetId} can
 *       resolve the mint id.</li>
 * </ul>
 *
 * <p>The exact-amount + replay + concurrency variants (T101 / T300) need
 * real signing, which means a configured key vault (or a deep mock of
 * {@code MintProtocolUtil#getPrivateKey}'s static
 * {@code VaultClientFactory.keySetClient()} call). Those are tracked as a
 * separate fixture-building effort.
 */
class MintQuoteAmountBindingIT extends AbstractMintDurableIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TEST_KEYSET_ID = "004cf8cba2f93266";
    private static final UUID TEST_MINT_UUID = UUID.fromString("00000001-0000-0000-0000-000000000001");
    // Valid secp256k1 public keys — derived from the corresponding small private keys.
    private static final String B_AMT_8 = "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2";
    private static final String B_AMT_1 = "03a0434d9e47f3c86235477c7b1ae6ae5d3442d49b1943c2b752a68e2a47e247c7";
    private static final String B_AMT_2 = "025f9d298d8d9e774c81ee64927a27e6e6b6e18f65447eb6a16808f92b84e44112";

    @Value("${local.server.port}")
    int port;

    @MockBean
    MintLoadService mintLoadService;

    private final RestTemplate restTemplate = new RestTemplate();

    private static MintProtocolService originalProtocolService;

    @BeforeAll
    static void overrideProtocolService() throws Exception {
        originalProtocolService = MintProtocolServiceFactory.getInstance();
        Gateway gateway = Mockito.mock(Gateway.class);
        Mockito.when(gateway.checkPaymentStatus(Mockito.anyString())).thenReturn(true);
        Mockito.when(gateway.getAmount(Mockito.anyString())).thenReturn(10);
        MintProtocolService stub = Mockito.spy(originalProtocolService);
        Mockito.doReturn(gateway).when(stub).createGateway(Mockito.any(PaymentMethod.class));
        Mockito.doReturn(gateway).when(stub).createGateway(Mockito.any(PaymentMethod.class), Mockito.anyString());
        // Stub the keyset private-key lookup so signing doesn't hit the cashu-vault REST client.
        // Maps amount → matching private key (same hex pattern createMintWithKeys uses).
        Mockito.doAnswer(inv -> {
            Integer amount = inv.getArgument(1);
            String hex = String.format("%064x", java.math.BigInteger.valueOf(amount));
            return PrivateKey.fromString(hex);
        }).when(stub).getPrivateKey(Mockito.anyString(), Mockito.anyInt(), Mockito.any(Mint.class));
        MintProtocolServiceFactory.setInstance(stub);
    }

    @AfterAll
    static void restoreProtocolService() {
        MintProtocolServiceFactory.setInstance(originalProtocolService);
    }

    private void wireMockMintLoader() throws CashuErrorException {
        Mint mint = createMintWithKeys();
        Mockito.when(mintLoadService.load(Mockito.any(UUID.class), Mockito.anyBoolean())).thenReturn(mint);
        Mockito.when(mintLoadService.load(Mockito.anyBoolean())).thenReturn(List.of(mint));
    }

    @BeforeEach
    void seedPaidQuote() throws CashuErrorException {
        wireMockMintLoader();
        MintQuoteEntity quote = new MintQuoteEntity();
        quote.setQuoteId("q-it-mint-bind");
        quote.setAmount(10L);
        quote.setUnit("sat");
        quote.setMintUrl("https://mint.it.example");
        quote.setPaymentMethod("bolt11");
        quote.setLifecycleState(LifecycleState.PAID);
        quote.setRequestHash("0".repeat(64));
        mintQuoteJpaRepository.save(quote);
    }

    @Test
    void underMint_rejectedWithAmountMismatch_andQuoteStaysPaid() {
        ResponseEntity<String> response = postMint(
                "q-it-mint-bind",
                List.of(Map.of("amount", 8, "id", TEST_KEYSET_ID, "B_", B_AMT_8),
                        Map.of("amount", 1, "id", TEST_KEYSET_ID, "B_", B_AMT_1)));

        assertThat(response.getStatusCode().isError())
                .as("status=%s body=%s", response.getStatusCode(), response.getBody())
                .isTrue();
        assertThat(response.getBody()).contains("amount_mismatch");
        assertThat(mintQuoteJpaRepository.findById("q-it-mint-bind").orElseThrow().getLifecycleState())
                .as("under-mint must not advance the quote out of PAID")
                .isEqualTo(LifecycleState.PAID);
        assertThat(issuanceRecordJpaRepository.count())
                .as("no IssuanceRecord row written on amount_mismatch")
                .isEqualTo(0L);
    }

    @Test
    void overMint_rejectedWithAmountMismatch_andQuoteStaysPaid() {
        ResponseEntity<String> response = postMint(
                "q-it-mint-bind",
                List.of(Map.of("amount", 8, "id", TEST_KEYSET_ID, "B_", B_AMT_8),
                        Map.of("amount", 2, "id", TEST_KEYSET_ID, "B_", B_AMT_2),
                        Map.of("amount", 1, "id", TEST_KEYSET_ID, "B_", B_AMT_1)));

        assertThat(response.getStatusCode().isError()).isTrue();
        assertThat(response.getBody()).contains("amount_mismatch");
        assertThat(mintQuoteJpaRepository.findById("q-it-mint-bind").orElseThrow().getLifecycleState())
                .isEqualTo(LifecycleState.PAID);
        assertThat(issuanceRecordJpaRepository.count()).isEqualTo(0L);
    }

    @Test
    void invalidOutput_amountSumsButDenominationInvalid_quoteStaysPaid() {
        // Spec 007 — outputs sum to the quote amount (11 + (-1) = 10) but one
        // output is deterministically invalid (non-positive). validateDenominations
        // now runs BEFORE the PAID → ISSUING CAS, so the quote MUST stay PAID
        // and never be consumed into ISSUING. Pre-fix this stranded the quote.
        ResponseEntity<String> response = postMint(
                "q-it-mint-bind",
                List.of(Map.of("amount", 11, "id", TEST_KEYSET_ID, "B_", B_AMT_8),
                        Map.of("amount", -1, "id", TEST_KEYSET_ID, "B_", B_AMT_1)));

        assertThat(response.getStatusCode().isError())
                .as("status=%s body=%s", response.getStatusCode(), response.getBody())
                .isTrue();
        assertThat(response.getBody()).contains("invalid_output_amount");
        assertThat(mintQuoteJpaRepository.findById("q-it-mint-bind").orElseThrow().getLifecycleState())
                .as("a deterministically-invalid output set must not consume the quote into ISSUING")
                .isEqualTo(LifecycleState.PAID);
        assertThat(issuanceRecordJpaRepository.count())
                .as("no IssuanceRecord row written when output validation fails")
                .isEqualTo(0L);
    }

    @Test
    void wrongSplit_amountSumsButNotCanonical_quoteStaysPaid() {
        // Outputs sum to 10 with valid keyset denoms but a non-canonical split
        // ([4,4,2] vs the NUT-00 minimal [8,2]) → invalid_denominations, before
        // the CAS. Quote stays PAID.
        ResponseEntity<String> response = postMint(
                "q-it-mint-bind",
                List.of(Map.of("amount", 4, "id", TEST_KEYSET_ID, "B_", B_AMT_8),
                        Map.of("amount", 4, "id", TEST_KEYSET_ID, "B_", B_AMT_1),
                        Map.of("amount", 2, "id", TEST_KEYSET_ID, "B_", B_AMT_2)));

        assertThat(response.getStatusCode().isError())
                .as("status=%s body=%s", response.getStatusCode(), response.getBody())
                .isTrue();
        assertThat(response.getBody()).contains("invalid_denominations");
        assertThat(mintQuoteJpaRepository.findById("q-it-mint-bind").orElseThrow().getLifecycleState())
                .isEqualTo(LifecycleState.PAID);
        assertThat(issuanceRecordJpaRepository.count()).isEqualTo(0L);
    }

    @Test
    void exactMint_succeeds_quoteIssued_andOneIssuanceRecord() {
        ResponseEntity<String> response = postMint(
                "q-it-mint-bind",
                List.of(Map.of("amount", 8, "id", TEST_KEYSET_ID, "B_", B_AMT_8),
                        Map.of("amount", 2, "id", TEST_KEYSET_ID, "B_", B_AMT_2)));

        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("status=%s body=%s", response.getStatusCode(), response.getBody())
                .isTrue();
        assertThat(mintQuoteJpaRepository.findById("q-it-mint-bind").orElseThrow().getLifecycleState())
                .isEqualTo(LifecycleState.ISSUED);
        assertThat(issuanceRecordJpaRepository.findAll())
                .as("exactly one IssuanceRecord row")
                .hasSize(1)
                .first()
                .satisfies(r -> {
                    assertThat(r.getQuoteId()).isEqualTo("q-it-mint-bind");
                    assertThat(r.getTotalAmount()).isEqualTo(10L);
                });
    }

    @Test
    void retrySameOutputs_returnsIdenticalSignatures_andNoSecondIssuance() {
        List<Map<String, Object>> outputs = List.of(
                Map.of("amount", 8, "id", TEST_KEYSET_ID, "B_", B_AMT_8),
                Map.of("amount", 2, "id", TEST_KEYSET_ID, "B_", B_AMT_2));

        ResponseEntity<String> first = postMint("q-it-mint-bind", outputs);
        assertThat(first.getStatusCode().is2xxSuccessful()).isTrue();
        String firstBody = first.getBody();

        ResponseEntity<String> second = postMint("q-it-mint-bind", outputs);
        assertThat(second.getStatusCode().is2xxSuccessful())
                .as("retry status=%s body=%s", second.getStatusCode(), second.getBody())
                .isTrue();
        assertThat(second.getBody())
                .as("NUT-19 replay must return the cached signatures byte-for-byte")
                .isEqualTo(firstBody);
        assertThat(issuanceRecordJpaRepository.count()).isEqualTo(1L);
    }

    @Test
    void retryDifferentOutputs_isRejectedWithQuoteAlreadyIssued() {
        // First issuance: 8+2=10
        List<Map<String, Object>> first = List.of(
                Map.of("amount", 8, "id", TEST_KEYSET_ID, "B_", B_AMT_8),
                Map.of("amount", 2, "id", TEST_KEYSET_ID, "B_", B_AMT_2));
        assertThat(postMint("q-it-mint-bind", first).getStatusCode().is2xxSuccessful()).isTrue();

        // Replay with a DIFFERENT output set summing to the same 10.
        List<Map<String, Object>> different = List.of(
                Map.of("amount", 4, "id", TEST_KEYSET_ID, "B_", B_AMT_1),
                Map.of("amount", 4, "id", TEST_KEYSET_ID, "B_", B_AMT_2),
                Map.of("amount", 1, "id", TEST_KEYSET_ID, "B_", B_AMT_8),
                Map.of("amount", 1, "id", TEST_KEYSET_ID, "B_", B_AMT_1));
        ResponseEntity<String> response = postMint("q-it-mint-bind", different);
        assertThat(response.getStatusCode().isError()).isTrue();
        assertThat(response.getBody()).contains("quote_already_issued");
        assertThat(issuanceRecordJpaRepository.count()).isEqualTo(1L);
    }

    @Test
    void unknownQuote_rejectedWithQuoteNotFound() {
        ResponseEntity<String> response = postMint(
                "q-doesnt-exist",
                List.of(Map.of("amount", 10, "id", TEST_KEYSET_ID, "B_", B_AMT_8)));

        assertThat(response.getStatusCode().isError()).isTrue();
        assertThat(response.getBody()).contains("quote_not_found");
    }

    // ---------------------- helpers ----------------------

    private ResponseEntity<String> postMint(String quoteId, List<Map<String, Object>> outputs) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            String body = MAPPER.writeValueAsString(Map.of(
                    "quote", quoteId,
                    "outputs", outputs));
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            return restTemplate.postForEntity(
                    "http://localhost:" + port + "/v1/mint/bolt11",
                    entity,
                    String.class);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .headers(e.getResponseHeaders())
                    .body(e.getResponseBodyAsString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Mint createMintWithKeys() {
        Mint mint = new Mint(TEST_MINT_UUID.toString());
        Keys keys = new Keys();
        keys.put(BigInteger.valueOf(1), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000001")));
        keys.put(BigInteger.valueOf(2), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000002")));
        keys.put(BigInteger.valueOf(4), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000004")));
        keys.put(BigInteger.valueOf(8), PrivateKey.derivePublicKey(PrivateKey.fromString("0000000000000000000000000000000000000000000000000000000000000008")));
        mint.addKeySet(KeySet.builder().id(TEST_KEYSET_ID).unit("sat").keys(keys).build());
        return mint;
    }
}
