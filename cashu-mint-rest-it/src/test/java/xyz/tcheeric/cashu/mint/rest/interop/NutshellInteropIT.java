package xyz.tcheeric.cashu.mint.rest.interop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.images.PullPolicy;
import org.testcontainers.utility.MountableFile;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.mint.proto.domain.PaymentOutcome;
import xyz.tcheeric.cashu.mint.proto.ports.LightningPaymentPort;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.rest.spec001.AbstractMintDurableIT;
import xyz.tcheeric.cashu.mint.rest.spec002.support.MeltProofFixture;
import xyz.tcheeric.payment.adapter.core.common.Gateway;
import xyz.tcheeric.payment.adapter.ln.dummy.DummyGateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * NUT compliance audit finding M10 — interoperability against an external
 * implementation.
 *
 * <p>Published NUT vectors cannot settle whether our ecash is spendable
 * elsewhere: they are self-consistent by construction. Only a foreign
 * implementation can. This test drives the reference Python implementation
 * (Nutshell) through the full NUT-04 → NUT-03 → NUT-05 sequence — mint, swap,
 * melt — against a mint booted in this JVM.
 *
 * <p>The instrument is deliberately loud. If the flow breaks at any stage the
 * assertion message carries the stage the wallet reached and the wallet's own
 * error, because that is the diagnostic the audit's downstream milestones
 * (starting with the {@code hash_to_curve} secret encoding) are waiting on.
 *
 * <p>Lightning is the dummy adapter: quotes are created and reported paid
 * without a node, so mint and swap need no external payment infrastructure,
 * and the melt leg is answered by a scripted {@link LightningPaymentPort}.
 *
 * <p>Skipping is a last resort and never silent: without a Docker daemon the
 * test fails rather than passing vacuously, so a broken environment cannot
 * hide an interoperability regression.
 */
@Import(NutshellInteropIT.ScriptedPaymentConfig.class)
class NutshellInteropIT extends AbstractMintDurableIT {

    private static final Logger log = LoggerFactory.getLogger(NutshellInteropIT.class);

    /** Pinned so a Nutshell release cannot silently change what this measures. */
    private static final String NUTSHELL_IMAGE = "cashubtc/nutshell:0.16.5";
    private static final String FLOW_SCRIPT = "interop/nutshell_flow.py";
    private static final String CONTAINER_SCRIPT_PATH = "/interop/nutshell_flow.py";
    private static final String RESULT_PREFIX = "RESULT_JSON ";
    /** Output-split modes understood by the flow script. */
    private static final String WALLET_CHOSEN_SPLIT = "wallet";
    private static final String CANONICAL_SPLIT = "canonical";
    private static final Duration FLOW_TIMEOUT = Duration.ofMinutes(3);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${local.server.port}")
    int port;

    @MockBean
    MintLoadService mintLoadService;

    @MockBean
    ProofVaultService proofVaultService;

    @MockBean
    MintVaultService mintVaultService;

    @Autowired
    LightningPaymentPort paymentPort;

    private static MintProtocolService originalProtocolService;

    /** Answers the melt leg without a Lightning node. */
    @TestConfiguration
    static class ScriptedPaymentConfig {
        @Bean
        @Primary
        LightningPaymentPort alwaysPayingLightningPaymentPort() {
            return new AlwaysPayingLightningPaymentPort();
        }
    }

    @BeforeAll
    static void useDummyGatewayAndDeterministicKeys() throws Exception {
        originalProtocolService = MintProtocolServiceFactory.getInstance();
        Gateway dummyGateway = new DummyGateway();

        MintProtocolService stub = Mockito.spy(originalProtocolService);
        Mockito.doReturn(dummyGateway).when(stub).createGateway(any(PaymentMethod.class));
        Mockito.doReturn(dummyGateway).when(stub).createGateway(any(PaymentMethod.class), anyString());
        Mockito.doAnswer(call -> MeltProofFixture.privateKeyFor(call.getArgument(1, Integer.class)))
                .when(stub).getPrivateKey(anyString(), anyInt(), any(Mint.class));
        Mockito.doAnswer(call -> MeltProofFixture.privateKeyFor(call.getArgument(1, Integer.class)))
                .when(stub).getPrivateKeyForSigning(anyString(), anyInt(), any(Mint.class));
        MintProtocolServiceFactory.setInstance(stub);
    }

    @AfterAll
    static void restoreProtocolService() {
        MintProtocolServiceFactory.setInstance(originalProtocolService);
    }

    @BeforeEach
    void publishKeysetAndStubVault() throws Exception {
        Mint mint = MeltProofFixture.mintWithKeys();
        when(mintLoadService.load(any(UUID.class), Mockito.anyBoolean())).thenReturn(mint);
        when(mintLoadService.load(Mockito.anyBoolean())).thenReturn(List.of(mint));
        when(mintLoadService.keySet(anyString())).thenReturn(mint.getKeySets().iterator().next());
        when(mintLoadService.keySets()).thenReturn(List.copyOf(mint.getKeySets()));
        // NUT-02 lists keysets from the archived and active sources separately;
        // the wallet bootstraps off the active one, so leave archived empty.
        when(mintLoadService.keySets(false)).thenReturn(List.copyOf(mint.getKeySets()));
        when(mintLoadService.keySets(true)).thenReturn(List.of());

        xyz.tcheeric.cashu.vault.db.model.MintEntity mintEntity =
                new xyz.tcheeric.cashu.vault.db.model.MintEntity();
        mintEntity.setId(UUID.fromString(mint.getId()));
        when(mintVaultService.retrieveMint(anyString())).thenReturn(mintEntity);
        when(proofVaultService.insertOrClaimForSaga(any(), anyString(), any(UUID.class)))
                .thenAnswer(call -> call.getArgument(0, List.class).size());
    }

