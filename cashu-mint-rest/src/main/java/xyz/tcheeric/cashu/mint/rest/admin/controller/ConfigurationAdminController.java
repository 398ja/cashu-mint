package xyz.tcheeric.cashu.mint.rest.admin.controller;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import xyz.tcheeric.cashu.mint.rest.admin.dto.configuration.ApplyConfigurationRequest;
import xyz.tcheeric.cashu.mint.rest.admin.dto.configuration.PreviewConfigurationRequest;
import xyz.tcheeric.cashu.mint.rest.admin.dto.configuration.RollbackConfigurationRequest;

/**
 * REST endpoints for configuration management of mint instances.
 */
@RestController
@RequestMapping("/admin/configuration")
public class ConfigurationAdminController {

    @PostMapping("/mints/{mintId}/preview")
    public void previewConfiguration(@PathVariable("mintId") String mintId,
                                     @Valid @RequestBody PreviewConfigurationRequest request) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
    }

    @PostMapping("/mints/{mintId}/apply")
    public void applyConfiguration(@PathVariable("mintId") String mintId,
                                   @Valid @RequestBody ApplyConfigurationRequest request) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
    }

    @PostMapping("/mints/{mintId}/rollback")
    public void rollbackConfiguration(@PathVariable("mintId") String mintId,
                                      @Valid @RequestBody RollbackConfigurationRequest request) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
    }
}
