package com.bugtracker.dto;

import java.util.List;
import java.util.Map;

public record DashboardSummary(long total, long active, long criticalActive,
                               Map<String, Long> byStatus, Map<String, Long> byPriority,
                               Map<String, Long> bySeverity, Map<String, Long> byProject,
                               Map<String, Long> byAssignee, List<HistoryResponse> recentActivity) {
}
