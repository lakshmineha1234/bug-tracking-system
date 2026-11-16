package com.bugtracker.dto;

import com.bugtracker.entity.BugStatus;
import com.bugtracker.entity.Priority;
import com.bugtracker.entity.Severity;

/** Optional search criteria for listing bugs. Null fields are ignored. */
public record BugFilter(String q, BugStatus status, Priority priority, Severity severity,
                        Long projectId, Long assigneeId, Boolean unassigned) {

    public static BugFilter none() {
        return new BugFilter(null, null, null, null, null, null, null);
    }
}
