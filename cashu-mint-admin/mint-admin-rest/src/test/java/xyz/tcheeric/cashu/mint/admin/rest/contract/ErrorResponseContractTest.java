package xyz.tcheeric.cashu.mint.admin.rest.contract;

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
import xyz.tcheeric.cashu.mint.admin.rest.service.AdminLifecycleServiceConfiguration;
import xyz.tcheeric.cashu.mint.admin.rest.service.AdminOperationsService;
import xyz.tcheeric.cashu.mint.admin.rest.service.AdminUserService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract tests verifying that all admin endpoints return consistent error payloads
 * and proper HTTP status codes across the API surface.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import({AdminApiConfiguration.class, AdminLifecycleServiceConfiguration.class,
    AdminUserService.class, AdminOperationsService.class})
@TestPropertySource(properties = "admin.security.api-token=test-token")
class ErrorResponseContractTest {

    private static final String ADMIN_TOKEN = "test-token";
    private static final String MINT_ID = "99999999-9999-9999-9999-999999999999";
    private static final String OPERATOR_ID = "123e4567-e89b-12d3-a456-426614174000";

    @Autowired
    private MockMvc mockMvc;

    // Verifies unauthenticated requests return 401 with a JSON error body.
    @Test
    @DisplayName("Unauthenticated lifecycle request returns 401 with error body")
    void unauthenticatedLifecycleReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/admin/lifecycle/mints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    // Verifies forbidden requests return standard error format with status, error, code, message fields.
    @Test
    @DisplayName("Forbidden lifecycle request returns standard error format")
    void unauthenticatedLifecycleReturnsStandardError() throws Exception {
        mockMvc.perform(post("/admin/lifecycle/mints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andExpect(jsonPath("$.message").exists());
    }

    // Verifies pausing a non-existent mint returns 404 with mint_not_found code.
    @Test
    @DisplayName("Pausing non-existent mint returns standard error with mint_not_found")
    void notFoundLifecyclePauseReturnsStandardError() throws Exception {
        mockMvc.perform(post("/admin/lifecycle/mints/" + MINT_ID + "/pause")
                        .header(AdminAuthenticationFilter.ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "maintenance",
                                  "requestedBy": {"id":"%s","displayName":"Ops"}
                                }
                                """.formatted(OPERATOR_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.code").value("mint_not_found"))
                .andExpect(jsonPath("$.message").exists());
    }

    // Verifies operations endpoint returns consistent response structure.
    @Test
    @DisplayName("Operations schedule returns consistent response structure")
    void operationsScheduleReturnsConsistentStructure() throws Exception {
        mockMvc.perform(post("/admin/operations/mints/" + MINT_ID + "/maintenance/schedule")
                        .header(AdminAuthenticationFilter.ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "test",
                                  "durationMinutes": 30,
                                  "requestedBy": {"id":"%s","displayName":"Ops"}
                                }
                                """.formatted(OPERATOR_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mintId").exists())
                .andExpect(jsonPath("$.controlId").exists())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.message").exists());
    }

}
