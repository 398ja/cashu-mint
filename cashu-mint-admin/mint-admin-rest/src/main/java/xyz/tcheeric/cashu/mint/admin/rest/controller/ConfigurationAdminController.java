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

import xyz.tcheeric.cashu.mint.admin.rest.dto.common.AdminErrorResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.common.PagedResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.configuration.ApplyConfigurationRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.configuration.ConfigurationActionResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.configuration.ConfigurationRevisionResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.configuration.PreviewConfigurationRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.configuration.RollbackConfigurationRequest;
import xyz.tcheeric.cashu.mint.admin.rest.service.AdminConfigurationService;

/**
 * REST endpoints for configuration management of mint instances.
 */
@Tag(name = "Admin Configuration", description = "Configuration workflows mirrored from CLI commands")
@SecurityRequirement(name = "AdminToken")
@SecurityRequirement(name = "AdminRoles")
@RestController
@RequestMapping("/admin/configuration")
public class ConfigurationAdminController {

    private final AdminConfigurationService configurationService;

    public ConfigurationAdminController(final AdminConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    @Operation(summary = "List configuration revisions", description = "Returns the configuration revision history for a mint.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Revisions listed"),
            @ApiResponse(responseCode = "404", description = "Mint not found", content = @Content(schema = @Schema(implementation = AdminErrorResponse.class)))
    })
    @GetMapping("/mints/{mintId}/revisions")
    public ResponseEntity<PagedResponse<ConfigurationRevisionResponse>> listRevisions(
            @PathVariable("mintId") String mintId,
            @Parameter(description = "Page number (zero-indexed)") @RequestParam(defaultValue = "0") final int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") final int size) {
        return ResponseEntity.ok(configurationService.listRevisions(mintId, page, size));
    }

    @Operation(summary = "Preview configuration changes", description = "Previews configuration updates without persisting them, matching the CLI preview mode.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Preview generated", content = @Content(schema = @Schema(implementation = ConfigurationActionResponse.class))),
            @ApiResponse(responseCode = "409", description = "Revision conflict", content = @Content(schema = @Schema(implementation = AdminErrorResponse.class)))
    })
    @PostMapping("/mints/{mintId}/preview")
    public ResponseEntity<ConfigurationActionResponse> previewConfiguration(@PathVariable("mintId") String mintId,
                                                                           @Valid @RequestBody PreviewConfigurationRequest request) {
        return ResponseEntity.ok(configurationService.previewConfiguration(mintId, request));
    }

    @Operation(summary = "Apply configuration changes", description = "Applies configuration updates to a mint, mirroring the CLI apply command.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Configuration applied", content = @Content(schema = @Schema(implementation = ConfigurationActionResponse.class)))
    })
    @PostMapping("/mints/{mintId}/apply")
    public ResponseEntity<ConfigurationActionResponse> applyConfiguration(@PathVariable("mintId") String mintId,
                                                                          @Valid @RequestBody ApplyConfigurationRequest request) {
        return ResponseEntity.ok(configurationService.applyConfiguration(mintId, request));
    }

    @Operation(summary = "Rollback configuration", description = "Rolls back a mint to a previous configuration revision.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rollback complete", content = @Content(schema = @Schema(implementation = ConfigurationActionResponse.class))),
            @ApiResponse(responseCode = "404", description = "Revision not found", content = @Content(schema = @Schema(implementation = AdminErrorResponse.class)))
    })
    @PostMapping("/mints/{mintId}/rollback")
    public ResponseEntity<ConfigurationActionResponse> rollbackConfiguration(@PathVariable("mintId") String mintId,
                                                                            @Valid @RequestBody RollbackConfigurationRequest request) {
        return ResponseEntity.ok(configurationService.rollbackConfiguration(mintId, request));
    }
}
