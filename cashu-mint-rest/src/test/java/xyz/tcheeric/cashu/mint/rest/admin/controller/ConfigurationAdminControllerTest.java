package xyz.tcheeric.cashu.mint.rest.admin.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import xyz.tcheeric.cashu.mint.rest.admin.config.AdminApiConfiguration;
import xyz.tcheeric.cashu.mint.rest.admin.config.AdminAuthenticationFilter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConfigurationAdminController.class)
@Import(AdminApiConfiguration.class)
@TestPropertySource(properties = "admin.security.api-token=test-token")
class ConfigurationAdminControllerTest {

    private static final String ADMIN_TOKEN = "test-token";

    @Autowired
    private MockMvc mockMvc;

    // Ensures configuration preview rejects requests without the admin token.
    @Test
    @DisplayName("Configuration preview requires authentication")
    void previewRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/admin/configuration/mints/mint-001/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestedBy": {"id":"ops","displayName":"Ops"},
                                  "proposedConfiguration": {"limits":{"max":1}},
                                  "baseRevisionId": "rev-0"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    // Confirms apply route is registered and responds with 501 for future implementation.
    @Test
    @DisplayName("Configuration apply returns 501")
    void applyReturnsNotImplemented() throws Exception {
        mockMvc.perform(post("/admin/configuration/mints/mint-001/apply")
                        .header(AdminAuthenticationFilter.ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestedBy": {"id":"ops","displayName":"Ops"},
                                  "proposedConfiguration": {"limits":{"max":1}},
                                  "changeSummary": "increase limit"
                                }
                                """))
                .andExpect(status().isNotImplemented());
    }
}
