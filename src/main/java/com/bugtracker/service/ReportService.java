package com.bugtracker.service;

import com.bugtracker.dto.ReportResponse;
import com.bugtracker.dto.ReportResponse.DurationStat;
import com.bugtracker.dto.ReportResponse.GroupStat;
import com.bugtracker.dto.ReportResponse.WeekStat;
import com.bugtracker.entity.Bug;
import com.bugtracker.entity.BugHistory;
import com.bugtracker.entity.BugStatus;
import com.bugtracker.entity.Project;
import com.bugtracker.entity.Role;
import com.bugtracker.entity.User;
import com.bugtracker.exception.BadRequestException;
import com.bugtracker.exception.ForbiddenException;
import com.bugtracker.exception.NotFoundException;
import com.bugtracker.repository.BugHistoryRepository;
import com.bugtracker.repository.BugRepository;
import com.bugtracker.repository.ProjectRepository;
import com.bugtracker.security.CurrentUserService;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds bug reports: weekly opened/closed/backlog trends, time to fix and close, and breakdowns. */
@Service
public class ReportService {

    public static final int MIN_WEEKS = 4;
    public static final int MAX_WEEKS = 52;

    private final BugRepository bugs;
    private final BugHistoryRepository history;
    private final ProjectRepository projects;
    private final CurrentUserService currentUser;

    /** Weeks start on Monday in this clock's time zone. Replaceable in tests. */
    Clock clock = Clock.systemDefaultZone();

