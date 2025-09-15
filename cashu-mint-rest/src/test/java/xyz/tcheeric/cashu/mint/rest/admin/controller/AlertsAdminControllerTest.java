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

@WebMvcTest(AlertsAdminController.class)
@Import(AdminApiConfiguration.class)
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

    // Ensures escalate route is wired and returns 501 for now.
    @Test
    @DisplayName("Alert escalate returns 501")
    void escalateReturnsNotImplemented() throws Exception {
        mockMvc.perform(post("/admin/alerts/alert-1/escalate")
                        .header(AdminAuthenticationFilter.ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestedBy": {"id":"ops","displayName":"Ops"},
                                  "policyId": "pagerduty",
                                  "reason": "manual escalation"
                                }
                                """))
                .andExpect(status().isNotImplemented());
    }
}
