package xyz.tcheeric.cashu.mint.admin.rest.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import xyz.tcheeric.cashu.mint.admin.domain.AdminPermission;
import xyz.tcheeric.cashu.mint.admin.rest.config.AdminOpenApiConfiguration;
import xyz.tcheeric.nap.spring.annotation.RequiresPermission;

import xyz.tcheeric.cashu.mint.admin.rest.dto.audit.AuditEventResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.common.PagedResponse;
import xyz.tcheeric.cashu.mint.admin.rest.service.AdminAuditQueryService;

/**
 * Unified audit timeline endpoint sourced from audit_events table.
 */
@Tag(name = "Admin Audit", description = "Audit timeline for the admin web interface")
@SecurityRequirement(name = AdminOpenApiConfiguration.ADMIN_SESSION_SCHEME)
@RequiresPermission(AdminPermission.Keys.AUDIT_READ)
@RestController
@RequestMapping("/admin/audit")
public class AuditAdminController {

    private final AdminAuditQueryService auditQueryService;

    public AuditAdminController(final AdminAuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    @Operation(summary = "List audit events", description = "Returns a paginated list of audit events from the audit_events table.")
    @ApiResponse(responseCode = "200", description = "Audit events retrieved")
    @GetMapping("/events")
    public ResponseEntity<PagedResponse<AuditEventResponse>> listAuditEvents(
            @Parameter(description = "Filter by mint ID") @RequestParam(required = false) final String mintId,
            @Parameter(description = "Filter by actor") @RequestParam(required = false) final String actor,
            @Parameter(description = "Filter by action") @RequestParam(required = false) final String action,
            @Parameter(description = "Page number (zero-indexed)") @RequestParam(defaultValue = "0") final int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") final int size) {
        return ResponseEntity.ok(auditQueryService.listAuditEvents(mintId, actor, action, page, size));
    }
}
