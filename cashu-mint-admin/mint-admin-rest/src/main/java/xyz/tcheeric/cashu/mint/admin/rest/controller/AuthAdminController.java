package xyz.tcheeric.cashu.mint.admin.rest.controller;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import xyz.tcheeric.cashu.mint.admin.rest.dto.auth.AuthMeResponse;

/**
 * Provides caller identity introspection for the web UI login flow.
 */
@Tag(name = "Admin Auth", description = "Authentication introspection")
@RestController
@RequestMapping("/admin/auth")
public class AuthAdminController {

    @Operation(summary = "Get caller identity", description = "Returns the roles associated with the authenticated caller.")
    @ApiResponse(responseCode = "200", description = "Caller identity retrieved")
    @GetMapping("/me")
    public ResponseEntity<AuthMeResponse> me(
            @RequestHeader(value = "X-Admin-Roles", required = false) final String rolesHeader) {
        final Set<String> roles = extractRoles(rolesHeader);
        return ResponseEntity.ok(new AuthMeResponse(true, roles));
    }

    private Set<String> extractRoles(final String headerValue) {
        if (!StringUtils.hasText(headerValue)) {
            return Set.of();
        }
        return Arrays.stream(headerValue.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
