package com.bugtracker.service;

import com.bugtracker.entity.BugStatus;

/** Published when a bug's status changes. */
public record BugStatusChangedEvent(Long bugId, BugStatus from, BugStatus to, Long changedById) {
}
