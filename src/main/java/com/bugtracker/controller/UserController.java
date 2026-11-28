package com.bugtracker.controller;

import com.bugtracker.dto.RegisterRequest;
import com.bugtracker.dto.ResetPasswordRequest;
import org.springframework.web.bind.annotation.PutMapping;
import com.bugtracker.dto.UpdateUserRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import com.bugtracker.dto.UserResponse;
import com.bugtracker.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Admin-only user management. */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<UserResponse> list() {
        return userService.list();
    }

    /** Admin creates an account (any role) with an initial password the admin shares with the user. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody RegisterRequest req) {
        return userService.createByAdmin(req);
    }

    /** Admin sets a new password for a user (for example after a forgotten password). */
    @PutMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest req) {
        userService.resetPassword(id, req);
    }

    @PatchMapping("/{id}")
    public UserResponse update(@PathVariable Long id, @RequestBody UpdateUserRequest req) {
        return userService.update(id, req);
    }
}
