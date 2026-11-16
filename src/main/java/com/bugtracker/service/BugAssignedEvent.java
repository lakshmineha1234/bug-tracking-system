package com.bugtracker.service;

/** Published when a bug is assigned to a developer who was not already its assignee. */
public record BugAssignedEvent(Long bugId, Long assigneeId, Long assignedById) {
}
