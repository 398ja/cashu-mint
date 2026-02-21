package xyz.tcheeric.cashu.mint.admin.rest.controller;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import xyz.tcheeric.cashu.mint.admin.rest.dto.common.AdminErrorResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.common.PagedResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.users.AssignRolesRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.users.CreateUserRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.users.CredentialResetResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.users.ResetCredentialsRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.users.UpdateUserRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.users.UserLifecycleRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.users.UserResponse;
import xyz.tcheeric.cashu.mint.admin.rest.service.AdminUserService;

/**
 * REST endpoints for operator account management.
 */
@Tag(name = "Admin Users", description = "Operator management workflows mirrored from CLI commands")
@SecurityRequirement(name = "AdminToken")
@SecurityRequirement(name = "AdminRoles")
@RestController
@RequestMapping("/admin/users")
public class UsersAdminController {

    private final AdminUserService userService;

    public UsersAdminController(final AdminUserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "List operators", description = "Returns a paginated list of operator accounts.")
    @ApiResponse(responseCode = "200", description = "Users listed")
    @GetMapping
    public ResponseEntity<PagedResponse<UserResponse>> listUsers(
            @Parameter(description = "Filter by active status") @RequestParam(required = false) final Boolean active,
            @Parameter(description = "Filter by role") @RequestParam(required = false) final String role,
            @Parameter(description = "Search query") @RequestParam(required = false) final String q,
            @Parameter(description = "Page number (zero-indexed)") @RequestParam(defaultValue = "0") final int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") final int size) {
        return ResponseEntity.ok(userService.listUsers(active, role, q, page, size));
    }

    @Operation(summary = "Get operator detail", description = "Returns details for a single operator account.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User detail retrieved"),
            @ApiResponse(responseCode = "404", description = "User not found", content = @Content(schema = @Schema(implementation = AdminErrorResponse.class)))
    })
    @GetMapping("/{userId}")
    public ResponseEntity<UserResponse> getUser(@PathVariable("userId") String userId) {
        return ResponseEntity.ok(userService.getUser(userId));
    }

    @Operation(summary = "Create an operator", description = "Provision a new operator account, mirroring the CLI workflow.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User created", content = @Content(schema = @Schema(implementation = UserResponse.class))),
            @ApiResponse(responseCode = "409", description = "User already exists", content = @Content(schema = @Schema(implementation = AdminErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.ok(userService.createUser(request));
    }

    @Operation(summary = "Update an operator", description = "Update operator details, mirroring the CLI user update command.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User updated", content = @Content(schema = @Schema(implementation = UserResponse.class))),
            @ApiResponse(responseCode = "404", description = "User not found", content = @Content(schema = @Schema(implementation = AdminErrorResponse.class)))
    })
    @PutMapping("/{userId}")
    public ResponseEntity<UserResponse> updateUser(@PathVariable("userId") String userId,
                                                   @Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(userService.updateUser(userId, request));
    }

    @Operation(summary = "Assign operator roles", description = "Assign or replace roles on an operator account.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Roles updated", content = @Content(schema = @Schema(implementation = UserResponse.class))),
            @ApiResponse(responseCode = "404", description = "User not found", content = @Content(schema = @Schema(implementation = AdminErrorResponse.class)))
    })
    @PostMapping("/{userId}/roles")
    public ResponseEntity<UserResponse> assignRoles(@PathVariable("userId") String userId,
                                                    @Valid @RequestBody AssignRolesRequest request) {
        return ResponseEntity.ok(userService.assignRoles(userId, request));
    }

    @Operation(summary = "Reset operator credentials", description = "Initiate a credential reset workflow for an operator.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reset token issued", content = @Content(schema = @Schema(implementation = CredentialResetResponse.class))),
            @ApiResponse(responseCode = "404", description = "User not found", content = @Content(schema = @Schema(implementation = AdminErrorResponse.class)))
    })
    @PostMapping("/{userId}/reset-credentials")
    public ResponseEntity<CredentialResetResponse> resetCredentials(@PathVariable("userId") String userId,
                                                                    @Valid @RequestBody ResetCredentialsRequest request) {
        return ResponseEntity.ok(userService.resetCredentials(userId, request));
    }

    @Operation(summary = "Deactivate an operator", description = "Deactivate an operator account, mirroring the CLI lifecycle flow.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User deactivated", content = @Content(schema = @Schema(implementation = UserResponse.class))),
            @ApiResponse(responseCode = "404", description = "User not found", content = @Content(schema = @Schema(implementation = AdminErrorResponse.class)))
    })
    @PostMapping("/{userId}/deactivate")
    public ResponseEntity<UserResponse> deactivateUser(@PathVariable("userId") String userId,
                                                       @Valid @RequestBody UserLifecycleRequest request) {
        return ResponseEntity.ok(userService.deactivateUser(userId, request));
    }
}
