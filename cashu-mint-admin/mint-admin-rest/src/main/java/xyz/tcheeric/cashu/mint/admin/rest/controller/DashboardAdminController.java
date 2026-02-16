package xyz.tcheeric.cashu.mint.admin.rest.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import xyz.tcheeric.cashu.mint.admin.rest.dto.dashboard.DashboardSummaryResponse;
import xyz.tcheeric.cashu.mint.admin.rest.service.AdminDashboardService;

/**
 * Aggregated dashboard endpoint for the web UI.
 */
@Tag(name = "Admin Dashboard", description = "Dashboard summary for the admin web interface")
@SecurityRequirement(name = "AdminToken")
@RestController
@RequestMapping("/admin/dashboard")
public class DashboardAdminController {

    private final AdminDashboardService dashboardService;

    public DashboardAdminController(final AdminDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @Operation(summary = "Get dashboard summary", description = "Returns aggregated counts of mints by state, alerts by severity, and active controls.")
    @ApiResponse(responseCode = "200", description = "Dashboard summary retrieved")
    @GetMapping("/summary")
    public ResponseEntity<DashboardSummaryResponse> getSummary() {
        return ResponseEntity.ok(dashboardService.getSummary());
    }
}
