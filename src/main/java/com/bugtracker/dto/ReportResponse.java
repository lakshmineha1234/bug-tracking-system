package com.bugtracker.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A bug report over the accessible bugs, optionally for one project.
 * Weekly figures and the time-to-fix/close statistics cover the period [from, to];
 * the totals and the per-project / per-assignee tables describe all bugs as they are now.
 * "Open" means not closed (open, in progress or fixed awaiting test).
 */
public record ReportResponse(LocalDate from, LocalDate to, int weeks, Long projectId, String projectName,
                             String generatedFor, Instant generatedAt,
                             long totalBugs, long openBugs, long closedBugs,
                             long openedInPeriod, long closedInPeriod,
                             DurationStat timeToFix, DurationStat timeToClose,
                             List<WeekStat> weekly, List<GroupStat> byProject, List<GroupStat> byAssignee) {

    /** One week, Monday to Sunday. openAtEnd is the backlog at the end of the week (or now, for this week). */
    public record WeekStat(LocalDate weekStart, long opened, long closed, long openAtEnd) {
    }

    /** Days from report to fix/close. Averages are null when count is 0. */
    public record DurationStat(long count, Double averageDays, Double medianDays) {
    }

    public record GroupStat(String name, long total, long open, long closed, Double averageDaysToClose) {
    }
}
