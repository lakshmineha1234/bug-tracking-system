package com.bugtracker.service;

import com.bugtracker.entity.BugStatus;

/**
 * Published when a bug's status changes. assigneeId is the developer assigned at the moment of the change
 * (null if none), so the email goes to them even if the bug is reassigned before it is sent.
 */
public record BugStatusChangedEvent(Long bugId, BugStatus from, BugStatus to, Long changedById, Long assigneeId) {
}
