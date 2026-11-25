package com.bugtracker.service;

import com.bugtracker.dto.ReportResponse;
import com.bugtracker.dto.ReportResponse.GroupStat;
import com.bugtracker.dto.ReportResponse.WeekStat;
import com.bugtracker.entity.Bug;
import com.bugtracker.entity.BugHistory;
import com.bugtracker.entity.BugStatus;
import com.bugtracker.entity.Project;
import com.bugtracker.entity.Role;
import com.bugtracker.entity.User;
import com.bugtracker.exception.BadRequestException;
import com.bugtracker.repository.BugHistoryRepository;
import com.bugtracker.repository.BugRepository;
import com.bugtracker.repository.ProjectRepository;
import com.bugtracker.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Report calculations against a fixed "now": Wednesday 25 Nov 2026, 12:00 UTC.
 * With 4 weeks the period is the weeks starting Mon 2, 9, 16 and 23 Nov.
 */
@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock private BugRepository bugs;
    @Mock private BugHistoryRepository history;
    @Mock private ProjectRepository projects;
    @Mock private CurrentUserService currentUser;
    @InjectMocks private ReportService service;

    private User admin;
    private User developer;
    private Project project;
    private final List<Bug> allBugs = new ArrayList<>();
    private final List<BugHistory> allEvents = new ArrayList<>();
    private long nextEventId = 1;

    @BeforeEach
    void setUp() {
        service.clock = Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneOffset.UTC);
        admin = user(1L, "Admin", Role.ADMIN);
        developer = user(2L, "Dev", Role.DEVELOPER);
        project = new Project();
        project.setProjectName("Web App");
    }

    @Test
    void weeklyTrendsDurationsAndBreakdowns() {
        // Reported 3 Nov, fixed 5 Nov (2 days), closed 10 Nov (7 days).
        Bug b1 = bug(1, "2026-11-03T10:00:00Z", BugStatus.CLOSED, developer);
        event(b1, null, BugStatus.OPEN, "2026-11-03T10:00:00Z");
        event(b1, BugStatus.OPEN, BugStatus.IN_PROGRESS, "2026-11-04T10:00:00Z");
        event(b1, BugStatus.IN_PROGRESS, BugStatus.FIXED, "2026-11-05T10:00:00Z");
        event(b1, BugStatus.FIXED, BugStatus.CLOSED, "2026-11-10T10:00:00Z");
        // Reported 10 Nov, still open.
        Bug b2 = bug(2, "2026-11-10T10:00:00Z", BugStatus.OPEN, null);
        event(b2, null, BugStatus.OPEN, "2026-11-10T10:00:00Z");
        // Reported 24 Nov, in progress.
        Bug b3 = bug(3, "2026-11-24T10:00:00Z", BugStatus.IN_PROGRESS, null);
        event(b3, null, BugStatus.OPEN, "2026-11-24T10:00:00Z");
        event(b3, BugStatus.OPEN, BugStatus.IN_PROGRESS, "2026-11-25T08:00:00Z");
        // Reported and closed before the period: counts in totals, not in the period figures.
        Bug b4 = bug(4, "2026-10-20T10:00:00Z", BugStatus.CLOSED, null);
        event(b4, null, BugStatus.OPEN, "2026-10-20T10:00:00Z");
        event(b4, BugStatus.OPEN, BugStatus.CLOSED, "2026-10-25T10:00:00Z");
        stubRepositories();

        ReportResponse r = service.report(4, null);

        assertThat(r.from()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(r.to()).isEqualTo(LocalDate.of(2026, 11, 25));
        assertThat(r.weekly()).containsExactly(
                new WeekStat(LocalDate.of(2026, 11, 2), 1, 0, 1),   // b1 reported; b1 open at week end
                new WeekStat(LocalDate.of(2026, 11, 9), 1, 1, 1),   // b2 reported, b1 closed
                new WeekStat(LocalDate.of(2026, 11, 16), 0, 0, 1),
                new WeekStat(LocalDate.of(2026, 11, 23), 1, 0, 2)); // b3 reported; backlog now b2 + b3
        assertThat(r.totalBugs()).isEqualTo(4);
        assertThat(r.openBugs()).isEqualTo(2);
        assertThat(r.closedBugs()).isEqualTo(2);
        assertThat(r.openedInPeriod()).isEqualTo(3);
        assertThat(r.closedInPeriod()).isEqualTo(1);
        assertThat(r.timeToFix().count()).isEqualTo(1);
        assertThat(r.timeToFix().averageDays()).isEqualTo(2.0);
        assertThat(r.timeToClose().count()).isEqualTo(1);         // b4 was closed before the period
        assertThat(r.timeToClose().averageDays()).isEqualTo(7.0);
        assertThat(r.byProject()).containsExactly(new GroupStat("Web App", 4, 2, 2, 6.0)); // (7 + 5) / 2
        assertThat(r.byAssignee()).containsExactly(
                new GroupStat("Dev", 1, 0, 1, 7.0),
                new GroupStat("Unassigned", 3, 2, 1, 5.0));
    }

    @Test
    void reopenedBugCountsAsOpenAgain() {
        Bug b = bug(1, "2026-11-03T10:00:00Z", BugStatus.OPEN, null);
        event(b, null, BugStatus.OPEN, "2026-11-03T10:00:00Z");
        event(b, BugStatus.OPEN, BugStatus.CLOSED, "2026-11-04T10:00:00Z");
        event(b, BugStatus.CLOSED, BugStatus.OPEN, "2026-11-17T10:00:00Z");
        stubRepositories();

        ReportResponse r = service.report(4, null);

        assertThat(r.weekly()).extracting(WeekStat::openAtEnd).containsExactly(0L, 0L, 1L, 1L);
        assertThat(r.weekly()).extracting(WeekStat::closed).containsExactly(1L, 0L, 0L, 0L);
        assertThat(r.timeToClose().count()).isZero(); // not closed now
    }

    @Test
    void emptyReportHasNoAverages() {
        stubRepositories();
        ReportResponse r = service.report(12, null);
        assertThat(r.weekly()).hasSize(12).allMatch(w -> w.opened() == 0 && w.closed() == 0 && w.openAtEnd() == 0);
        assertThat(r.timeToFix().averageDays()).isNull();
        assertThat(r.timeToClose().medianDays()).isNull();
    }

    @Test
    void periodMustBeFourToFiftyTwoWeeks() {
        assertThatThrownBy(() -> service.report(3, null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.report(53, null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void niceAxisSteps() {
        assertThat(ReportPdfService.niceStep(0)).isEqualTo(1);
        assertThat(ReportPdfService.niceStep(4)).isEqualTo(1);
        assertThat(ReportPdfService.niceStep(7)).isEqualTo(2);
        assertThat(ReportPdfService.niceStep(18)).isEqualTo(5);
        assertThat(ReportPdfService.niceStep(39)).isEqualTo(10);
        assertThat(ReportPdfService.niceStep(130)).isEqualTo(50);
    }

    private void stubRepositories() {
        when(currentUser.get()).thenReturn(admin);
        when(bugs.findAll(org.mockito.ArgumentMatchers.<Specification<Bug>>any(), any(Sort.class))).thenReturn(allBugs);
        if (!allBugs.isEmpty()) {
            when(history.findByBugIdInOrderByIdAsc(any())).thenReturn(allEvents);
        }
    }

    private Bug bug(long id, String created, BugStatus status, User assignee) {
        Bug b = new Bug();
        ReflectionTestUtils.setField(b, "id", id);
        ReflectionTestUtils.setField(b, "createdAt", Instant.parse(created));
        b.setTitle("Bug " + id);
        b.setStatus(status);
        b.setProject(project);
        b.setAssignedTo(assignee);
        allBugs.add(b);
        return b;
    }

    private void event(Bug b, BugStatus from, BugStatus to, String at) {
        BugHistory h = new BugHistory(b, admin, from, to);
        ReflectionTestUtils.setField(h, "id", nextEventId++);
        ReflectionTestUtils.setField(h, "changedAt", Instant.parse(at));
        allEvents.add(h);
    }

    private static User user(Long id, String name, Role role) {
        User u = new User();
        u.setId(id);
        u.setName(name);
        u.setRole(role);
        u.setEnabled(true);
        return u;
    }
}
