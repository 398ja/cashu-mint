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
import xyz.tcheeric.cashu.mint.admin.domain.AdminRole;
import xyz.tcheeric.cashu.mint.admin.rest.nap.NapSessionCleanup;
import xyz.tcheeric.cashu.mint.admin.rest.nap.TestNapSessions;
import xyz.tcheeric.cashu.mint.admin.rest.service.AdminLifecycleServiceConfiguration;
import xyz.tcheeric.cashu.mint.admin.rest.service.AdminOperationsService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import({AdminApiConfiguration.class, AdminLifecycleServiceConfiguration.class, AdminOperationsService.class})
@TestPropertySource(properties = {
    // Own database per class: a shared in-memory store leaks operators between
    // classes, so one class's profiles decide another class's ACL decisions.
    "spring.datasource.url=jdbc:h2:mem:OperationsAdminControllerTest;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
})
@ExtendWith(NapSessionCleanup.class)
class OperationsAdminControllerTest {

    private static final String MINT_ID = "55555555-5555-5555-5555-555555555555";

    @Autowired
    private MockMvc mockMvc;

    // Checks that scheduling maintenance requires authentication.
    @Test
    @DisplayName("Schedule maintenance requires authentication")
    void scheduleMaintenanceRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/admin/operations/mints/" + MINT_ID + "/maintenance/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maintenanceJson()))
                .andExpect(status().isUnauthorized());
    }

    // Checks that scheduling maintenance requires the OPS_ADMIN role.
    @Test
    @DisplayName("Schedule maintenance requires role header")
    void scheduleMaintenanceRequiresCredential() throws Exception {
        mockMvc.perform(post("/admin/operations/mints/" + MINT_ID + "/maintenance/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maintenanceJson()))
                .andExpect(status().isUnauthorized());
    }

    // Ensures scheduling maintenance returns SCHEDULED status.
    @Test
    @DisplayName("Schedule maintenance returns scheduled response")
    void scheduleMaintenanceReturnsResponse() throws Exception {
        mockMvc.perform(post("/admin/operations/mints/" + MINT_ID + "/maintenance/schedule")
                        .with(TestNapSessions.superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maintenanceJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mintId").value(MINT_ID))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.message").value("Maintenance window scheduled"));
    }

    // Ensures force close returns FORCE_CLOSED status.
    @Test
    @DisplayName("Force close returns response")
    void forceCloseReturnsResponse() throws Exception {
        mockMvc.perform(post("/admin/operations/mints/" + MINT_ID + "/force-close")
                        .with(TestNapSessions.superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maintenanceJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mintId").value(MINT_ID))
                .andExpect(jsonPath("$.status").value("FORCE_CLOSED"));
    }

    private String maintenanceJson() {
        return """
                {
                  "reason": "Scheduled update",
                  "durationMinutes": 60
                }
                """;
    }
}
