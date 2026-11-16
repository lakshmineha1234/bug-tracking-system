package com.bugtracker.service;

import com.bugtracker.entity.Bug;
import com.bugtracker.entity.BugStatus;
import com.bugtracker.entity.Project;
import com.bugtracker.entity.Role;
import com.bugtracker.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BugWorkflowTest {

    private User admin;
    private User tester;
    private User developer;
    private User otherDeveloper;
    private Bug bug;

    @BeforeEach
    void setUp() {
        admin = user(1L, Role.ADMIN);
        tester = user(2L, Role.TESTER);
        developer = user(3L, Role.DEVELOPER);
        otherDeveloper = user(4L, Role.DEVELOPER);

        Project project = new Project();
        project.addMember(tester);
        project.addMember(developer);
        project.addMember(otherDeveloper);

        bug = new Bug();
        bug.setProject(project);
        bug.setReportedBy(tester);
        bug.setAssignedTo(developer);
        bug.setStatus(BugStatus.OPEN);
    }

    @Test
    void assignedDeveloperCanStartWork() {
        assertThat(BugWorkflow.allowedTargets(developer, bug)).containsExactly(BugStatus.IN_PROGRESS);
    }

    @Test
    void otherDeveloperCannotChangeStatus() {
        assertThat(BugWorkflow.allowedTargets(otherDeveloper, bug)).isEmpty();
    }

    @Test
    void testerCannotStartWork() {
        assertThat(BugWorkflow.canTransition(tester, bug, BugStatus.IN_PROGRESS)).isFalse();
    }

    @Test
    void developerMarksFixedThenTesterClosesOrReturns() {
        bug.setStatus(BugStatus.IN_PROGRESS);
        assertThat(BugWorkflow.allowedTargets(developer, bug)).containsExactly(BugStatus.FIXED);

        bug.setStatus(BugStatus.FIXED);
        assertThat(BugWorkflow.allowedTargets(developer, bug)).isEmpty();
        assertThat(BugWorkflow.allowedTargets(tester, bug))
                .containsExactlyInAnyOrder(BugStatus.CLOSED, BugStatus.IN_PROGRESS);
    }

    @Test
    void testerCanReopenClosedBug() {
        bug.setStatus(BugStatus.CLOSED);
        assertThat(BugWorkflow.allowedTargets(tester, bug)).containsExactly(BugStatus.OPEN);
    }

    @Test
    void adminCanPerformAnyDefinedTransition() {
        bug.setStatus(BugStatus.FIXED);
        assertThat(BugWorkflow.allowedTargets(admin, bug))
                .containsExactlyInAnyOrder(BugStatus.CLOSED, BugStatus.IN_PROGRESS);
    }

    @Test
    void skippingStepsIsNotAValidTransition() {
        assertThat(BugWorkflow.isValidTransition(BugStatus.OPEN, BugStatus.CLOSED)).isFalse();
        assertThat(BugWorkflow.isValidTransition(BugStatus.OPEN, BugStatus.FIXED)).isFalse();
        assertThat(BugWorkflow.canTransition(admin, bug, BugStatus.CLOSED)).isFalse();
    }

    private static User user(Long id, Role role) {
        User u = new User();
        u.setId(id);
        u.setName(role.name().toLowerCase());
        u.setEmail(role.name().toLowerCase() + id + "@example.com");
        u.setRole(role);
        return u;
    }
}
