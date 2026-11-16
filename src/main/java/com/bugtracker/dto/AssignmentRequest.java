package com.bugtracker.dto;

/** A null assigneeId unassigns the bug. */
public record AssignmentRequest(Long assigneeId) {
}
