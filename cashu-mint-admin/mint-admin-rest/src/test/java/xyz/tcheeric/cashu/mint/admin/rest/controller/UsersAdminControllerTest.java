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
import xyz.tcheeric.cashu.mint.admin.rest.service.AdminUserService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import({AdminApiConfiguration.class, AdminLifecycleServiceConfiguration.class, AdminUserService.class})
@TestPropertySource(properties = {
    // Own database per class: a shared in-memory store leaks operators between classes.
    "spring.datasource.url=jdbc:h2:mem:UsersAdminControllerTest;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
})
@ExtendWith(NapSessionCleanup.class)
class UsersAdminControllerTest {

    private static final String USER_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    @Autowired
    private MockMvc mockMvc;

    // Checks that creating a user without authentication is rejected.
    @Test
    @DisplayName("User creation requires authentication")
    void createUserRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson(USER_ID)))
                .andExpect(status().isUnauthorized());
    }

    // Verifies a session whose roles lack users:manage is refused rather than served.
    @Test
    @DisplayName("User creation requires the users permission")
    void createUserRequiresThePermission() throws Exception {
        mockMvc.perform(post("/admin/users")
                        .with(TestNapSessions.role(AdminRole.OPS_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson(USER_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
    }

    // Ensures update returns the resulting user payload once a user exists.
    @Test
    @DisplayName("User update returns response payload")
    void updateUserReturnsResponse() throws Exception {
        final String userId = "bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee";

        mockMvc.perform(post("/admin/users")
                        .with(TestNapSessions.superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson(userId)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/admin/users/" + userId)
                        .with(TestNapSessions.superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Alice Ops",
                                  "email": "alice@example.com",
                                  "roles": ["USER_ADMIN", "OPS_ADMIN"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.message").value("User updated"));
    }

    private String createUserJson(final String userId) {
        return """
                {
                  "userId": "%s",
                  "displayName": "Alice",
                  "email": "alice@example.com",
                  "roles": ["USER_ADMIN"],
                  "npub": "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"
                }
                """.formatted(userId);
    }
}