    public ReportService(BugRepository bugs, BugHistoryRepository history, ProjectRepository projects,
                         CurrentUserService currentUser) {
        this.bugs = bugs;
        this.history = history;
        this.projects = projects;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public ReportResponse report(int weeks, Long projectId) {
        if (weeks < MIN_WEEKS || weeks > MAX_WEEKS) {
            throw new BadRequestException("weeks must be between " + MIN_WEEKS + " and " + MAX_WEEKS);
        }
        User me = currentUser.get();
        Specification<Bug> spec = BugSpecifications.visibleTo(me);
        String projectName = null;
        if (projectId != null) {
            Project project = projects.findById(projectId)
                    .orElseThrow(() -> new NotFoundException("Project " + projectId + " not found"));
            if (me.getRole() != Role.ADMIN && !project.isMember(me)) {
                throw new ForbiddenException("You are not a member of this project");
            }
            projectName = project.getProjectName();
            spec = spec.and((root, query, cb) -> cb.equal(root.get("project").get("id"), projectId));
        }

        List<Bug> visible = bugs.findAll(spec, Sort.by("id"));
        Map<Long, List<BugHistory>> events = visible.isEmpty() ? Map.of()
                : history.findByBugIdInOrderByIdAsc(visible.stream().map(Bug::getId).toList()).stream()
                .collect(Collectors.groupingBy(h -> h.getBug().getId()));

        ZoneId zone = clock.getZone();
        Instant now = clock.instant();
        LocalDate thisWeek = LocalDate.now(clock).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate firstWeek = thisWeek.minusWeeks(weeks - 1L);
        Instant periodStart = firstWeek.atStartOfDay(zone).toInstant();

        List<WeekStat> weekly = new ArrayList<>();
        for (int i = 0; i < weeks; i++) {
            LocalDate weekStart = firstWeek.plusWeeks(i);
            Instant start = weekStart.atStartOfDay(zone).toInstant();
            Instant end = weekStart.plusWeeks(1).atStartOfDay(zone).toInstant();
            Instant snapshot = end.isAfter(now) ? now : end;
            long opened = visible.stream().filter(b -> within(b.getCreatedAt(), start, end)).count();
            long closed = events.values().stream().flatMap(List::stream)
                    .filter(h -> h.getNewStatus() == BugStatus.CLOSED && within(h.getChangedAt(), start, end))
                    .count();
            long openAtEnd = visible.stream()
                    .filter(b -> b.getCreatedAt().isBefore(snapshot))
                    .filter(b -> statusAt(b, events.getOrDefault(b.getId(), List.of()), snapshot) != BugStatus.CLOSED)
                    .count();
            weekly.add(new WeekStat(weekStart, opened, closed, openAtEnd));
        }

        List<Double> fixDays = new ArrayList<>();
        List<Double> closeDays = new ArrayList<>();
        for (Bug b : visible) {
            List<BugHistory> bugEvents = events.getOrDefault(b.getId(), List.of());
            firstEvent(bugEvents, BugStatus.FIXED)
                    .filter(t -> !t.isBefore(periodStart))
                    .ifPresent(t -> fixDays.add(days(b.getCreatedAt(), t)));
            if (b.getStatus() == BugStatus.CLOSED) {
                lastEvent(bugEvents, BugStatus.CLOSED)
                        .filter(t -> !t.isBefore(periodStart))
                        .ifPresent(t -> closeDays.add(days(b.getCreatedAt(), t)));
            }
        }

        long closedNow = visible.stream().filter(b -> b.getStatus() == BugStatus.CLOSED).count();
        return new ReportResponse(firstWeek, LocalDate.now(clock), weeks, projectId, projectName,
                me.getName(), now,
                visible.size(), visible.size() - closedNow, closedNow,
                weekly.stream().mapToLong(WeekStat::opened).sum(),
                weekly.stream().mapToLong(WeekStat::closed).sum(),
                stat(fixDays), stat(closeDays),
                weekly,
                groups(visible, events, b -> b.getProject().getProjectName()),
                groups(visible, events, b -> b.getAssignedTo() == null ? "Unassigned" : b.getAssignedTo().getName()));
    }

    private static List<GroupStat> groups(List<Bug> visible, Map<Long, List<BugHistory>> events,
                                          Function<Bug, String> key) {
        Map<String, List<Bug>> grouped = visible.stream()
                .collect(Collectors.groupingBy(key, () -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER), Collectors.toList()));
        List<GroupStat> result = new ArrayList<>();
        grouped.forEach((name, list) -> {
            long closed = list.stream().filter(b -> b.getStatus() == BugStatus.CLOSED).count();
            List<Double> closeDays = list.stream()
                    .filter(b -> b.getStatus() == BugStatus.CLOSED)
                    .map(b -> lastEvent(events.getOrDefault(b.getId(), List.of()), BugStatus.CLOSED)
                            .map(t -> days(b.getCreatedAt(), t)).orElse(null))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            result.add(new GroupStat(name, list.size(), list.size() - closed, closed, stat(closeDays).averageDays()));
        });
        return result;
    }

    /** The bug's status at a moment, from its recorded history. */
    private static BugStatus statusAt(Bug bug, List<BugHistory> bugEvents, Instant moment) {
        BugStatus status = BugStatus.OPEN;
        for (BugHistory h : bugEvents) {
            if (h.getChangedAt().isAfter(moment)) {
                break;
            }
            status = h.getNewStatus();
        }
        return status;
    }

    private static Optional<Instant> firstEvent(List<BugHistory> bugEvents, BugStatus status) {
        return bugEvents.stream().filter(h -> h.getNewStatus() == status).map(BugHistory::getChangedAt).findFirst();
    }

    private static Optional<Instant> lastEvent(List<BugHistory> bugEvents, BugStatus status) {
        return bugEvents.stream().filter(h -> h.getNewStatus() == status).map(BugHistory::getChangedAt)
                .max(Comparator.naturalOrder());
    }

    private static boolean within(Instant t, Instant start, Instant end) {
        return t != null && !t.isBefore(start) && t.isBefore(end);
    }

    private static double days(Instant from, Instant to) {
        return Duration.between(from, to).toMinutes() / 1440.0;
    }

    private static DurationStat stat(List<Double> values) {
        if (values.isEmpty()) {
            return new DurationStat(0, null, null);
        }
        List<Double> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        double average = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double median = n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
        return new DurationStat(n, round1(average), round1(median));
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
