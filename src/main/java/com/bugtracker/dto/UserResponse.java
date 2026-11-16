package com.bugtracker.dto;

import com.bugtracker.entity.Role;
import com.bugtracker.entity.User;

import java.time.Instant;

public record UserResponse(Long id, String name, String email, Role role, boolean enabled, Instant createdAt) {

    public static UserResponse from(User u) {
        return new UserResponse(u.getId(), u.getName(), u.getEmail(), u.getRole(), u.isEnabled(), u.getCreatedAt());
    }
}
