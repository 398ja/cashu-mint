package xyz.tcheeric.cashu.mint.rest.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import xyz.tcheeric.cashu.entities.rest.nut07.PostCheckStateRequest;
import xyz.tcheeric.cashu.entities.rest.nut09.PostRestoreRequest;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The request-size limits on {@code /v1/restore} and {@code /v1/checkstate}, enforced by the
 * <em>production</em> wiring rather than by a validator the test supplied.
 *
 * <p>Note what this IT does differently from
 * {@code CashuControllerRequestLimitTest} in {@code cashu-mint-rest}. That test builds its
 * MockMvc with {@code standaloneSetup(...).setValidator(new LocalValidatorFactoryBean())}, so it
 * proves that {@code @Valid} rejects an over-sized body <em>when a validator exists</em> —
 * it supplies one itself, so it cannot speak to whether the deployed application has one. This
 * IT boots the real context and therefore uses only the validator Spring Boot auto-configures.
 *
 * <p>Measured, not assumed: removing {@code @Valid} makes these assertions fail, so the
 * annotation is what they test. Removing {@code spring-boot-starter-validation} from
 * {@code cashu-mint-rest} does <em>not</em> make them fail, because a validator also arrives
 * transitively via {@code cashu-mint-jpa}. The explicit dependency is kept for the reason given
 * in that pom — a runtime-scoped persistence module is not where a web-layer validator should
 * come from — but this IT is honest about not being the thing that guards it.
 *
 * <p>The assertions also check the response <em>shape</em>, not just the status. A NUT client
 * reads {@code code} and {@code detail}; Spring's default validation body carries neither, so a
 * bare 400 would be a status a wallet cannot interpret.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/07.md">NUT-07</a>
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/09.md">NUT-09</a>
 */
@SpringBootTest(classes = xyz.tcheeric.cashu.mint.rest.CashuMintRestApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"voucher.enabled=false"})
class RequestSizeLimitIT {

    private static final String COMPRESSED_POINT =
            "02599b9ea0a1ad4143706c2a5a4a568ce442dd4313e1cf1f7f0b58a317c1a355ee";

    @Autowired
    private MockMvc mockMvc;

    /**
     * Confirms the auto-configured validator rejects an over-sized restore body with a 400
     * carrying the protocol's error shape.
     */
    @Test
    void restoreRefusesMoreOutputsThanTheMaximum() throws Exception {
        mockMvc.perform(post("/v1/restore")
                        .contentType(APPLICATION_JSON)
                        .content(restoreBody(PostRestoreRequest.MAX_OUTPUTS + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.detail").exists());
    }

    /**
     * Confirms the same for checkstate. This is the endpoint whose only bound before the
     * cross-mint merge fans out is the {@code @Valid} annotation, so the auto-configured
     * validator has to be present for it to hold.
     */
    @Test
    void checkStateRefusesMoreSecretsThanTheMaximum() throws Exception {
        mockMvc.perform(post("/v1/checkstate")
                        .contentType(APPLICATION_JSON)
                        .content(checkStateBody(PostCheckStateRequest.MAX_SECRETS + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.detail").exists());
    }

    /**
     * Confirms an empty output list is refused, covering the {@code @NotEmpty} constraint that
     * sits beside the size limit on the same field.
     */
    @Test
    void restoreRefusesAnEmptyOutputList() throws Exception {
        mockMvc.perform(post("/v1/restore")
                        .contentType(APPLICATION_JSON)
                        .content("{\"outputs\":[]}"))
                .andExpect(status().isBadRequest());
    }

    private static String restoreBody(int count) {
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

    private static String checkStateBody(int count) {
        StringBuilder json = new StringBuilder("{\"Ys\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(COMPRESSED_POINT).append('"');
        }
        return json.append("]}").toString();
    }
}
