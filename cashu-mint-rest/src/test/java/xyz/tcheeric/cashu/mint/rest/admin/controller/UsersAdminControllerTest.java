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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UsersAdminController.class)
@Import(AdminApiConfiguration.class)
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

    // Verifies that updating a user is routed and returns 501 when authentication is supplied.
    @Test
    @DisplayName("User update returns 501")
    void updateUserReturnsNotImplemented() throws Exception {
        mockMvc.perform(put("/admin/users/alice")
                        .header(AdminAuthenticationFilter.ADMIN_TOKEN_HEADER, ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Alice Ops",
                                  "email": "alice@example.com",
                                  "roles": ["admin"],
                                  "requestedBy": {"id":"ops","displayName":"Ops"}
                                }
                                """))
                .andExpect(status().isNotImplemented());
    }
}
