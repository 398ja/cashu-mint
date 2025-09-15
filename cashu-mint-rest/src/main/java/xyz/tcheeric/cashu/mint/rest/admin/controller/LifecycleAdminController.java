package xyz.tcheeric.cashu.mint.rest.admin.controller;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.CreateMintRequest;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.LifecycleChangeRequest;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.UpdateMintRequest;

/**
 * REST endpoints covering mint lifecycle workflows. They validate payloads and expose
 * request DTOs that will later be passed to the lifecycle use case.
 */
@RestController
@RequestMapping("/admin/lifecycle")
public class LifecycleAdminController {

    @PostMapping("/mints")
    public void createMint(@Valid @RequestBody CreateMintRequest request) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
    }

    @PutMapping("/mints/{mintId}")
    public void updateMint(@PathVariable("mintId") String mintId, @Valid @RequestBody UpdateMintRequest request) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
    }

    @PostMapping("/mints/{mintId}/pause")
    public void pauseMint(@PathVariable("mintId") String mintId, @Valid @RequestBody LifecycleChangeRequest request) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
    }

    @PostMapping("/mints/{mintId}/resume")
    public void resumeMint(@PathVariable("mintId") String mintId, @Valid @RequestBody LifecycleChangeRequest request) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
    }

    @PostMapping("/mints/{mintId}/retire")
    public void retireMint(@PathVariable("mintId") String mintId, @Valid @RequestBody LifecycleChangeRequest request) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
    }
}
