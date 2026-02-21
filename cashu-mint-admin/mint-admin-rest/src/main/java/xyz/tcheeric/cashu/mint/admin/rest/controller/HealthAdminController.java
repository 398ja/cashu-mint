package xyz.tcheeric.cashu.mint.admin.rest.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import xyz.tcheeric.cashu.mint.admin.rest.dto.common.AdminErrorResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.health.HealthSnapshotResponse;
import xyz.tcheeric.cashu.mint.admin.rest.service.AdminHealthService;

/**
 * REST endpoints for mint health monitoring.
 */
@Tag(name = "Admin Health", description = "Health monitoring for mint instances")
@SecurityRequirement(name = "AdminToken")
@SecurityRequirement(name = "AdminRoles")
@RestController
@RequestMapping("/admin/health")
public class HealthAdminController {

    private final AdminHealthService healthService;

    public HealthAdminController(final AdminHealthService healthService) {
        this.healthService = healthService;
    }

    @Operation(summary = "Get mint health snapshot", description = "Returns the current health status of a mint instance.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Health snapshot retrieved",
                content = @Content(schema = @Schema(implementation = HealthSnapshotResponse.class))),
            @ApiResponse(responseCode = "404", description = "Mint not found",
                content = @Content(schema = @Schema(implementation = AdminErrorResponse.class)))
    })
    @GetMapping("/mints/{mintId}")
    public ResponseEntity<HealthSnapshotResponse> getHealthSnapshot(@PathVariable("mintId") String mintId) {
        return ResponseEntity.ok(healthService.getHealthSnapshot(mintId));
    }

    @Operation(summary = "Acknowledge health alert", description = "Acknowledges a health alert and resets status.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Alert acknowledged",
                content = @Content(schema = @Schema(implementation = HealthSnapshotResponse.class))),
            @ApiResponse(responseCode = "404", description = "Mint not found",
                content = @Content(schema = @Schema(implementation = AdminErrorResponse.class)))
    })
    @PostMapping("/mints/{mintId}/acknowledge")
    public ResponseEntity<HealthSnapshotResponse> acknowledgeAlert(@PathVariable("mintId") String mintId) {
        return ResponseEntity.ok(healthService.acknowledgeAlert(mintId));
    }
}
