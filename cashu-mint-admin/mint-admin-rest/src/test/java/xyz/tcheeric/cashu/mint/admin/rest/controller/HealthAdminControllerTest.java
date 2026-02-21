package xyz.tcheeric.cashu.mint.admin.rest.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import xyz.tcheeric.cashu.mint.admin.rest.config.AdminApiConfiguration;
import xyz.tcheeric.cashu.mint.admin.rest.config.AdminAuthenticationFilter;
import xyz.tcheeric.cashu.mint.admin.rest.config.AdminRbacFilter;
import xyz.tcheeric.cashu.mint.admin.rest.service.AdminHealthService;
import xyz.tcheeric.cashu.mint.admin.rest.service.AdminLifecycleServiceConfiguration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import({AdminApiConfiguration.class, AdminLifecycleServiceConfiguration.class, AdminHealthService.class})
@TestPropertySource(properties = "admin.security.api-token=test-token")
class HealthAdminControllerTest {

    private static final String ADMIN_TOKEN = "test-token";
    private static final String MINT_ID = "44444444-4444-4444-4444-444444444444";

    @Autowired
    private MockMvc mockMvc;

    // Checks that health snapshot requires authentication.
    @Test
    @DisplayName("Health snapshot requires authentication")
    void healthSnapshotRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/admin/health/mints/" + MINT_ID))
                .andExpect(status().isUnauthorized());
    }

    // Checks that health snapshot requires the MINT_ADMIN role.
    @Test
    @DisplayName("Health snapshot requires role header")
    void healthSnapshotRequiresRole() throws Exception {
        mockMvc.perform(get("/admin/health/mints/" + MINT_ID)
                        .header(AdminAuthenticationFilter.ADMIN_TOKEN_HEADER, ADMIN_TOKEN))
                .andExpect(status().isForbidden());
    }

    // Ensures health snapshot returns UNKNOWN for a mint with no health data.
    @Test
    @DisplayName("Health snapshot returns UNKNOWN for unknown mint")
    void healthSnapshotReturnsUnknown() throws Exception {
        mockMvc.perform(get("/admin/health/mints/" + MINT_ID)
                        .header(AdminAuthenticationFilter.ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                        .header(AdminRbacFilter.ADMIN_ROLES_HEADER, "MINT_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mintId").value(MINT_ID))
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.message").value("No health data available"));
    }
}
