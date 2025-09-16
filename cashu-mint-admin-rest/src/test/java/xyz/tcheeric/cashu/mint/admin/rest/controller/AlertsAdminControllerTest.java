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
import xyz.tcheeric.cashu.mint.admin.rest.service.AdminAlertService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AlertsAdminController.class)
@Import({AdminApiConfiguration.class, AdminAlertService.class})
@TestPropertySource(properties = "admin.security.api-token=test-token")
class AlertsAdminControllerTest {

    private static final String ADMIN_TOKEN = "test-token";

    @Autowired
    private MockMvc mockMvc;

    // Confirms alert creation cannot be called without authentication.
    @Test
    @DisplayName("Alert creation requires authentication")
    void createAlertRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/admin/alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "alertId": "alert-1",
                                  "mintId": "mint-001",
                                  "severity": "CRITICAL",
                                  "summary": "Mint offline",
                                  "requestedBy": {"id":"ops","displayName":"Ops"}
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    // Ensures alerts require role-based access when authenticated.
    @Test
    @DisplayName("Alert creation requires role header")
    void createAlertRequiresRole() throws Exception {
        mockMvc.perform(post("/admin/alerts")
                        .header(AdminAuthenticationFilter.ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "alertId": "alert-1",
                                  "mintId": "mint-001",
                                  "severity": "CRITICAL",
                                  "summary": "Mint offline",
                                  "requestedBy": {"id":"ops","displayName":"Ops"}
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    // Ensures alert escalation updates state when authentication and roles are present.
    @Test
    @DisplayName("Alert escalate updates response")
    void escalateReturnsUpdatedAlert() throws Exception {
        mockMvc.perform(post("/admin/alerts")
                        .header(AdminAuthenticationFilter.ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                        .header(AdminRbacFilter.ADMIN_ROLES_HEADER, "ALERTS_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "alertId": "alert-1",
                                  "mintId": "mint-001",
                                  "severity": "CRITICAL",
                                  "summary": "Mint offline",
                                  "requestedBy": {"id":"ops","displayName":"Ops"}
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/alerts/alert-1/escalate")
                        .header(AdminAuthenticationFilter.ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                        .header(AdminRbacFilter.ADMIN_ROLES_HEADER, "ALERTS_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestedBy": {"id":"ops","displayName":"Ops"},
                                  "policyId": "pagerduty",
                                  "reason": "manual escalation"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.escalations[0]").value("pagerduty"))
                .andExpect(jsonPath("$.message").value("Alert escalated to pagerduty"));
    }
}
