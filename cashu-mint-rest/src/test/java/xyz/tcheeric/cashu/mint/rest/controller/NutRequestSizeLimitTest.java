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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The declared size limits on the NUT request bodies, checked over HTTP.
 *
 * <p>Companion to {@link RequestBodyValidationRuleTest}, which enforces that every constrained
 * {@code @RequestBody} carries {@code @Valid}. That rule is structural; these tests confirm the
 * annotation has the effect it is supposed to have, because "the annotation is present" and "the
 * limit is enforced" are different claims and only the second one matters.
 *
 * <p>`/v1/swap` and `/v1/mint` additionally carry in-task `SecurityLimits` checks, so they were
 * bounded even before `@Valid` was applied. `/v1/melt` had neither, which is why it is covered
 * here explicitly.
 */
class NutRequestSizeLimitTest {

    private static final String POINT =
            "02599b9ea0a1ad4143706c2a5a4a568ce442dd4313e1cf1f7f0b58a317c1a355ee";
    private static final int OVER_LIMIT = 1001;

    private MockMvc mockMvc;
    private SignatureVaultService signatureVaultService;
    private MintLoadService mintLoadService;

    @BeforeEach
    void setUp() {
        signatureVaultService = mock(SignatureVaultService.class);
        mintLoadService = mock(MintLoadService.class);

        CashuController<Secret> controller = new CashuController<>(
                mock(NUT06.class),
                mintLoadService,
                signatureVaultService,
                null,
                mock(MintVaultService.class),
                mock(ProofVaultService.class));

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(new LocalValidatorFactoryBean())
                .build();
    }

    /** An over-sized swap is refused before any mint is loaded or any proof looked up. */
    @Test
    void swapRefusesMoreOutputsThanTheMaximum() throws Exception {
        String body = "{\"inputs\":[],\"outputs\":[" + blindedMessages(OVER_LIMIT) + "]}";
        mockMvc.perform(post("/v1/swap").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(mintLoadService);
    }

    /** Same for mint. */
    @Test
    void mintRefusesMoreOutputsThanTheMaximum() throws Exception {
        String body = "{\"quote\":\"q-1\",\"outputs\":[" + blindedMessages(OVER_LIMIT) + "]}";
        mockMvc.perform(post("/v1/mint/bolt11").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(mintLoadService);
    }

    /**
     * Melt is the one that had no bound at all — no {@code @Valid} and, unlike swap and mint, no
     * in-task {@code SecurityLimits} check either. Its inputs were limited only by the request
     * body cap.
     */
    @Test
    void meltRefusesMoreInputsThanTheMaximum() throws Exception {
        String body = "{\"quote\":\"q-1\",\"inputs\":[" + proofs(OVER_LIMIT) + "]}";
        mockMvc.perform(post("/v1/melt/bolt11").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(mintLoadService);
    }

    /** Restore, the endpoint the original High finding was reported against. */
    @Test
    void restoreRefusesMoreOutputsThanTheMaximum() throws Exception {
        String body = "{\"outputs\":[" + blindedMessages(OVER_LIMIT) + "]}";
        mockMvc.perform(post("/v1/restore").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(signatureVaultService);
    }

    /** Checkstate, the other half of the original finding. */
    @Test
    void checkStateRefusesMoreSecretsThanTheMaximum() throws Exception {
        StringBuilder ys = new StringBuilder();
        for (int i = 0; i < OVER_LIMIT; i++) {
            if (i > 0) {
                ys.append(',');
            }
            ys.append('"').append(POINT).append('"');
        }
        mockMvc.perform(post("/v1/checkstate").contentType(APPLICATION_JSON)
                        .content("{\"Ys\":[" + ys + "]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(mintLoadService);
    }

    private static String blindedMessages(int count) {
        StringBuilder json = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"amount\":1,\"id\":\"009a1f293253e41e\",\"B_\":\"")
                    .append(POINT).append("\"}");
        }
        return json.toString();
    }

    private static String proofs(int count) {
        StringBuilder json = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"amount\":1,\"id\":\"009a1f293253e41e\",\"secret\":\"s")
                    .append(i).append("\",\"C\":\"").append(POINT).append("\"}");
        }
        return json.toString();
    }
}
