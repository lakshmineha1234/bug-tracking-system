package com.bugtracker.dto;

import com.bugtracker.entity.Role;
import com.bugtracker.entity.User;

public record UserSummary(Long id, String name, Role role) {

    public static UserSummary from(User u) {
        return u == null ? null : new UserSummary(u.getId(), u.getName(), u.getRole());
    }
}
