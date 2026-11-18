package com.bugtracker.service;

import com.bugtracker.dto.AssignmentHistoryResponse;
import com.bugtracker.dto.DashboardSummary;
import com.bugtracker.dto.HistoryResponse;
import com.bugtracker.entity.Bug;
import com.bugtracker.entity.BugStatus;
import com.bugtracker.entity.Priority;
import com.bugtracker.entity.Severity;
import com.bugtracker.entity.User;
import com.bugtracker.repository.AssignmentHistoryRepository;
import com.bugtracker.repository.BugHistoryRepository;
import com.bugtracker.repository.BugRepository;
import com.bugtracker.security.CurrentUserService;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/** Statistics over the bugs the current user can access. */
@Service
public class DashboardService {

    private final BugRepository bugs;
    private final BugHistoryRepository history;
    private final AssignmentHistoryRepository assignmentHistory;
    private final CurrentUserService currentUser;

    public DashboardService(BugRepository bugs, BugHistoryRepository history,
                            AssignmentHistoryRepository assignmentHistory, CurrentUserService currentUser) {
        this.bugs = bugs;
        this.history = history;
        this.assignmentHistory = assignmentHistory;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public DashboardSummary summary() {
        User me = currentUser.get();
        List<Bug> visible = bugs.findAll(BugSpecifications.visibleTo(me), Sort.by("id"));

        long active = visible.stream().filter(DashboardService::isActive).count();
        long criticalActive = visible.stream()
                .filter(b -> isActive(b) && b.getPriority() == Priority.CRITICAL)
                .count();

        Map<String, Long> byProject = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, Long> byAssignee = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Bug b : visible) {
            byProject.merge(b.getProject().getProjectName(), 1L, Long::sum);
            String assignee = b.getAssignedTo() == null ? "Unassigned" : b.getAssignedTo().getName();
            byAssignee.merge(assignee, 1L, Long::sum);
        }

        List<Long> ids = visible.stream().map(Bug::getId).toList();
        List<HistoryResponse> recent = ids.isEmpty()
                ? List.of()
                : history.findTop10ByBugIdInOrderByIdDesc(ids).stream().map(HistoryResponse::from).toList();
        List<AssignmentHistoryResponse> recentAssignments = ids.isEmpty()
                ? List.of()
                : assignmentHistory.findTop10ByBugIdInOrderByIdDesc(ids).stream()
                        .map(AssignmentHistoryResponse::from).toList();

        return new DashboardSummary(visible.size(), active, criticalActive,
                countByEnum(BugStatus.values(), visible, Bug::getStatus),
                countByEnum(Priority.values(), visible, Bug::getPriority),
                countByEnum(Severity.values(), visible, Bug::getSeverity),
                byProject, byAssignee, recent, recentAssignments);
    }

    private static boolean isActive(Bug b) {
        return b.getStatus() != BugStatus.CLOSED;
    }

    /** Counts per enum value, including values with zero bugs, in declaration order. */
    private static <E extends Enum<E>> Map<String, Long> countByEnum(E[] values, List<Bug> list, Function<Bug, E> key) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (E value : values) {
            counts.put(value.name(), 0L);
        }
        for (Bug b : list) {
            counts.merge(key.apply(b).name(), 1L, Long::sum);
        }
        return counts;
    }
}