    // A real wallet picks its own output denominations. This is the flow an
    // unmodified Nutshell performs against any mint, so it is the honest
    // measure of whether our ecash is usable elsewhere.
    @Test
    void nutshellCompletesMintSwapAndMeltWithItsOwnOutputSplit() {
        requireDockerDaemon();

        assertFlowCompleted(runNutshellFlow(WALLET_CHOSEN_SPLIT));
    }

    // Forcing the minimal-split denomination removes NUT-04 output-split
    // disagreement from the picture, so a failure here is a defect in swap or
    // melt rather than in what the mint will accept as outputs.
    @Test
    void nutshellCompletesMintSwapAndMeltWithACanonicalOutputSplit() {
        requireDockerDaemon();

        assertFlowCompleted(runNutshellFlow(CANONICAL_SPLIT));
    }

    private static void assertFlowCompleted(JsonNode report) {
        assertThat(report.path("ok").asBoolean())
                .as("Nutshell (%s, split mode '%s') must complete mint -> swap -> melt against "
                                + "this mint. Reached stage '%s'. Wallet error: %s. Mint said: %s%n"
                                + "Full report: %s",
                        NUTSHELL_IMAGE,
                        report.path("split_mode").asText("unknown"),
                        report.path("stage").asText("unknown"),
                        report.path("error").asText("none"),
                        report.path("mint_response").asText("nothing"),
                        report)
                .isTrue();
        assertThat(settledState(report))
                .as("the melt must settle, not linger unpaid — report: %s", report)
                .isEqualTo("PAID");
    }

    /**
     * The wallet's melt state, reduced to the bare state name.
     *
     * <p>Nutshell renders the state as an enum, so the text can arrive either bare ({@code PAID})
     * or qualified ({@code MeltQuoteState.paid}). Comparing the bare name in upper case accepts
     * both without the substring match that would also accept {@code UNPAID}.
     */
    private static String settledState(JsonNode report) {
        String state = report.path("melt_state").asText("");
        return state.substring(state.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
    }

    /**
     * Fail loudly rather than skip. A silent skip on a machine without Docker
     * turns this instrument into a green tick that measures nothing, which is
     * exactly the failure mode the audit filed M10 to end. Documented in
     * docs/how-to/run-the-interoperability-test.md.
     */
    private static void requireDockerDaemon() {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            fail("INTEROP TEST CANNOT RUN: no Docker daemon reachable, so the external "
                    + "implementation (" + NUTSHELL_IMAGE + ") could not be started. This test is "
                    + "the only evidence that our ecash interoperates; a skip here would hide "
                    + "interoperability regressions. See docs/how-to/run-the-interoperability-test.md.");
        }
    }

    private JsonNode runNutshellFlow(String splitMode) {
        Testcontainers.exposeHostPorts(port);
        String mintUrl = "http://host.testcontainers.internal:" + port;
        StringBuilder containerOutput = new StringBuilder();

        try (PaidQuotePromoter promoter = PaidQuotePromoter.started(mintQuoteJpaRepository);
             GenericContainer<?> nutshell = new GenericContainer<>(NUTSHELL_IMAGE)
                .withImagePullPolicy(PullPolicy.defaultPolicy())
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource(FLOW_SCRIPT), CONTAINER_SCRIPT_PATH)
                .withEnv("PYTHONPATH", "/app")
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("python"))
                .withCommand(CONTAINER_SCRIPT_PATH, mintUrl, splitMode)
                .withLogConsumer((OutputFrame frame) -> containerOutput.append(frame.getUtf8String()))
                .withStartupCheckStrategy(
                        new org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy()
                                .withTimeout(FLOW_TIMEOUT))) {
            nutshell.start();
        } catch (RuntimeException containerFailure) {
            log.error("nutshell_interop container_failed output={}", containerOutput, containerFailure);
        }

        return parseReport(containerOutput.toString());
    }

    private static JsonNode parseReport(String containerOutput) {
        int start = containerOutput.lastIndexOf(RESULT_PREFIX);
        if (start < 0) {
            return fail("The Nutshell wallet produced no report. Container output:%n%s", containerOutput);
        }
        String json = containerOutput.substring(start + RESULT_PREFIX.length()).lines().findFirst().orElse("");
        try {
            return MAPPER.readTree(json);
        } catch (Exception unparseable) {
            return fail("Unparseable wallet report '%s'. Container output:%n%s", json, containerOutput);
        }
    }

    /** Settles every melt immediately, standing in for a Lightning node. */
    private static final class AlwaysPayingLightningPaymentPort implements LightningPaymentPort {

        @Override
        public PaymentOutcome pay(String quoteId, Duration timeout) {
            return new PaymentOutcome.Success("interop-preimage", 0L, 0L, "interop-" + quoteId);
        }

        @Override
        public PaymentOutcome checkStatus(String quoteId) {
            return new PaymentOutcome.Success("interop-preimage", 0L, 0L, "interop-" + quoteId);
        }
    }
}
