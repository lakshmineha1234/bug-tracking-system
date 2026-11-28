package com.bugtracker.service;

/** Published when a user's password changes, by the user themself or by an admin. */
public record PasswordChangedEvent(Long userId, Long changedById) {
}
