package xyz.tcheeric.cashu.mint.rest.admin.controller;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import xyz.tcheeric.cashu.mint.rest.admin.dto.alerts.AlertActionRequest;
import xyz.tcheeric.cashu.mint.rest.admin.dto.alerts.CreateAlertRequest;
import xyz.tcheeric.cashu.mint.rest.admin.dto.alerts.EscalateAlertRequest;
import xyz.tcheeric.cashu.mint.rest.admin.dto.alerts.SilenceAlertRequest;

/**
 * REST endpoints for alert and notification workflows.
 */
@RestController
@RequestMapping("/admin/alerts")
public class AlertsAdminController {

    @PostMapping
    public void createAlert(@Valid @RequestBody CreateAlertRequest request) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
    }

    @PostMapping("/{alertId}/acknowledge")
    public void acknowledgeAlert(@PathVariable("alertId") String alertId, @Valid @RequestBody AlertActionRequest request) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
    }

    @PostMapping("/{alertId}/silence")
    public void silenceAlert(@PathVariable("alertId") String alertId, @Valid @RequestBody SilenceAlertRequest request) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
    }

    @PostMapping("/{alertId}/unsilence")
    public void unsilenceAlert(@PathVariable("alertId") String alertId, @Valid @RequestBody AlertActionRequest request) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
    }

    @PostMapping("/{alertId}/escalate")
    public void escalateAlert(@PathVariable("alertId") String alertId, @Valid @RequestBody EscalateAlertRequest request) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
    }
}
