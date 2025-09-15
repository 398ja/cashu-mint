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

@WebMvcTest(LifecycleAdminController.class)
@Import(AdminApiConfiguration.class)
@TestPropertySource(properties = "admin.security.api-token=test-token")
class LifecycleAdminControllerTest {

    private static final String ADMIN_TOKEN = "test-token";

    @Autowired
    private MockMvc mockMvc;

    // Verifies that lifecycle endpoints reject unauthenticated calls.
    @Test
    @DisplayName("Lifecycle create requires authentication")
    void createMintRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/admin/lifecycle/mints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "mintId": "mint-001",
                                  "requestedBy": {"id":"ops","displayName":"Ops"},
                                  "metadata": {"displayName":"Primary","description":"Mint","tags":["prod"]},
                                  "configuration": {"key":"value"}
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    // Verifies that lifecycle pause route is reachable and returns 501 when authenticated.
    @Test
    @DisplayName("Lifecycle pause returns 501")
    void pauseMintReturnsNotImplemented() throws Exception {
        mockMvc.perform(post("/admin/lifecycle/mints/mint-001/pause")
                        .header(AdminAuthenticationFilter.ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestedBy": {"id":"ops","displayName":"Ops"},
                                  "reason": "maintenance"
                                }
                                """))
                .andExpect(status().isNotImplemented());
    }
}
