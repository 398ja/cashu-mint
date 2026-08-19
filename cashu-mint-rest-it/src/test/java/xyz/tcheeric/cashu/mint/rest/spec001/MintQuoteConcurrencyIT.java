package xyz.tcheeric.cashu.mint.rest.spec001;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.jpa.entity.MintQuoteEntity;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 001 T101 — drives N concurrent identical {@code POST /v1/mint/bolt11}
 * requests against the same PAID quote and asserts SC-002: exactly one
 * {@code IssuanceRecord} row is persisted regardless of contention, and
 * every successful response carries the same signed promises.
 */
class MintQuoteConcurrencyIT extends AbstractMintDurableIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TEST_KEYSET_ID = "004cf8cba2f93266";
    private static final UUID TEST_MINT_UUID = UUID.fromString("00000001-0000-0000-0000-000000000001");
    private static final String B_AMT_8 = "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2";
    private static final String B_AMT_2 = "025f9d298d8d9e774c81ee64927a27e6e6b6e18f65447eb6a16808f92b84e44112";

    @Value("${local.server.port}")
    int port;

    @MockBean
    MintLoadService mintLoadService;

    private static MintProtocolService originalProtocolService;
    private final RestTemplate restTemplate = new RestTemplate();

    @BeforeAll
    static void overrideProtocolService() throws Exception {
        originalProtocolService = MintProtocolServiceFactory.getInstance();
        Gateway gateway = Mockito.mock(Gateway.class);
        Mockito.when(gateway.checkPaymentStatus(Mockito.anyString())).thenReturn(true);
        Mockito.when(gateway.getAmount(Mockito.anyString())).thenReturn(10);
        MintProtocolService stub = Mockito.spy(originalProtocolService);
        Mockito.doReturn(gateway).when(stub).createGateway(Mockito.any(PaymentMethod.class));
        Mockito.doReturn(gateway).when(stub).createGateway(Mockito.any(PaymentMethod.class), Mockito.anyString());
        Mockito.doAnswer(inv -> {
            Integer amount = inv.getArgument(1);
            return PrivateKey.fromString(String.format("%064x", BigInteger.valueOf(amount)));
        }).when(stub).getPrivateKey(Mockito.anyString(), Mockito.anyInt(), Mockito.any(Mint.class));
        // Signing resolves through getPrivateKeyForSigning, which also honours the
        // archived flag; stub it too or signing reaches the real vault client.
        Mockito.doAnswer(inv -> {
            Integer signAmount = inv.getArgument(1);
            String signHex = String.format("%064x", java.math.BigInteger.valueOf(signAmount));
            return PrivateKey.fromString(signHex);
        }).when(stub).getPrivateKeyForSigning(Mockito.anyString(), Mockito.anyInt(), Mockito.any(Mint.class));
        MintProtocolServiceFactory.setInstance(stub);
    }

    @AfterAll
    static void restoreProtocolService() {
        MintProtocolServiceFactory.setInstance(originalProtocolService);
    }

    @BeforeEach
    void seedPaidQuote() throws CashuErrorException {
        Mockito.when(mintLoadService.load(Mockito.any(UUID.class), Mockito.anyBoolean()))
                .thenReturn(createMintWithKeys());
        Mockito.when(mintLoadService.load(Mockito.anyBoolean()))
                .thenReturn(List.of(createMintWithKeys()));

        MintQuoteEntity quote = new MintQuoteEntity();
        quote.setQuoteId("q-it-concurrent");
        quote.setAmount(10L);
        quote.setUnit("sat");
        quote.setMintUrl("https://mint.it.example");
        quote.setPaymentMethod("bolt11");
        quote.setLifecycleState(LifecycleState.PAID);
        quote.setRequestHash("0".repeat(64));
        mintQuoteJpaRepository.save(quote);
    }

    @Test
    void concurrentIdenticalRequests_collapseToOneIssuance_andReturnSameSignatures() throws Exception {
        int parallelism = 6;
        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        try {
            List<CompletableFuture<ResponseEntity<String>>> futures = IntStream.range(0, parallelism)
                    .mapToObj(i -> CompletableFuture.supplyAsync(this::postExactMint, executor))
                    .toList();
            List<ResponseEntity<String>> responses = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

            long successCount = responses.stream()
                    .filter(r -> r.getStatusCode().is2xxSuccessful())
                    .count();
            assertThat(successCount)
                    .as("at least one concurrent caller must succeed; the rest may replay or hit issuance_in_progress")
                    .isGreaterThanOrEqualTo(1L);

            // Per SC-002: exactly one IssuanceRecord regardless of contention.
            assertThat(issuanceRecordJpaRepository.count())
                    .as("exactly one IssuanceRecord row after %d concurrent requests", parallelism)
                    .isEqualTo(1L);
            assertThat(mintQuoteJpaRepository.findById("q-it-concurrent").orElseThrow().getLifecycleState())
                    .isEqualTo(LifecycleState.ISSUED);

            // The successful responses MUST be byte-identical (NUT-19 replay).
            List<String> successfulBodies = responses.stream()
                    .filter(r -> r.getStatusCode().is2xxSuccessful())
                    .map(ResponseEntity::getBody)
                    .distinct()
                    .toList();
            assertThat(successfulBodies)
                    .as("all 2xx responses must carry the same signed promises")
                    .hasSize(1);
        } finally {
            executor.shutdown();
            //noinspection ResultOfMethodCallIgnored
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private ResponseEntity<String> postExactMint() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            String body = MAPPER.writeValueAsString(Map.of(
                    "quote", "q-it-concurrent",
                    "outputs", List.of(
                            Map.of("amount", 8, "id", TEST_KEYSET_ID, "B_", B_AMT_8),
                            Map.of("amount", 2, "id", TEST_KEYSET_ID, "B_", B_AMT_2))));
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
