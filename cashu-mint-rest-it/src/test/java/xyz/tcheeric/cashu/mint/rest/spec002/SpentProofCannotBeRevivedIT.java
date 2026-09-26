package xyz.tcheeric.cashu.mint.rest.spec002;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.util.SecretUtil;
import xyz.tcheeric.cashu.mint.jpa.repository.MeltSagaJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.MeltSagaTransitionJpaRepository;
import xyz.tcheeric.cashu.mint.proto.domain.MeltSagaState;
import xyz.tcheeric.cashu.mint.proto.domain.PaymentOutcome;
import xyz.tcheeric.cashu.mint.proto.ports.LightningPaymentPort;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.rest.spec001.AbstractMintDurableIT;
import xyz.tcheeric.cashu.mint.rest.spec002.support.MeltProofFixture;
import xyz.tcheeric.cashu.mint.rest.spec002.support.MockLightningPaymentPort;
import xyz.tcheeric.cashu.vault.api.VaultClientFactory;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * cashu-mint#492 / cashu-vault#154: a proof the mint has spent stays spent, against the real vault.
 *
 * <p>The vault is the mint's only record of spent proofs. Before cashu-vault 0.15.0 anyone holding
 * the vault token could delete a SPENT row, or re-post it with its state set back to UNSPENT, and
 * the mint would then accept the same proof a second time. And until this change the mint's own
 * melt burn depended on exactly that overwrite, so the vault could not close it without breaking
 * melt.
 *
 * <p>Every other melt IT mocks {@code ProofVaultService} and so cannot see what the vault allows.
 * This one starts the real cashu-vault server as a separate process, at the version the BOM
 * resolves for the client, on its own PostgreSQL, and points the mint's unmodified vault client at
 * it. It then plays the attacker: after a melt spends a proof, it tries to delete and overwrite that
 * proof's row with the mint's own token, and finally presents the proof to the mint again.
 *
 * <p>A swap reports a spent input as {@code 11001} from its spending condition. A melt has no
 * separate spent check: the SPENT row cannot be claimed into its hold, so it fails closed with
 * {@code proofs_not_bound} before paying, which is the property that matters here.
 */
