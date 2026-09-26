package xyz.tcheeric.cashu.mint.rest.spec001;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.Signature;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut09.PostRestoreRequest;
import xyz.tcheeric.cashu.entities.rest.nut09.PostRestoreResponse;
import xyz.tcheeric.cashu.mint.jpa.InvariantGaugePoller;
import xyz.tcheeric.cashu.mint.jpa.MintJpaAutoConfiguration;
import xyz.tcheeric.cashu.mint.jpa.adapter.JpaSignatureVaultService;
import xyz.tcheeric.cashu.mint.jpa.entity.MintQuoteEntity;
import xyz.tcheeric.cashu.mint.proto.domain.SignatureSource;
import xyz.tcheeric.cashu.mint.proto.nut.NUT09;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultSignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.tasks.ValidateTransactionTask;
import xyz.tcheeric.cashu.mint.rest.support.PrometheusScrape;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #491: the record of signed outputs is durable and shared.
 *
 * <p>The mint under test signs through the real {@code POST /v1/mint/bolt11} endpoint. What
 * happens afterwards is then observed from <em>other</em> mint instances: persistence contexts
 * booted fresh against the same Postgres, sharing nothing in process with the first. A context
 * booted after the signing is what a restarted mint sees, and one booted alongside it is what a
 * second replica sees. The singleton container in {@link AbstractMintDurableIT} outlives every
 * context, which is what lets the database be the only thing they have in common.
 */
