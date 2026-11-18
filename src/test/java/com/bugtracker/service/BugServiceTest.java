package com.bugtracker.service;

import com.bugtracker.dto.AssignmentRequest;
import com.bugtracker.dto.StatusChangeRequest;
import com.bugtracker.entity.Bug;
import com.bugtracker.entity.BugHistory;
import com.bugtracker.entity.BugStatus;
import com.bugtracker.entity.Priority;
import com.bugtracker.entity.Project;
import com.bugtracker.entity.Role;
import com.bugtracker.entity.Severity;
import com.bugtracker.entity.User;
import com.bugtracker.exception.BadRequestException;
import com.bugtracker.exception.ForbiddenException;
import com.bugtracker.exception.NotFoundException;
import com.bugtracker.entity.AssignmentHistory;
import com.bugtracker.repository.AssignmentHistoryRepository;
import com.bugtracker.repository.BugHistoryRepository;
import com.bugtracker.repository.BugRepository;
import com.bugtracker.repository.CommentRepository;
import com.bugtracker.repository.ProjectRepository;
import com.bugtracker.repository.UserRepository;
import com.bugtracker.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BugServiceTest {

    @Mock private BugRepository bugs;
    @Mock private ProjectRepository projects;
    @Mock private UserRepository users;
    @Mock private CommentRepository comments;
    @Mock private BugHistoryRepository history;
    @Mock private AssignmentHistoryRepository assignmentHistory;
    @Mock private CurrentUserService currentUser;
    @Mock private ApplicationEventPublisher events;
    @InjectMocks private BugService service;

    private User admin;
    private User tester;
    private User developer;
    private Bug bug;

    @BeforeEach
    void setUp() {
        admin = user(1L, Role.ADMIN);
        tester = user(2L, Role.TESTER);
        developer = user(3L, Role.DEVELOPER);
        Project project = new Project();
        project.setProjectName("Web App");
        project.addMember(tester);
        project.addMember(developer);
        bug = new Bug();
        bug.setTitle("Crash");
        bug.setPriority(Priority.HIGH);
        bug.setSeverity(Severity.MAJOR);
        bug.setProject(project);
        bug.setReportedBy(tester);
        bug.setStatus(BugStatus.OPEN);
    }

    @Test
    void statusChangeIsRecordedInHistory() {
        bug.setAssignedTo(developer);
        when(currentUser.get()).thenReturn(developer);
        when(bugs.findById(10L)).thenReturn(Optional.of(bug));

        service.changeStatus(10L, new StatusChangeRequest(BugStatus.IN_PROGRESS));

        assertThat(bug.getStatus()).isEqualTo(BugStatus.IN_PROGRESS);
        ArgumentCaptor<BugHistory> saved = ArgumentCaptor.forClass(BugHistory.class);
        verify(history).save(saved.capture());
        assertThat(saved.getValue().getOldStatus()).isEqualTo(BugStatus.OPEN);
        assertThat(saved.getValue().getNewStatus()).isEqualTo(BugStatus.IN_PROGRESS);
        assertThat(saved.getValue().getChangedBy()).isSameAs(developer);
    }

    @Test
    void developerCannotChangeUnassignedBug() {
        when(currentUser.get()).thenReturn(developer);
        when(bugs.findById(10L)).thenReturn(Optional.of(bug));

        assertThatThrownBy(() -> service.changeStatus(10L, new StatusChangeRequest(BugStatus.IN_PROGRESS)))
                .isInstanceOf(ForbiddenException.class);
        verify(history, never()).save(any());
    }

    @Test
    void invalidTransitionIsBadRequest() {
        when(currentUser.get()).thenReturn(admin);
        when(bugs.findById(10L)).thenReturn(Optional.of(bug));

        assertThatThrownBy(() -> service.changeStatus(10L, new StatusChangeRequest(BugStatus.CLOSED)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void assigningToTesterIsRejected() {
        when(currentUser.get()).thenReturn(admin);
        when(bugs.findById(10L)).thenReturn(Optional.of(bug));
        when(users.findById(2L)).thenReturn(Optional.of(tester));

        assertThatThrownBy(() -> service.assign(10L, new AssignmentRequest(2L)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("developers");
    }

    @Test
    void assigningToDeveloperInProjectSucceeds() {
        when(currentUser.get()).thenReturn(admin);
        when(bugs.findById(10L)).thenReturn(Optional.of(bug));
        when(users.findById(3L)).thenReturn(Optional.of(developer));

        service.assign(10L, new AssignmentRequest(3L));

        assertThat(bug.getAssignedTo()).isSameAs(developer);
        verify(events).publishEvent(new BugAssignedEvent(bug.getId(), 3L, 1L));
        AssignmentHistory recorded = savedAssignment();
        assertThat(recorded.getChangedBy()).isSameAs(admin);
        assertThat(recorded.getOldAssignee()).isNull();
        assertThat(recorded.getNewAssignee()).isSameAs(developer);
    }

    @Test
    void reassigningToSameDeveloperChangesNothing() {
        bug.setAssignedTo(developer);
        when(currentUser.get()).thenReturn(admin);
        when(bugs.findById(10L)).thenReturn(Optional.of(bug));
        when(users.findById(3L)).thenReturn(Optional.of(developer));

        service.assign(10L, new AssignmentRequest(3L));

        verify(events, never()).publishEvent(any());
        verify(assignmentHistory, never()).save(any());
    }

    @Test
    void reassigningToAnotherDeveloperRecordsBoth() {
        User otherDeveloper = user(4L, Role.DEVELOPER);
        bug.getProject().addMember(otherDeveloper);
        bug.setAssignedTo(developer);
        when(currentUser.get()).thenReturn(admin);
        when(bugs.findById(10L)).thenReturn(Optional.of(bug));
        when(users.findById(4L)).thenReturn(Optional.of(otherDeveloper));

        service.assign(10L, new AssignmentRequest(4L));

        AssignmentHistory recorded = savedAssignment();
        assertThat(recorded.getOldAssignee()).isSameAs(developer);
        assertThat(recorded.getNewAssignee()).isSameAs(otherDeveloper);
        verify(events).publishEvent(new BugAssignedEvent(bug.getId(), 4L, 1L));
    }

    @Test
    void unassigningIsRecordedButNotNotified() {
        bug.setAssignedTo(developer);
        when(currentUser.get()).thenReturn(admin);
        when(bugs.findById(10L)).thenReturn(Optional.of(bug));

        service.assign(10L, new AssignmentRequest(null));

        assertThat(bug.getAssignedTo()).isNull();
        verify(events, never()).publishEvent(any());
        AssignmentHistory recorded = savedAssignment();
        assertThat(recorded.getOldAssignee()).isSameAs(developer);
        assertThat(recorded.getNewAssignee()).isNull();
    }

    private AssignmentHistory savedAssignment() {
        ArgumentCaptor<AssignmentHistory> saved = ArgumentCaptor.forClass(AssignmentHistory.class);
        verify(assignmentHistory).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void missingBugIsNotFound() {
        when(currentUser.get()).thenReturn(admin);
        when(bugs.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(99L)).isInstanceOf(NotFoundException.class);
    }

    private static User user(Long id, Role role) {
        User u = new User();
        u.setId(id);
        u.setName(role.name());
        u.setRole(role);
        u.setEnabled(true);
        return u;
    }
}