@Import(SpentProofCannotBeRevivedIT.PaymentPortConfig.class)
class SpentProofCannotBeRevivedIT extends AbstractMintDurableIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String VAULT_TOKEN = "it-vault-token-" + UUID.randomUUID();
    /**
     * The vault server to run. Overridable with {@code -Dcashu.vault.server.jar=...} so the test
     * can be pointed at an older vault to show that it catches the double spend there.
     */
    private static final Path VAULT_JAR = Path.of(
            System.getProperty("cashu.vault.server.jar", "target/cashu-vault-server.jar"));
    private static final Duration VAULT_STARTUP_TIMEOUT = Duration.ofSeconds(120);
    private static final int VERIFY_PROOF_ALREADY_USED = 11001;

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> VAULT_DB =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("cashu_vault_it");

    private static Process vaultProcess;
    private static String vaultBaseUrl;
    private static String originalVaultBaseUrl;
    private static MintProtocolService originalProtocolService;

    @Value("${local.server.port}")
    int port;

    @MockBean
    MintLoadService mintLoadService;

    @Autowired
    LightningPaymentPort paymentPort;

    @Autowired
    MeltSagaJpaRepository sagas;

    @Autowired
    MeltSagaTransitionJpaRepository transitions;

    private final RestTemplate rest = new RestTemplate();

    @TestConfiguration
    static class PaymentPortConfig {
        @Bean
        @Primary
        LightningPaymentPort mockLightningPaymentPort() {
            return new MockLightningPaymentPort();
        }
    }

    static {
        VAULT_DB.start();
        vaultBaseUrl = "http://localhost:" + freePort();
        System.setProperty("vault.api.token", VAULT_TOKEN);
        vaultProcess = startVault();
    }

    @DynamicPropertySource
    static void vaultToken(DynamicPropertyRegistry registry) {
        registry.add("vault.api.token", () -> VAULT_TOKEN);
    }

    /**
     * Points the mint's vault clients at this class's vault.
     *
     * <p>They are static singletons that fix their base URL when first created, and in a shared
     * surefire JVM an earlier IT has usually created them already, against the default
     * {@code localhost:3333}. So the live instances are re-pointed, and restored afterwards.
     */
    @BeforeAll
    static void pointTheMintAtThisVault() {
        originalVaultBaseUrl = VaultClientFactory.proofClient().getBaseUrl();
        VaultClientFactory.proofClient().setBaseUrl(vaultBaseUrl);
        VaultClientFactory.getClient(MintEntity.class).setBaseUrl(vaultBaseUrl);
    }

    @BeforeAll
    static void stubSigningKeysAndGateway() {
        originalProtocolService = MintProtocolServiceFactory.getInstance();
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.getName()).thenReturn("mock-gateway");
        when(gateway.getAmount(anyString())).thenReturn(100);
        when(gateway.getRequest(anyString())).thenReturn("lnbc100...");
        when(gateway.getFeeReserve(anyString())).thenReturn(5);

        MintProtocolService stub = Mockito.spy(originalProtocolService);
        Mockito.doReturn(gateway).when(stub).createGateway(any(PaymentMethod.class));
        Mockito.doReturn(gateway).when(stub).createGateway(any(PaymentMethod.class), anyString());
        try {
            Mockito.doAnswer(inv -> MeltProofFixture.privateKeyFor(inv.getArgument(1, Integer.class)))
                    .when(stub).getPrivateKey(anyString(), anyInt(), any(Mint.class));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
        MintProtocolServiceFactory.setInstance(stub);
    }

    @AfterAll
    static void stopVault() {
        MintProtocolServiceFactory.setInstance(originalProtocolService);
        VaultClientFactory.proofClient().setBaseUrl(originalVaultBaseUrl);
        VaultClientFactory.getClient(MintEntity.class).setBaseUrl(originalVaultBaseUrl);
        System.clearProperty("vault.api.token");
        if (vaultProcess != null) {
            vaultProcess.destroy();
        }
        VAULT_DB.stop();
    }

    @BeforeEach
    void registerTheMintWithTheVault() throws Exception {
        Mint mint = MeltProofFixture.mintWithKeys();
        when(mintLoadService.load(any(UUID.class), Mockito.anyBoolean())).thenReturn(mint);
        when(mintLoadService.load(Mockito.anyBoolean())).thenReturn(List.of(mint));
        when(mintLoadService.keySet(anyString())).thenReturn(mint.getKeySets().iterator().next());
        when(mintLoadService.keySets()).thenReturn(List.copyOf(mint.getKeySets()));
        when(mintLoadService.keySets(false)).thenReturn(List.copyOf(mint.getKeySets()));
        when(mintLoadService.keySets(true)).thenReturn(List.of());
        ensureVaultKnowsMint(MeltProofFixture.MINT_UUID);
        transitions.deleteAllInBatch();
        sagas.deleteAllInBatch();
        ((MockLightningPaymentPort) paymentPort).reset();
    }

    // A melt against the real vault completes, and afterwards the vault records its inputs as
    // SPENT. This is the mint no longer depending on overwriting a row: the burn goes through the
    // hold it took before paying.
    @Test
    void aPaidMeltRecordsItsInputsSpentInTheRealVault() throws Exception {
        Map<String, Object> first = MeltProofFixture.proofJson(64);
        Map<String, Object> second = MeltProofFixture.proofJson(64);

        ResponseEntity<String> melt = meltPaying("quote-spend", List.of(first, second));

        assertThat(melt.getStatusCode().is2xxSuccessful()).as("body=%s", melt.getBody()).isTrue();
        assertThat(sagas.findByQuoteId("quote-spend").orElseThrow().getCurrentState())
                .isEqualTo(MeltSagaState.COMPLETED);
        assertThat(vaultStateOf(first)).isEqualTo("SPENT");
        assertThat(vaultStateOf(second)).isEqualTo("SPENT");
    }

    // The acceptance test of both issues. Spend a proof, then use the mint's own vault token to
    // try to erase that spend by deleting the row and by re-posting it as UNSPENT. Both must be
    // refused and the row must still say SPENT. Presented to the mint again, the proof must be
    // rejected as already spent by a swap, and refused by a melt without any payment going out.
    @Test
    void aSpentProofCannotBeErasedAndSpentAgain() throws Exception {
        Map<String, Object> proof = MeltProofFixture.proofJson(64);
        Map<String, Object> other = MeltProofFixture.proofJson(64);
        assertThat(meltPaying("quote-first", List.of(proof, other)).getStatusCode().is2xxSuccessful())
                .isTrue();
        Map<String, Object> row = vaultRowOf(proof);
        assertThat(row.get("state")).isEqualTo("SPENT");
        String rowId = (String) row.get("id");

        HttpStatusCode deleted = vaultCall(HttpMethod.DELETE, "/vault/proof/" + rowId, null);
        assertThat(deleted.is4xxClientError()).as("DELETE answered %s", deleted).isTrue();

        Map<String, Object> revived = new java.util.HashMap<>(row);
        revived.put("state", "UNSPENT");
        HttpStatusCode overwritten = vaultCall(HttpMethod.POST, "/vault/proof", revived);
        assertThat(overwritten.is4xxClientError()).as("overwrite answered %s", overwritten).isTrue();

        revived.put("state", "SPENT");
        HttpStatusCode sameStateOverwrite = vaultCall(HttpMethod.POST, "/vault/proof", revived);
        assertThat(sameStateOverwrite.is4xxClientError())
                .as("re-posting an existing id is refused whatever the state").isTrue();

        assertThat(vaultStateOf(proof)).isEqualTo("SPENT");

        ResponseEntity<String> swapAgain = postSwap(List.of(proof),
                List.of(MeltProofFixture.blindedMessageForChange(64, 1)));
        assertThat(swapAgain.getStatusCode().is4xxClientError()).as("body=%s", swapAgain.getBody()).isTrue();
        assertThat(swapAgain.getBody())
                .as("the swap reports the proof as already spent")
                .contains("\"code\":" + VERIFY_PROOF_ALREADY_USED);

        ((MockLightningPaymentPort) paymentPort).enqueuePay(
                new PaymentOutcome.Success("preimage-again", 100L, 0L, "evt-again"));
        ResponseEntity<String> meltAgain = postMelt("quote-again",
                List.of(proof, MeltProofFixture.proofJson(64)));
        assertThat(meltAgain.getStatusCode().isError()).as("body=%s", meltAgain.getBody()).isTrue();
        assertThat(((MockLightningPaymentPort) paymentPort).payCallsFor("quote-again"))
                .as("a spent proof must never buy a second payment").isZero();
    }

    private ResponseEntity<String> meltPaying(String quoteId, List<Map<String, Object>> inputs) {
        ((MockLightningPaymentPort) paymentPort).enqueuePay(
                new PaymentOutcome.Success("preimage-" + quoteId, 100L, 0L, "evt-" + quoteId));
        return postMelt(quoteId, inputs);
    }

    private ResponseEntity<String> postMelt(String quoteId, List<Map<String, Object>> inputs) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            String body = MAPPER.writeValueAsString(Map.of("quote", quoteId, "inputs", inputs));
            return rest.postForEntity("http://localhost:" + port + "/v1/melt/bolt11",
                    new HttpEntity<>(body, headers), String.class);
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(new String(e.getResponseBodyAsByteArray(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseEntity<String> postSwap(List<Map<String, Object>> inputs,
                                            List<BlindedMessage> outputs) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            List<Map<String, Object>> outputJson = outputs.stream()
                    .map(output -> Map.<String, Object>of(
                            "amount", output.getAmount(),
                            "id", output.getKeySetId().toString(),
                            "B_", output.getBlindedMessage().toString()))
                    .toList();
            String body = MAPPER.writeValueAsString(Map.of("inputs", inputs, "outputs", outputJson));
            return rest.postForEntity("http://localhost:" + port + "/v1/swap",
                    new HttpEntity<>(body, headers), String.class);
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(new String(e.getResponseBodyAsByteArray(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String vaultStateOf(Map<String, Object> proof) throws IOException {
        return (String) vaultRowOf(proof).get("state");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> vaultRowOf(Map<String, Object> proof) throws IOException {
        String y = SecretUtil.toYFromString((String) proof.get("secret"));
        ResponseEntity<String> response = rest.exchange(
                vaultBaseUrl + "/vault/proof/mint/" + MeltProofFixture.MINT_UUID + "/secret/" + y,
                HttpMethod.GET, new HttpEntity<>(vaultHeaders()), String.class);
        assertThat(response.getBody()).as("the vault has no row for this proof").isNotBlank();
        return MAPPER.readValue(response.getBody(), Map.class);
    }

    private HttpStatusCode vaultCall(HttpMethod method, String path, Object body) throws IOException {
        HttpHeaders headers = vaultHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String json = body == null ? null : MAPPER.writeValueAsString(body);
        try {
            return rest.exchange(vaultBaseUrl + path, method, new HttpEntity<>(json, headers), String.class)
                    .getStatusCode();
        } catch (HttpStatusCodeException e) {
            return e.getStatusCode();
        }
    }

    private void ensureVaultKnowsMint(UUID mintId) throws IOException {
        HttpStatusCode status = vaultCall(HttpMethod.POST, "/vault/mint", Map.of("id", mintId.toString()));
        assertThat(status.is2xxSuccessful() || status.value() == 409)
                .as("registering the mint with the vault answered %s", status).isTrue();
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
        String port = vaultBaseUrl.substring(vaultBaseUrl.lastIndexOf(':') + 1);
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", VAULT_JAR.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .redirectOutput(Path.of("target", "cashu-vault-server.log").toFile());
        Map<String, String> env = builder.environment();
        env.put("SPRING_PROFILES_ACTIVE", "prod");
        env.put("DATABASE_URL", VAULT_DB.getJdbcUrl());
        env.put("DATABASE_USER", VAULT_DB.getUsername());
        env.put("DATABASE_PASSWORD", VAULT_DB.getPassword());
        env.put("VAULT_API_TOKEN", VAULT_TOKEN);
        env.put("VAULT_BACKEND", "jpa");
        env.put("cashu_vault_port", port);
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
                        + "target/cashu-vault-server.log");
            }
            try {
                if (probe.getForEntity(vaultBaseUrl + "/actuator/health", String.class)
                        .getStatusCode().is2xxSuccessful()) {
                    return;
                }
            } catch (RuntimeException notYetUp) {
                sleepBriefly();
            }
        }
        process.destroy();
        throw new IllegalStateException("cashu-vault did not become healthy within "
                + VAULT_STARTUP_TIMEOUT + "; see target/cashu-vault-server.log");
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
