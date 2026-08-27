package xyz.tcheeric.cashu.mint.admin.rest.contract;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import xyz.tcheeric.cashu.mint.admin.rest.config.AdminApiConfiguration;
import xyz.tcheeric.cashu.mint.admin.domain.AdminRole;
import xyz.tcheeric.cashu.mint.admin.rest.nap.TestNapSessions;
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
@TestPropertySource(properties = {
    // Own database per class: a shared in-memory store leaks operators between
    // classes, and the bootstrap credential is inert once any operator exists.
    "spring.datasource.url=jdbc:h2:mem:ErrorResponseContractTest;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
})
class ErrorResponseContractTest {

    private static final String MINT_ID = "99999999-9999-9999-9999-999999999999";

    @Autowired
    private MockMvc mockMvc;

    // NapSessionFilter clears only the context it set itself, so a seated session
    // would otherwise leak onto the next test sharing this thread.
    @AfterEach
    void clearSession() {
        SecurityContextHolder.clearContext();
    }

    // Verifies unauthenticated requests return 401 with a JSON error body.
    @Test
    @DisplayName("Unauthenticated lifecycle request returns 401 with error body")
    void unauthenticatedLifecycleReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/admin/lifecycle/mints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));
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
                        .with(TestNapSessions.superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "maintenance"
                                }
                                """))
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
                        .with(TestNapSessions.superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "test",
                                  "durationMinutes": 30
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mintId").exists())
                .andExpect(jsonPath("$.controlId").exists())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.message").exists());
    }

}
