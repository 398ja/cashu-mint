package xyz.tcheeric.cashu.mint.admin.rest.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import xyz.tcheeric.cashu.mint.admin.rest.config.AdminApiConfiguration;
import xyz.tcheeric.cashu.mint.admin.rest.config.AdminAuthenticationFilter;
import xyz.tcheeric.cashu.mint.admin.rest.config.AdminRbacFilter;
import xyz.tcheeric.cashu.mint.admin.rest.service.AdminConfigurationService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConfigurationAdminController.class)
@Import({AdminApiConfiguration.class, AdminConfigurationService.class})
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

    // Ensures configuration apply requires the appropriate RBAC role when authenticated.
    @Test
    @DisplayName("Configuration apply requires role header")
    void applyRequiresRole() throws Exception {
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
                .andExpect(status().isForbidden());
    }

    // Validates that applying a configuration returns the resulting revision metadata.
    @Test
    @DisplayName("Configuration apply returns revision")
    void applyReturnsConfigurationResponse() throws Exception {
        mockMvc.perform(post("/admin/configuration/mints/mint-001/apply")
                        .header(AdminAuthenticationFilter.ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                        .header(AdminRbacFilter.ADMIN_ROLES_HEADER, "MINT_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestedBy": {"id":"ops","displayName":"Ops"},
                                  "proposedConfiguration": {"limits":{"max":1}},
                                  "changeSummary": "increase limit"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mintId").value("mint-001"))
                .andExpect(jsonPath("$.revisionId").value("rev-1"))
                .andExpect(jsonPath("$.message").value("Configuration applied: increase limit"));
    }
}
