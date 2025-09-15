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

import xyz.tcheeric.cashu.mint.rest.admin.dto.users.AssignRolesRequest;
import xyz.tcheeric.cashu.mint.rest.admin.dto.users.CreateUserRequest;
import xyz.tcheeric.cashu.mint.rest.admin.dto.users.ResetCredentialsRequest;
import xyz.tcheeric.cashu.mint.rest.admin.dto.users.UpdateUserRequest;
import xyz.tcheeric.cashu.mint.rest.admin.dto.users.UserLifecycleRequest;

/**
 * REST endpoints for operator account management.
 */
@RestController
@RequestMapping("/admin/users")
public class UsersAdminController {

    @PostMapping
    public void createUser(@Valid @RequestBody CreateUserRequest request) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
    }

    @PutMapping("/{userId}")
    public void updateUser(@PathVariable("userId") String userId, @Valid @RequestBody UpdateUserRequest request) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
    }

    @PostMapping("/{userId}/roles")
    public void assignRoles(@PathVariable("userId") String userId, @Valid @RequestBody AssignRolesRequest request) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
    }

    @PostMapping("/{userId}/reset-credentials")
    public void resetCredentials(@PathVariable("userId") String userId, @Valid @RequestBody ResetCredentialsRequest request) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
    }

    @PostMapping("/{userId}/deactivate")
    public void deactivateUser(@PathVariable("userId") String userId, @Valid @RequestBody UserLifecycleRequest request) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
    }
}
