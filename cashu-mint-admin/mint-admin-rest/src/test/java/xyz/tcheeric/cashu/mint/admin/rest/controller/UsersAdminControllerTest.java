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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    /** The id the configured Super Administrator's npub derives, having no stored profile. */
    private static final String ANCHORED_USER_ID = "4db74fdf-1918-3bf2-955e-6099f9790667";

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

    // The Super Administrator has no stored profile, so a listing built from the store alone
    // omits the one account that outranks every entry in it.
    @Test
    @DisplayName("The listing carries the configuration-anchored Super Administrator, marked as such")
    void listingIncludesTheAnchoredSuperAdministrator() throws Exception {
        mockMvc.perform(get("/admin/users").with(TestNapSessions.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.userId == '" + ANCHORED_USER_ID + "')].configurationAnchored")
                    .value(true))
                .andExpect(jsonPath("$.items[?(@.userId == '" + ANCHORED_USER_ID + "')].roles[0]")
                    .value(AdminRole.SUPER_ADMIN.key()));
    }

    // Suspending the account that recovers the deployment would lock everybody out, and its
    // entitlement comes from configuration, so a write here could only disagree with it.
    @Test
    @DisplayName("Suspending the Super Administrator is refused")
    void suspendingTheSuperAdministratorIsRefused() throws Exception {
        mockMvc.perform(post("/admin/users/" + ANCHORED_USER_ID + "/deactivate")
                        .with(TestNapSessions.superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"offboarding\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("super_admin_protected"));
    }

    // A suspended Operator is reinstated rather than re-provisioned: the profile and the
    // audit history that names them survive the suspension.
    @Test
    @DisplayName("An operator can be suspended and reinstated")
    void operatorCanBeSuspendedAndReinstated() throws Exception {
        final String userId = "cccccccc-bbbb-cccc-dddd-eeeeeeeeeeee";
        mockMvc.perform(post("/admin/users")
                        .with(TestNapSessions.superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson(userId,
                            "npub1az6g0srekrm8c626umzvy4f2glec4haz2v7vtynt6tqs99p0mjmsna8xse")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/users/" + userId + "/deactivate")
                        .with(TestNapSessions.superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"offboarding\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(post("/admin/users/" + userId + "/reinstate")
                        .with(TestNapSessions.superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"returned from leave\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.message").value("User reinstated"));
    }

    // Operator management is the Super Administrator's alone: an Administrator holding another
    // role is refused before the request reaches the use case.
    @Test
    @DisplayName("An operator without USERS_MANAGE cannot reinstate")
    void reinstateRequiresThePermission() throws Exception {
        mockMvc.perform(post("/admin/users/" + ANCHORED_USER_ID + "/reinstate")
                        .with(TestNapSessions.role(AdminRole.MINT_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"nice try\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
    }

    // The action has to be traceable to a person, so it lands on the same timeline the mint's
    // own privileged actions do, naming the Operator whose session made the request.
    @Test
    @DisplayName("A management action reaches the audit trail naming the acting operator")
    void managementActionIsAudited() throws Exception {
        final String userId = "dddddddd-bbbb-cccc-dddd-eeeeeeeeeeee";
        mockMvc.perform(post("/admin/users")
                        .with(TestNapSessions.superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson(userId,
                            "npub1ky9j57gnhqv4hhvhjushyv6sgferr8umhheenxrr89y9jujthy9qrld2q8")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/audit/events").with(TestNapSessions.superAdmin()))
                .andExpect(status().isOk())
                // The acting Operator is the session's, never a value the caller supplied,
                // and the entry names who it was done to as well as who did it.
                .andExpect(jsonPath("$.items[?(@.action == 'PROVISION')].actor",
                    everyItem(equalTo(ANCHORED_USER_ID))))
                .andExpect(jsonPath("$.items[?(@.action == 'PROVISION')].targetAccountId",
                    hasItem(userId)));
    }

    private String createUserJson(final String userId) {
        return createUserJson(userId, "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6");
    }

    private String createUserJson(final String userId, final String npub) {
        return """
                {
                  "userId": "%s",
                  "displayName": "Alice",
                  "email": "alice@example.com",
                  "roles": ["USER_ADMIN"],
                  "npub": "%s"
                }
                """.formatted(userId, npub);
    }
}
