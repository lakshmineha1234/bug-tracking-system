package com.bugtracker.dto;

import com.bugtracker.entity.Bug;
import com.bugtracker.entity.BugStatus;
import com.bugtracker.entity.Priority;
import com.bugtracker.entity.Severity;

import java.time.Instant;
import java.util.List;

/**
 * A bug plus what the current user may do with it, so the frontend can show the right controls.
 * The backend still checks every action itself.
 */
public record BugResponse(Long id, String title, String description, String stepsToReproduce,
                          String expectedResult, String actualResult, Priority priority, Severity severity,
                          BugStatus status, ProjectRef project, UserSummary reportedBy, UserSummary assignedTo,
                          Instant createdAt, Instant updatedAt,
                          List<BugStatus> allowedTransitions, boolean canEdit, boolean canAssign) {

    public record ProjectRef(Long id, String projectName) {
    }

    public static BugResponse from(Bug b, List<BugStatus> allowedTransitions, boolean canEdit, boolean canAssign) {
        return new BugResponse(b.getId(), b.getTitle(), b.getDescription(), b.getStepsToReproduce(),
                b.getExpectedResult(), b.getActualResult(), b.getPriority(), b.getSeverity(), b.getStatus(),
                new ProjectRef(b.getProject().getId(), b.getProject().getProjectName()),
                UserSummary.from(b.getReportedBy()), UserSummary.from(b.getAssignedTo()),
                b.getCreatedAt(), b.getUpdatedAt(), allowedTransitions, canEdit, canAssign);
    }
}
