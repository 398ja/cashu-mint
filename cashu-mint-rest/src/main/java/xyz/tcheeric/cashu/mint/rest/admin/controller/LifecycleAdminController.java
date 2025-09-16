package xyz.tcheeric.cashu.mint.rest.admin.controller;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import xyz.tcheeric.cashu.mint.rest.admin.dto.common.AdminErrorResponse;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.CreateMintRequest;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.LifecycleActionResponse;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.LifecycleChangeRequest;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.UpdateMintRequest;
import xyz.tcheeric.cashu.mint.rest.admin.service.AdminLifecycleService;

/**
 * REST endpoints covering mint lifecycle workflows. They validate payloads and expose
 * request DTOs that will later be passed to the lifecycle use case.
 */
@Tag(name = "Admin Lifecycle", description = "Lifecycle workflows mirrored from CLI commands")
@SecurityRequirement(name = "AdminToken")
@SecurityRequirement(name = "AdminRoles")
@RestController
@RequestMapping("/admin/lifecycle")
public class LifecycleAdminController {

    private final AdminLifecycleService lifecycleService;

    public LifecycleAdminController(final AdminLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @Operation(summary = "Provision a mint", description = "Creates a new mint instance just like the `mint create` CLI command.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Mint created", content = @Content(schema = @Schema(implementation = LifecycleActionResponse.class))),
            @ApiResponse(responseCode = "409", description = "Mint already exists", content = @Content(schema = @Schema(implementation = AdminErrorResponse.class)))
    })
    @PostMapping("/mints")
    public ResponseEntity<LifecycleActionResponse> createMint(@Valid @RequestBody CreateMintRequest request) {
        return ResponseEntity.ok(lifecycleService.createMint(request));
    }

    @Operation(summary = "Update a mint", description = "Updates metadata or configuration bindings, mirroring `mint update`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Mint updated", content = @Content(schema = @Schema(implementation = LifecycleActionResponse.class))),
            @ApiResponse(responseCode = "404", description = "Mint not found", content = @Content(schema = @Schema(implementation = AdminErrorResponse.class)))
    })
    @PutMapping("/mints/{mintId}")
    public ResponseEntity<LifecycleActionResponse> updateMint(@PathVariable("mintId") String mintId,
                                                              @Valid @RequestBody UpdateMintRequest request) {
        return ResponseEntity.ok(lifecycleService.updateMint(mintId, request));
    }

    @Operation(summary = "Pause mint operations", description = "Pauses a mint for maintenance, mirroring `mint pause`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Mint paused", content = @Content(schema = @Schema(implementation = LifecycleActionResponse.class))),
            @ApiResponse(responseCode = "404", description = "Mint not found", content = @Content(schema = @Schema(implementation = AdminErrorResponse.class)))
    })
    @PostMapping("/mints/{mintId}/pause")
    public ResponseEntity<LifecycleActionResponse> pauseMint(@PathVariable("mintId") String mintId,
                                                             @Valid @RequestBody LifecycleChangeRequest request) {
        return ResponseEntity.ok(lifecycleService.pauseMint(mintId, request));
    }

    @Operation(summary = "Resume mint operations", description = "Resumes a paused mint, mirroring `mint resume`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Mint resumed", content = @Content(schema = @Schema(implementation = LifecycleActionResponse.class))),
            @ApiResponse(responseCode = "404", description = "Mint not found", content = @Content(schema = @Schema(implementation = AdminErrorResponse.class)))
    })
    @PostMapping("/mints/{mintId}/resume")
    public ResponseEntity<LifecycleActionResponse> resumeMint(@PathVariable("mintId") String mintId,
                                                              @Valid @RequestBody LifecycleChangeRequest request) {
        return ResponseEntity.ok(lifecycleService.resumeMint(mintId, request));
    }

    @Operation(summary = "Retire a mint", description = "Retires a mint and revokes access, mirroring `mint retire`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Mint retired", content = @Content(schema = @Schema(implementation = LifecycleActionResponse.class))),
            @ApiResponse(responseCode = "404", description = "Mint not found", content = @Content(schema = @Schema(implementation = AdminErrorResponse.class)))
    })
    @PostMapping("/mints/{mintId}/retire")
    public ResponseEntity<LifecycleActionResponse> retireMint(@PathVariable("mintId") String mintId,
                                                              @Valid @RequestBody LifecycleChangeRequest request) {
        return ResponseEntity.ok(lifecycleService.retireMint(mintId, request));
    }
}