@TestPropertySource(properties = {
        "CASHU_MINT_MANAGEMENT_PORT=0",
        "cashu.observability.enabled=true",
        "management.prometheus.metrics.export.enabled=true",
        "management.endpoints.web.exposure.include=health,info,prometheus,metrics",
        "cashu.mint.invariant.poll-interval=PT1H"
})
class DurableSignatureVaultIT extends AbstractMintDurableIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TEST_KEYSET_ID = "004cf8cba2f93266";
    private static final UUID TEST_MINT_UUID = UUID.fromString("00000001-0000-0000-0000-000000000491");
    private static final String FIRST_QUOTE = "q-it-491-first";
    private static final String SECOND_QUOTE = "q-it-491-second";
    private static final int AMOUNT = 8;
    private static final String B_ = "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2";

    @Value("${local.server.port}")
    int port;

    @Value("${local.management.port}")
    int managementPort;

    @MockBean
    MintLoadService mintLoadService;

    @Autowired
    ApplicationContext mintContext;

    @Autowired
    InvariantGaugePoller poller;

    private final RestTemplate restTemplate = new RestTemplate();

    private static MintProtocolService originalProtocolService;

    @BeforeAll
    static void overrideProtocolService() throws CashuErrorException {
        originalProtocolService = MintProtocolServiceFactory.getInstance();
        Gateway gateway = Mockito.mock(Gateway.class);
        Mockito.when(gateway.checkPaymentStatus(Mockito.anyString())).thenReturn(true);
        Mockito.when(gateway.getAmount(Mockito.anyString())).thenReturn(AMOUNT);

        MintProtocolService stub = Mockito.spy(originalProtocolService);
        Mockito.doReturn(gateway).when(stub).createGateway(Mockito.any(PaymentMethod.class));
        Mockito.doReturn(gateway).when(stub)
                .createGateway(Mockito.any(PaymentMethod.class), Mockito.anyString());
        Mockito.doAnswer(invocation -> privateKeyForAmount(invocation.getArgument(1)))
                .when(stub).getPrivateKeyForSigning(Mockito.anyString(), Mockito.anyInt(), Mockito.any(Mint.class));
        MintProtocolServiceFactory.setInstance(stub);
    }

    @AfterAll
    static void restoreProtocolService() {
        MintProtocolServiceFactory.setInstance(originalProtocolService);
    }

    @BeforeEach
    void seedPaidQuotes() throws CashuErrorException {
        Mint mint = mintWithKeys();
        Mockito.when(mintLoadService.load(Mockito.any(UUID.class), Mockito.anyBoolean())).thenReturn(mint);
        Mockito.when(mintLoadService.load(Mockito.anyBoolean())).thenReturn(List.of(mint));
        savePaidQuote(FIRST_QUOTE);
        savePaidQuote(SECOND_QUOTE);
    }

    // The mint wires the durable vault and never creates the in-memory fallback when JPA is enabled.
    @Test
    void theMintIsWiredWithTheDurableVaultAndNoInMemoryFallback() {
        assertThat(mintContext.getBeansOfType(SignatureVaultService.class))
                .as("exactly one signature vault, and it is the durable one")
                .hasSize(1)
                .allSatisfy((name, vault) -> assertThat(vault).isInstanceOf(JpaSignatureVaultService.class));
        assertThat(mintContext.getBeansOfType(DefaultSignatureVaultService.class)).isEmpty();
    }

    // A B_ signed once is refused on a later mint request with outputs_already_signed, and the
    // second paid quote is left PAID rather than stranded in ISSUING.
    @Test
    void aSignedOutputIsRefusedOnALaterMintAndLeavesThatQuotePaid() throws Exception {
        assertThat(mint(FIRST_QUOTE).getStatusCode().is2xxSuccessful()).isTrue();

        ResponseEntity<String> refused = mint(SECOND_QUOTE);

        assertThat(refused.getStatusCode().is4xxClientError())
                .as("status=%s body=%s", refused.getStatusCode(), refused.getBody())
                .isTrue();
        assertThat(refused.getBody())
                .as("outputs_already_signed is wire code 11003")
                .contains("\"code\":11003");
        assertThat(mintQuoteJpaRepository.findById(SECOND_QUOTE).orElseThrow().getLifecycleState())
                .as("the refused quote must stay PAID so the customer can still mint against it")
                .isEqualTo(LifecycleState.PAID);
    }

    // A NUT-19 replay of the same outputs against the quote that issued them still returns the
    // original signatures; refusing duplicates must not break idempotent retries.
    @Test
    void aNut19ReplayOfTheSameOutputsStillSucceeds() {
        ResponseEntity<String> first = mint(FIRST_QUOTE);
        ResponseEntity<String> replay = mint(FIRST_QUOTE);

        assertThat(replay.getStatusCode().is2xxSuccessful())
                .as("status=%s body=%s", replay.getStatusCode(), replay.getBody())
                .isTrue();
        assertThat(replay.getBody()).isEqualTo(first.getBody());
    }

    // After the signing process is gone, a freshly started mint on the same database refuses
    // the same B_ and a wallet restoring from its seed gets the original signature back.
    @Test
    void aRestartedMintRefusesTheSignedOutputAndRestoresItsSignature() throws Exception {
        JsonNode issued = firstSignature(mint(FIRST_QUOTE));

        withFreshMintInstance(restarted -> {
            assertOutputIsRefused(restarted);
            assertRestoreReturns(restarted, issued);
        });
    }

    // Two mint instances on one database share the record, so the second refuses a B_ the
    // first signed, both at validation and at the moment of recording a signature.
    @Test
    void aSecondInstanceRefusesAnOutputTheFirstSigned() throws Exception {
        mint(FIRST_QUOTE);

        withFreshMintInstance(secondInstance -> {
            assertThatThrownBy(() -> new ValidateTransactionTask<Secret>(
                    null, List.of(output()), null, secondInstance).execute())
                    .isInstanceOf(CashuErrorException.class)
                    .extracting(e -> ((CashuErrorException) e).getErrorCode())
                    .isEqualTo(CashuErrorCode.outputs_already_signed);
            assertOutputIsRefused(secondInstance);
        });
    }

    // The restore endpoint of the signing mint returns the stored signature, DLEQ included.
    @Test
    void restoreOverRestReturnsTheIssuedSignature() throws Exception {
        JsonNode issued = firstSignature(mint(FIRST_QUOTE));

        JsonNode restored = MAPPER.readTree(restoreOverRest().getBody()).get("signatures").get(0);

        assertThat(restored.get("C_").asText()).isEqualTo(issued.get("C_").asText());
        assertThat(restored.get("dleq").get("e").asText()).isEqualTo(issued.get("dleq").get("e").asText());
        assertThat(restored.get("dleq").get("s").asText()).isEqualTo(issued.get("dleq").get("s").asText());
    }

    // The issued-amount series reports the face value signed on the keyset, read off the real
    // Prometheus scrape endpoint.
    @Test
    void theIssuedAmountIsExportedPerKeyset() {
        mint(FIRST_QUOTE);

        poller.pollTick();

        assertThat(PrometheusScrape.body(restTemplate, managementPort))
                .contains("cashu_mint_issued_amount_total{")
                .containsPattern("cashu_mint_issued_amount_total\\{[^}]*keyset=\"" + TEST_KEYSET_ID
                        + "\"[^}]*} " + AMOUNT + "\\.0");
    }

    // --- assertions ---------------------------------------------------------------------------

    private void assertOutputIsRefused(SignatureVaultService vault) {
        BlindSignature forgery = new BlindSignature(AMOUNT, KeysetId.fromString(TEST_KEYSET_ID),
                Signature.fromString(B_), null);
        assertThatThrownBy(() -> vault.store(output(), forgery, SignatureSource.SWAP))
                .isInstanceOf(CashuErrorException.class)
                .extracting(e -> ((CashuErrorException) e).getErrorCode())
                .isEqualTo(CashuErrorCode.outputs_already_signed);
    }

    private void assertRestoreReturns(SignatureVaultService vault, JsonNode issued) throws CashuErrorException {
        PostRestoreResponse restored = NUT09.restore(new PostRestoreRequest(List.of(output())), vault);

        assertThat(restored.getBlindSignatures()).hasSize(1);
        BlindSignature signature = restored.getBlindSignatures().get(0);
        assertThat(signature.getBlindedSignature().toString()).isEqualTo(issued.get("C_").asText());
        assertThat(signature.getAmount()).isEqualTo(AMOUNT);
        assertThat(signature.getDleq().getE()).isEqualTo(issued.get("dleq").get("e").asText());
        assertThat(signature.getDleq().getS()).isEqualTo(issued.get("dleq").get("s").asText());
    }

    // --- harness ------------------------------------------------------------------------------

    /**
     * Boots an independent mint persistence context on the shared database, hands its vault to
     * {@code check}, and closes it. Nothing in process is shared with the mint that signed.
     */
    private void withFreshMintInstance(ThrowingConsumer<SignatureVaultService> check) throws Exception {
        AtomicReference<Exception> failure = new AtomicReference<>();
        freshMintInstance().run(context -> {
            SignatureVaultService vault = context.getBean(SignatureVaultService.class);
            assertThat(vault).isNotSameAs(mintContext.getBean(SignatureVaultService.class));
            try {
                check.accept(vault);
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
    }

    private ApplicationContextRunner freshMintInstance() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MintJpaAutoConfiguration.class))
                .withUserConfiguration(JpaSignatureVaultService.class)
                .withPropertyValues(
                        "cashu.mint.jpa.enabled=true",
                        "cashu.mint.jpa.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "cashu.mint.jpa.datasource.username=" + POSTGRES.getUsername(),
                        "cashu.mint.jpa.datasource.password=" + POSTGRES.getPassword(),
                        "cashu.mint.jpa.datasource.driver-class-name=org.postgresql.Driver",
                        "cashu.mint.jpa.flyway.enabled=false");
    }

    private ResponseEntity<String> mint(String quoteId) {
        return post("/v1/mint/bolt11", Map.of(
                "quote", quoteId,
                "outputs", List.of(Map.of("amount", AMOUNT, "id", TEST_KEYSET_ID, "B_", B_))));
    }

    private ResponseEntity<String> restoreOverRest() {
        return post("/v1/restore", Map.of(
                "outputs", List.of(Map.of("amount", AMOUNT, "id", TEST_KEYSET_ID, "B_", B_))));
    }

    private ResponseEntity<String> post(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            return restTemplate.postForEntity("http://localhost:" + port + path,
                    new HttpEntity<>(MAPPER.writeValueAsString(body), headers), String.class);
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to POST " + path, e);
        }
    }

    private static JsonNode firstSignature(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("status=%s body=%s", response.getStatusCode(), response.getBody())
                .isTrue();
        return MAPPER.readTree(response.getBody()).get("signatures").get(0);
    }

    private void savePaidQuote(String quoteId) {
        MintQuoteEntity quote = new MintQuoteEntity();
        quote.setQuoteId(quoteId);
        quote.setAmount(AMOUNT);
        quote.setUnit("sat");
        quote.setMintUrl("https://mint.it.example");
        quote.setPaymentMethod("bolt11");
        quote.setLifecycleState(LifecycleState.PAID);
        quote.setRequestHash("0".repeat(64));
        mintQuoteJpaRepository.save(quote);
    }

    private static BlindedMessage output() {
        return new BlindedMessage(AMOUNT, KeysetId.fromString(TEST_KEYSET_ID), PublicKey.fromString(B_), null);
    }

    private static PrivateKey privateKeyForAmount(Integer amount) {
        return PrivateKey.fromString(String.format("%064x", BigInteger.valueOf(amount)));
    }

    private static Mint mintWithKeys() {
        Mint mint = new Mint(TEST_MINT_UUID.toString());
        Keys keys = new Keys();
        keys.put(BigInteger.valueOf(AMOUNT), PrivateKey.derivePublicKey(privateKeyForAmount(AMOUNT)));
        mint.addKeySet(KeySet.builder().id(TEST_KEYSET_ID).unit("sat").keys(keys).build());
        return mint;
    }

    /** A check against a vault that may throw the protocol's checked exception. */
    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }
}
