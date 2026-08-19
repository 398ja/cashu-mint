package xyz.tcheeric.cashu.mint.admin.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xyz.tcheeric.cashu.mint.admin.rest.config.AuthenticatedOperator;
import xyz.tcheeric.cashu.mint.admin.rest.dto.auth.AuthMeResponse;

import java.util.Set;

/**
 * Provides caller identity introspection for the web UI login flow.
 *
 * <p>Roles are those of the operator resolved from the presented credential —
 * the caller does not get to say what they hold. See ADR-0005.
 */
@Tag(name = "Admin Auth", description = "Authentication introspection")
@RestController
@RequestMapping("/admin/auth")
public class AuthAdminController {

    @Operation(summary = "Get caller identity", description = "Returns the roles of the authenticated operator.")
    @ApiResponse(responseCode = "200", description = "Caller identity retrieved")
    @GetMapping("/me")
    public ResponseEntity<AuthMeResponse> me(final HttpServletRequest request) {
        final AuthenticatedOperator operator =
                (AuthenticatedOperator) request.getAttribute(AuthenticatedOperator.ATTRIBUTE);
        if (operator == null) {
            return ResponseEntity.ok(new AuthMeResponse(false, Set.of()));
        }
        return ResponseEntity.ok(new AuthMeResponse(true, operator.roles()));
    }
}
