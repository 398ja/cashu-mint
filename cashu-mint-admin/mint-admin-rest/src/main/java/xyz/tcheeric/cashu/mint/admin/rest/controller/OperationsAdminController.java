package xyz.tcheeric.cashu.mint.admin.rest.controller;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

import xyz.tcheeric.cashu.mint.admin.domain.AdminPermission;
import xyz.tcheeric.cashu.mint.admin.rest.config.AdminOpenApiConfiguration;
import xyz.tcheeric.nap.spring.annotation.RequiresPermission;

import xyz.tcheeric.cashu.mint.admin.rest.dto.common.AdminErrorResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.common.PagedResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.operations.MaintenanceRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.operations.OperationalControlResponse;
import xyz.tcheeric.cashu.mint.admin.rest.service.AdminOperationsService;

/**
 * REST endpoints for operational control workflows such as maintenance windows and key rotation.
 */
@Tag(name = "Admin Operations", description = "Operational controls for mint instances")
@SecurityRequirement(name = AdminOpenApiConfiguration.ADMIN_SESSION_SCHEME)
@RequiresPermission(AdminPermission.Keys.OPERATIONS_EXECUTE)
@RestController
@RequestMapping("/admin/operations")
public class OperationsAdminController {

    private final AdminOperationsService operationsService;

    public OperationsAdminController(final AdminOperationsService operationsService) {
        this.operationsService = operationsService;
    }

    @Operation(summary = "List operational controls", description = "Returns recent and in-progress controls for a mint.")
    @ApiResponse(responseCode = "200", description = "Controls listed")
    @GetMapping("/mints/{mintId}/controls")
    public ResponseEntity<PagedResponse<OperationalControlResponse>> listControls(
            @PathVariable("mintId") String mintId,
            @Parameter(description = "Page number (zero-indexed)") @RequestParam(defaultValue = "0") final int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") final int size) {
        return ResponseEntity.ok(operationsService.listControls(mintId, page, size));
    }

    @Operation(summary = "Schedule maintenance window", description = "Schedules a maintenance window for a mint.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Maintenance scheduled",
                content = @Content(schema = @Schema(implementation = OperationalControlResponse.class))),
            @ApiResponse(responseCode = "404", description = "Mint not found",
                content = @Content(schema = @Schema(implementation = AdminErrorResponse.class)))
    })
    @PostMapping("/mints/{mintId}/maintenance/schedule")
    public ResponseEntity<OperationalControlResponse> scheduleMaintenance(
            @PathVariable("mintId") String mintId,
            @Valid @RequestBody MaintenanceRequest request) {
        return ResponseEntity.ok(operationsService.scheduleMaintenance(mintId, request));
    }

    @Operation(summary = "Start maintenance", description = "Starts a maintenance window for a mint.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Maintenance started",
                content = @Content(schema = @Schema(implementation = OperationalControlResponse.class)))
    })
    @PostMapping("/mints/{mintId}/maintenance/start")
    public ResponseEntity<OperationalControlResponse> startMaintenance(
            @PathVariable("mintId") String mintId,
            @Valid @RequestBody MaintenanceRequest request) {
        return ResponseEntity.ok(operationsService.startMaintenance(mintId, request));
    }

    @Operation(summary = "Complete maintenance", description = "Completes a maintenance window for a mint.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Maintenance completed",
                content = @Content(schema = @Schema(implementation = OperationalControlResponse.class))),
            @ApiResponse(responseCode = "404", description = "No active maintenance window",
                content = @Content(schema = @Schema(implementation = AdminErrorResponse.class)))
    })
    @PostMapping("/mints/{mintId}/maintenance/complete")
    public ResponseEntity<OperationalControlResponse> completeMaintenance(
            @PathVariable("mintId") String mintId,
            @Valid @RequestBody MaintenanceRequest request) {
        return ResponseEntity.ok(operationsService.completeMaintenance(mintId, request));
    }

    @Operation(summary = "Rotate keys", description = "Initiates key rotation for a mint.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Key rotation initiated",
                content = @Content(schema = @Schema(implementation = OperationalControlResponse.class)))
    })
    @PostMapping("/mints/{mintId}/keys/rotate")
    public ResponseEntity<OperationalControlResponse> rotateKeys(
            @PathVariable("mintId") String mintId,
            @Valid @RequestBody MaintenanceRequest request) {
        return ResponseEntity.ok(operationsService.rotateKeys(mintId, request));
    }

    @Operation(summary = "Force close mint", description = "Emergency decommission of a mint.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Mint force-closed",
                content = @Content(schema = @Schema(implementation = OperationalControlResponse.class)))
    })
    @PostMapping("/mints/{mintId}/force-close")
    public ResponseEntity<OperationalControlResponse> forceClose(
            @PathVariable("mintId") String mintId,
            @Valid @RequestBody MaintenanceRequest request) {
        return ResponseEntity.ok(operationsService.forceClose(mintId, request));
    }
}
