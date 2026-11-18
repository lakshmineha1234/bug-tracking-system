package com.bugtracker.dto;

import com.bugtracker.entity.AssignmentHistory;

import java.time.Instant;

/** oldAssignee is null if the bug was unassigned before; newAssignee is null if it was unassigned. */
public record AssignmentHistoryResponse(Long id, Long bugId, String bugTitle, UserSummary changedBy,
                                        UserSummary oldAssignee, UserSummary newAssignee, Instant changedAt) {

    public static AssignmentHistoryResponse from(AssignmentHistory h) {
        return new AssignmentHistoryResponse(h.getId(), h.getBug().getId(), h.getBug().getTitle(),
                UserSummary.from(h.getChangedBy()), UserSummary.from(h.getOldAssignee()),
                UserSummary.from(h.getNewAssignee()), h.getChangedAt());
    }
}
