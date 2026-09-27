package xyz.tcheeric.cashu.mint.rest.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.mint.proto.nut.NUT06;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
    /** NUT-00 {@code too_many_inputs} (11014): a wallet reads this as "split the request". */
    private static final int TOO_MANY_INPUTS = CashuErrorCode.too_many_inputs.getCode();
    /** NUT-00 {@code too_many_outputs} (11015). */
    private static final int TOO_MANY_OUTPUTS = CashuErrorCode.too_many_outputs.getCode();

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

    /** An over-sized swap is refused as too_many_outputs before any mint is loaded or any proof looked up. */
    @Test
    void swapRefusesMoreOutputsThanTheMaximum() throws Exception {
        String body = "{\"inputs\":[],\"outputs\":[" + blindedMessages(OVER_LIMIT) + "]}";
        mockMvc.perform(post("/v1/swap").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(TOO_MANY_OUTPUTS));
        verifyNoInteractions(mintLoadService);
    }

    /**
     * cashu-mint#521: a swap with too many inputs answers too_many_inputs, the code SwapTask's own
     * check uses, not the internal_error a failed validation used to produce.
     */
    @Test
    void swapRefusesMoreInputsThanTheMaximumAsTooManyInputs() throws Exception {
        String body = "{\"inputs\":[" + proofs(OVER_LIMIT) + "],\"outputs\":[" + blindedMessages(1) + "]}";
        mockMvc.perform(post("/v1/swap").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(TOO_MANY_INPUTS));
        verifyNoInteractions(mintLoadService);
    }

    /** Same for mint: too_many_outputs. */
    @Test
    void mintRefusesMoreOutputsThanTheMaximum() throws Exception {
        String body = "{\"quote\":\"q-1\",\"outputs\":[" + blindedMessages(OVER_LIMIT) + "]}";
        mockMvc.perform(post("/v1/mint/bolt11").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(TOO_MANY_OUTPUTS));
        verifyNoInteractions(mintLoadService);
    }

    /**
     * Melt is the one that had no bound at all — no {@code @Valid} and, unlike swap and mint, no
     * in-task {@code SecurityLimits} check either. Its inputs were limited only by the request
     * body cap. cashu-mint#521: it now answers too_many_inputs, so a wallet can tell it apart
     * from any other malformed request and merge its proofs.
     */
    @Test
    void meltRefusesMoreInputsThanTheMaximum() throws Exception {
        String body = "{\"quote\":\"q-1\",\"inputs\":[" + proofs(OVER_LIMIT) + "]}";
        mockMvc.perform(post("/v1/melt/bolt11").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(TOO_MANY_INPUTS));
        verifyNoInteractions(mintLoadService);
    }

    /** A melt carrying too many NUT-08 change outputs answers too_many_outputs. */
    @Test
    void meltRefusesMoreChangeOutputsThanTheMaximum() throws Exception {
        String body = "{\"quote\":\"q-1\",\"inputs\":[" + proofs(1) + "],\"outputs\":["
                + blindedMessages(OVER_LIMIT) + "]}";
        mockMvc.perform(post("/v1/melt/bolt11").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(TOO_MANY_OUTPUTS));
        verifyNoInteractions(mintLoadService);
    }

    /**
     * Only a list over its size limit is "too many". A swap with no inputs at all violates a different
     * constraint on the same field, and must not be answered as if it had too many.
     */
    @Test
    void anEmptyInputListIsNotTooManyInputs() throws Exception {
        String body = "{\"inputs\":[],\"outputs\":[" + blindedMessages(1) + "]}";
        mockMvc.perform(post("/v1/swap").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(CashuErrorCode.internal_error.getCode()));
    }

    /** Restore, the endpoint the original High finding was reported against: too_many_outputs. */
    @Test
    void restoreRefusesMoreOutputsThanTheMaximum() throws Exception {
        String body = "{\"outputs\":[" + blindedMessages(OVER_LIMIT) + "]}";
        mockMvc.perform(post("/v1/restore").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(TOO_MANY_OUTPUTS));
        verifyNoInteractions(signatureVaultService);
    }

    /**
     * Checkstate, the other half of the original finding. Its Ys are neither inputs nor outputs, and
     * NUT-00 has no code for them, so it stays a plain 400.
     */
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
