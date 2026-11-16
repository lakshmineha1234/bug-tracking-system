package com.bugtracker.dto;

import com.bugtracker.entity.BugHistory;
import com.bugtracker.entity.BugStatus;

import java.time.Instant;

public record HistoryResponse(Long id, Long bugId, String bugTitle, UserSummary changedBy,
                              BugStatus oldStatus, BugStatus newStatus, Instant changedAt) {

    public static HistoryResponse from(BugHistory h) {
        return new HistoryResponse(h.getId(), h.getBug().getId(), h.getBug().getTitle(),
                UserSummary.from(h.getChangedBy()), h.getOldStatus(), h.getNewStatus(), h.getChangedAt());
    }
}
