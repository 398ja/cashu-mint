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

import xyz.tcheeric.cashu.mint.admin.rest.dto.alerts.AlertActionRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.alerts.AlertActionResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.alerts.CreateAlertRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.alerts.EscalateAlertRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.alerts.SilenceAlertRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.common.AdminErrorResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.common.PagedResponse;
import xyz.tcheeric.cashu.mint.admin.rest.service.AdminAlertService;

/**
 * REST endpoints for alert and notification workflows.
 */
@Tag(name = "Admin Alerts", description = "Alert workflows mirrored from the mint CLI")
@SecurityRequirement(name = "AdminToken")
@SecurityRequirement(name = "AdminRoles")
@RestController
@RequestMapping("/admin/alerts")
public class AlertsAdminController {

    private final AdminAlertService alertService;

    public AlertsAdminController(final AdminAlertService alertService) {
        this.alertService = alertService;
    }

    @Operation(summary = "List alerts", description = "Returns a paginated list of alerts with optional filters.")
    @ApiResponse(responseCode = "200", description = "Alerts listed")
    @GetMapping
    public ResponseEntity<PagedResponse<AlertActionResponse>> listAlerts(
            @Parameter(description = "Filter by severity") @RequestParam(required = false) final String severity,
            @Parameter(description = "Filter by acknowledged status") @RequestParam(required = false) final Boolean acknowledged,
            @Parameter(description = "Filter by silenced status") @RequestParam(required = false) final Boolean silenced,
            @Parameter(description = "Filter by mint ID") @RequestParam(required = false) final String mintId,
            @Parameter(description = "Page number (zero-indexed)") @RequestParam(defaultValue = "0") final int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") final int size) {
        return ResponseEntity.ok(alertService.listAlerts(severity, acknowledged, silenced, mintId, page, size));
    }

    @Operation(summary = "Get alert detail", description = "Returns details for a single alert.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Alert detail retrieved"),
            @ApiResponse(responseCode = "404", description = "Alert not found", content = @Content(schema = @Schema(implementation = AdminErrorResponse.class)))
    })
    @GetMapping("/{alertId}")
    public ResponseEntity<AlertActionResponse> getAlert(@PathVariable("alertId") String alertId) {
        return ResponseEntity.ok(alertService.getAlert(alertId));
    }

    @Operation(summary = "Declare an operational alert", description = "Mirrors the `mint alerts` CLI workflow for creating alerts.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Alert recorded", content = @Content(schema = @Schema(implementation = AlertActionResponse.class))),
            @ApiResponse(responseCode = "409", description = "Alert already exists", content = @Content(schema = @Schema(implementation = AdminErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<AlertActionResponse> createAlert(@Valid @RequestBody CreateAlertRequest request) {
        return ResponseEntity.ok(alertService.createAlert(request));
    }

    @Operation(summary = "Acknowledge an alert", description = "Marks an alert as acknowledged, matching the CLI acknowledgement flow.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Alert acknowledged", content = @Content(schema = @Schema(implementation = AlertActionResponse.class))),
            @ApiResponse(responseCode = "404", description = "Alert not found", content = @Content(schema = @Schema(implementation = AdminErrorResponse.class)))
    })
    @PostMapping("/{alertId}/acknowledge")
    public ResponseEntity<AlertActionResponse> acknowledgeAlert(@PathVariable("alertId") String alertId,
                                                                @Valid @RequestBody AlertActionRequest request) {
        return ResponseEntity.ok(alertService.acknowledgeAlert(alertId, request));
    }

    @Operation(summary = "Silence alert notifications", description = "Silences alert notifications for the specified duration.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Alert silenced", content = @Content(schema = @Schema(implementation = AlertActionResponse.class))),
            @ApiResponse(responseCode = "404", description = "Alert not found", content = @Content(schema = @Schema(implementation = AdminErrorResponse.class)))
    })
    @PostMapping("/{alertId}/silence")
    public ResponseEntity<AlertActionResponse> silenceAlert(@PathVariable("alertId") String alertId,
                                                            @Valid @RequestBody SilenceAlertRequest request) {
        return ResponseEntity.ok(alertService.silenceAlert(alertId, request));
    }

    @Operation(summary = "Unsilence an alert", description = "Re-enables alert notifications for a previously silenced alert.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Alert unsilenced", content = @Content(schema = @Schema(implementation = AlertActionResponse.class))),
            @ApiResponse(responseCode = "404", description = "Alert not found", content = @Content(schema = @Schema(implementation = AdminErrorResponse.class)))
    })
    @PostMapping("/{alertId}/unsilence")
    public ResponseEntity<AlertActionResponse> unsilenceAlert(@PathVariable("alertId") String alertId,
                                                              @Valid @RequestBody AlertActionRequest request) {
        return ResponseEntity.ok(alertService.unsilenceAlert(alertId, request));
    }

    @Operation(summary = "Escalate an alert", description = "Escalates an alert to the requested policy, mirroring the CLI escalation command.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Alert escalated", content = @Content(schema = @Schema(implementation = AlertActionResponse.class))),
            @ApiResponse(responseCode = "404", description = "Alert not found", content = @Content(schema = @Schema(implementation = AdminErrorResponse.class)))
    })
    @PostMapping("/{alertId}/escalate")
    public ResponseEntity<AlertActionResponse> escalateAlert(@PathVariable("alertId") String alertId,
                                                             @Valid @RequestBody EscalateAlertRequest request) {
        return ResponseEntity.ok(alertService.escalateAlert(alertId, request));
    }
}
