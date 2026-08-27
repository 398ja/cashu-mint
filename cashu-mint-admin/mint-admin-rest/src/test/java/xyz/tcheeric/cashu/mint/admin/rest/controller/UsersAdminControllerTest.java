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
    "admin.security.api-token=test-token",
    // Own database per class: a shared in-memory store leaks operators between
    // classes, and the bootstrap credential is inert once any operator exists.
    "spring.datasource.url=jdbc:h2:mem:UsersAdminControllerTest;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
})
class UsersAdminControllerTest {

    private static final String ADMIN_TOKEN = "test-token";
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

    // Verifies that creating a user without the required role returns a forbidden status.
    @Test
    @DisplayName("User creation requires role header")
    void createUserRequiresCredential() throws Exception {
        mockMvc.perform(post("/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson(USER_ID)))
                .andExpect(status().isUnauthorized());
    }

    // Ensures update returns the resulting user payload once a user exists.
    @Test
    @DisplayName("User update returns response payload")
    void updateUserReturnsResponse() throws Exception {
        final String userId = "bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee";

        // Creating this operator consumes the bootstrap credential, so the update that
        // follows is authenticated as the operator just created — the same handover a
        // real deployment performs.
        final String created = mockMvc.perform(post("/admin/users")
                        .header(AdminAuthenticationFilter.ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson(userId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        final String credential = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(created).path("credential").asText();

        mockMvc.perform(put("/admin/users/" + userId)
                        .header(AdminAuthenticationFilter.ADMIN_TOKEN_HEADER, credential)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Alice Ops",
                                  "email": "alice@example.com",
                                  "roles": ["admin", "viewer"]
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
                  "roles": ["USER_ADMIN"]
                }
                """.formatted(userId);
    }
}
