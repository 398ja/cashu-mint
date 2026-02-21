package xyz.tcheeric.cashu.mint.admin.rest.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import xyz.tcheeric.cashu.mint.admin.rest.config.AdminApiConfiguration;
import xyz.tcheeric.cashu.mint.admin.rest.config.AdminAuthenticationFilter;
import xyz.tcheeric.cashu.mint.admin.rest.config.AdminRbacFilter;
import xyz.tcheeric.cashu.mint.admin.rest.service.AdminConfigurationService;
import xyz.tcheeric.cashu.mint.admin.rest.service.AdminLifecycleServiceConfiguration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(org.springframework.test.context.junit.jupiter.SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import({AdminApiConfiguration.class, AdminLifecycleServiceConfiguration.class, AdminConfigurationService.class})
@TestPropertySource(properties = "admin.security.api-token=test-token")
class ConfigurationAdminControllerTest {

    private static final String ADMIN_TOKEN = "test-token";
    private static final String MINT_ID = "33333333-3333-3333-3333-333333333333";
    private static final String OPERATOR_ID = "123e4567-e89b-12d3-a456-426614174000";

    @Autowired
    private MockMvc mockMvc;

    // Ensures configuration preview rejects requests without the admin token.
    @Test
    @DisplayName("Configuration preview requires authentication")
    void previewRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/admin/configuration/mints/" + MINT_ID + "/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestedBy": {"id":"%s","displayName":"Ops"},
                                  "proposedConfiguration": {"limits":"1"},
                                  "baseRevisionId": "1"
                                }
                                """.formatted(OPERATOR_ID)))
                .andExpect(status().isUnauthorized());
    }

    // Ensures configuration apply requires the appropriate RBAC role when authenticated.
    @Test
    @DisplayName("Configuration apply requires role header")
    void applyRequiresRole() throws Exception {
        mockMvc.perform(post("/admin/configuration/mints/" + MINT_ID + "/apply")
                        .header(AdminAuthenticationFilter.ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestedBy": {"id":"%s","displayName":"Ops"},
                                  "proposedConfiguration": {"limits":"1"},
                                  "changeSummary": "increase limit"
                                }
                                """.formatted(OPERATOR_ID)))
                .andExpect(status().isForbidden());
    }

    // Validates that applying a configuration returns the resulting revision metadata.
    @Test
    @DisplayName("Configuration apply returns revision")
    void applyReturnsConfigurationResponse() throws Exception {
        createMint();

        mockMvc.perform(post("/admin/configuration/mints/" + MINT_ID + "/apply")
                        .header(AdminAuthenticationFilter.ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                        .header(AdminRbacFilter.ADMIN_ROLES_HEADER, "MINT_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestedBy": {"id":"%s","displayName":"Ops"},
                                  "proposedConfiguration": {"limits":"1"},
                                  "changeSummary": "increase limit"
                                }
                                """.formatted(OPERATOR_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mintId").value(MINT_ID))
                .andExpect(jsonPath("$.revisionId").value("2"))
                .andExpect(jsonPath("$.message").value("Configuration applied: increase limit"));
    }

    // Validates that applying to a non-existent mint returns 404.
    @Test
    @DisplayName("Configuration apply returns not found for missing mint")
    void applyReturnsNotFoundForMissingMint() throws Exception {
        mockMvc.perform(post("/admin/configuration/mints/99999999-9999-9999-9999-999999999999/apply")
                        .header(AdminAuthenticationFilter.ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                        .header(AdminRbacFilter.ADMIN_ROLES_HEADER, "MINT_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestedBy": {"id":"%s","displayName":"Ops"},
                                  "proposedConfiguration": {"limits":"1"},
                                  "changeSummary": "increase limit"
                                }
                                """.formatted(OPERATOR_ID)))
                .andExpect(status().isNotFound());
    }

    private void createMint() throws Exception {
        mockMvc.perform(post("/admin/lifecycle/mints")
                        .header(AdminAuthenticationFilter.ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                        .header(AdminRbacFilter.ADMIN_ROLES_HEADER, "MINT_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mintId": "%s",
                                  "requestedBy": {"id":"%s","displayName":"Ops"},
                                  "metadata": {"displayName":"Test","description":"Test mint","tags":["test"]},
                                  "configuration": {"versionTag":"v1"}
                                }
                                """.formatted(MINT_ID, OPERATOR_ID)))
                .andExpect(status().isOk());
    }
}
