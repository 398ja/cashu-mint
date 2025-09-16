package xyz.tcheeric.cashu.mint.admin.rest.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import xyz.tcheeric.cashu.mint.admin.rest.config.AdminApiConfiguration;
import xyz.tcheeric.cashu.mint.admin.rest.config.AdminAuthenticationFilter;
import xyz.tcheeric.cashu.mint.admin.rest.config.AdminRbacFilter;
import xyz.tcheeric.cashu.mint.admin.rest.service.AdminUserService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@WebMvcTest(UsersAdminController.class)
@AutoConfigureMockMvc
@Import({AdminApiConfiguration.class, AdminUserService.class})
@TestPropertySource(properties = "admin.security.api-token=test-token")
class UsersAdminControllerTest {

    private static final String ADMIN_TOKEN = "test-token";

    @Autowired
    private MockMvc mockMvc;

    // Checks that creating a user without authentication is rejected.
    @Test
    @DisplayName("User creation requires authentication")
    void createUserRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "alice",
                                  "displayName": "Alice",
                                  "email": "alice@example.com",
                                  "roles": ["admin"],
                                  "requestedBy": {"id":"ops","displayName":"Ops"}
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    // Verifies that creating a user without the required role returns a forbidden status.
    @Test
    @DisplayName("User creation requires role header")
    void createUserRequiresRole() throws Exception {
        mockMvc.perform(post("/admin/users")
                        .header(AdminAuthenticationFilter.ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "alice",
                                  "displayName": "Alice",
                                  "email": "alice@example.com",
                                  "roles": ["admin"],
                                  "requestedBy": {"id":"ops","displayName":"Ops"}
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    // Ensures update returns the resulting user payload once a user exists.
    @Test
    @DisplayName("User update returns response payload")
    void updateUserReturnsResponse() throws Exception {
        mockMvc.perform(post("/admin/users")
                        .header(AdminAuthenticationFilter.ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                        .header(AdminRbacFilter.ADMIN_ROLES_HEADER, "USER_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "alice",
                                  "displayName": "Alice",
                                  "email": "alice@example.com",
                                  "roles": ["admin"],
                                  "requestedBy": {"id":"ops","displayName":"Ops"}
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(put("/admin/users/alice")
                        .header(AdminAuthenticationFilter.ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                        .header(AdminRbacFilter.ADMIN_ROLES_HEADER, "USER_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Alice Ops",
                                  "email": "alice@example.com",
                                  "roles": ["admin", "viewer"],
                                  "requestedBy": {"id":"ops","displayName":"Ops"}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("alice"))
                .andExpect(jsonPath("$.message").value("User updated"));
    }
}
