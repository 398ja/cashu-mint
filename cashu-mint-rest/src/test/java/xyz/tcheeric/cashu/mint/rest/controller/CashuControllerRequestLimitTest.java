package xyz.tcheeric.cashu.mint.rest.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.mint.proto.nut.NUT06;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.entities.rest.nut07.PostCheckStateRequest;
import xyz.tcheeric.cashu.entities.rest.nut09.PostRestoreRequest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The declared request-size limits on the two unauthenticated lookup endpoints, checked over
 * HTTP rather than by calling the task directly.
 *
 * <p>{@code PostRestoreRequest.MAX_OUTPUTS} and {@code PostCheckStateRequest.MAX_SECRETS} are
 * Bean Validation {@code @Size} constraints, and a constraint is only worth something where
 * something applies it. They were declared and never applied: the controller methods bound the
 * body without {@code @Valid}, and the module carried no validator to apply them with. Both
 * endpoints perform one vault lookup per element, so the effective bound was the request body
 * size — which bounds bytes, not element count.
 *
 * <p>These tests drive the real Spring MVC argument resolver with a real validator attached, so
 * they fail if either half of the fix is removed: the {@code @Valid} annotation, or the
 * validation starter that provides the validator. The task-level tests in
 * {@code cashu-mint-protocol} cover the defence-in-depth check underneath; this is the web
 * contract itself.
 *
 * <p>Every assertion also requires that no vault was consulted. A 400 returned after the vault
 * work is already done would leave the amplification exactly as it was.
 */
class CashuControllerRequestLimitTest {

    private MockMvc mockMvc;
    private SignatureVaultService signatureVaultService;
    private MintLoadService mintLoadService;
    private ProofVaultService proofVaultService;
    private MintVaultService mintVaultService;

    @BeforeEach
    void setUp() {
        signatureVaultService = mock(SignatureVaultService.class);
        mintLoadService = mock(MintLoadService.class);
        proofVaultService = mock(ProofVaultService.class);
        mintVaultService = mock(MintVaultService.class);

        CashuController<Secret> controller = new CashuController<>(
                mock(NUT06.class),
                mintLoadService,
                signatureVaultService,
                null,
                mintVaultService,
                proofVaultService);

        // A real validator, because the point of these tests is that one is present and wired.
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(new LocalValidatorFactoryBean())
                .build();
    }

    /**
     * Confirms /v1/restore refuses more outputs than the declared maximum with a 400, before any
     * vault lookup happens.
     */
    @Test
    void restoreRefusesMoreOutputsThanTheMaximum() throws Exception {
        mockMvc.perform(post("/v1/restore")
                        .contentType(APPLICATION_JSON)
                        .content(blindedMessages(PostRestoreRequest.MAX_OUTPUTS + 1)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(signatureVaultService);
    }

    /**
     * Confirms /v1/checkstate refuses more Ys than the declared maximum with a 400, before any
     * mint is loaded or any proof looked up. The merge runs this list once per mint, so the
     * unbounded case is multiplied before it reaches the vault.
     *
     * <p>This is the case where {@code @Valid} is load-bearing on its own. The defence-in-depth
     * check lives in {@code CheckStateTask}, which {@code CrossMintCheckStateMerger} only reaches
     * once it has a mint to query — so the number of Ys is unbounded across mint loading itself.
     * Removing {@code @Valid} was observed to turn this assertion from 400 into 200 while the
     * restore assertion still passed, which is precisely why the limit is enforced in both layers
     * rather than either alone.
     */
    @Test
    void checkStateRefusesMoreSecretsThanTheMaximum() throws Exception {
        mockMvc.perform(post("/v1/checkstate")
                        .contentType(APPLICATION_JSON)
                        .content(curvePoints(PostCheckStateRequest.MAX_SECRETS + 1)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(mintLoadService, proofVaultService, mintVaultService);
    }

    /**
     * Confirms a request at the limit is still accepted, so the constraint rejects only what it
     * is meant to. Without this, a fix that rejected every request would pass the tests above.
     */
    @Test
    void restoreAcceptsExactlyTheMaximumOutputs() throws Exception {
        mockMvc.perform(post("/v1/restore")
                        .contentType(APPLICATION_JSON)
                        .content(blindedMessages(PostRestoreRequest.MAX_OUTPUTS)))
                .andExpect(status().isOk());
    }

    /** A restore body carrying {@code count} well-formed blinded messages. */
    private static String blindedMessages(int count) {
        StringBuilder json = new StringBuilder("{\"outputs\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"amount\":1,\"id\":\"009a1f293253e41e\",\"B_\":\"")
                    .append(COMPRESSED_POINT)
                    .append("\"}");
        }
        return json.append("]}").toString();
    }

    /** A checkstate body carrying {@code count} well-formed curve points. */
    private static String curvePoints(int count) {
        StringBuilder json = new StringBuilder("{\"Ys\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(COMPRESSED_POINT).append('"');
        }
        return json.append("]}").toString();
    }

    private static final String COMPRESSED_POINT =
            "02599b9ea0a1ad4143706c2a5a4a568ce442dd4313e1cf1f7f0b58a317c1a355ee";
}
