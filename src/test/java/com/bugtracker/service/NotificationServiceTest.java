package com.bugtracker.service;

import com.bugtracker.entity.Bug;
import com.bugtracker.entity.BugStatus;
import com.bugtracker.entity.Priority;
import com.bugtracker.entity.Project;
import com.bugtracker.entity.Role;
import com.bugtracker.entity.Severity;
import com.bugtracker.entity.User;
import com.bugtracker.entity.Comment;
import com.bugtracker.repository.BugRepository;
import com.bugtracker.repository.CommentRepository;
import com.bugtracker.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private BugRepository bugs;
    @Mock private UserRepository users;
    @Mock private CommentRepository comments;
    @Mock private ObjectProvider<JavaMailSender> mailSenderProvider;
    @Mock private JavaMailSender mailSender;

    private Bug bug;
    private User admin;
    private User tester;
    private User developer;

    @BeforeEach
    void setUp() {
        admin = user(1L, "Ada Admin", "admin@example.com", Role.ADMIN);
        tester = user(2L, "Tess Tester", "tester@example.com", Role.TESTER);
        developer = user(3L, "Dev Developer", "dev@example.com", Role.DEVELOPER);
        Project project = new Project();
        project.setProjectName("Web App");
        project.addMember(tester);
        project.addMember(developer);
        bug = new Bug();
        bug.setAssignedTo(developer);
        ReflectionTestUtils.setField(bug, "id", 8L);
        bug.setTitle("Search pagination skips page 2");
        bug.setStepsToReproduce("1. Search\n2. Click Next");
        bug.setPriority(Priority.MEDIUM);
        bug.setSeverity(Severity.MAJOR);
        bug.setStatus(BugStatus.IN_PROGRESS);
        bug.setProject(project);
        bug.setReportedBy(tester);
    }

    @Test
    void sendsAssignmentEmailToTheDeveloper() {
        when(bugs.findById(8L)).thenReturn(Optional.of(bug));
        when(users.findById(3L)).thenReturn(Optional.of(developer));
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);

        service(true).onBugAssigned(new BugAssignedEvent(8L, 3L, 1L));

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        SimpleMailMessage mail = sent.getValue();
        assertThat(mail.getTo()).containsExactly("dev@example.com");
        assertThat(mail.getFrom()).isEqualTo("BugTrack <no-reply@example.com>");
        assertThat(mail.getSubject()).isEqualTo("[BugTrack] Bug #8 assigned to you: Search pagination skips page 2");
        assertThat(mail.getText())
                .contains("Hi Dev Developer,")
                .contains("Ada Admin assigned bug #8 to you.")
                .contains("Project:     Web App")
                .contains("Priority:    Medium")
                .contains("Status:      In Progress")
                .contains("Steps to reproduce:\n1. Search\n2. Click Next")
                .contains("Open the bug: https://bugs.example.com/#bug-8");
    }

    @Test
    void doesNotSendWhenNotificationsAreDisabled() {
        when(bugs.findById(8L)).thenReturn(Optional.of(bug));
        when(users.findById(3L)).thenReturn(Optional.of(developer));
        when(users.findById(1L)).thenReturn(Optional.of(admin));

        service(false).onBugAssigned(new BugAssignedEvent(8L, 3L, 1L));

        verify(mailSenderProvider, never()).getIfAvailable();
    }

    @Test
    void mailServerFailureIsNotThrown() {
        when(bugs.findById(8L)).thenReturn(Optional.of(bug));
        when(users.findById(3L)).thenReturn(Optional.of(developer));
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
        doThrow(new MailSendException("connection refused")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatCode(() -> service(true).onBugAssigned(new BugAssignedEvent(8L, 3L, 1L)))
                .doesNotThrowAnyException();
        verify(mailSender, times(3)).send(any(SimpleMailMessage.class)); // first try + 2 retries
    }

    @Test
    void temporaryMailFailureIsRetried() {
        when(bugs.findById(8L)).thenReturn(Optional.of(bug));
        when(users.findById(3L)).thenReturn(Optional.of(developer));
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
        doThrow(new MailSendException("timeout")).doNothing().when(mailSender).send(any(SimpleMailMessage.class));

        service(true).onBugAssigned(new BugAssignedEvent(8L, 3L, 1L));

        verify(mailSender, times(2)).send(any(SimpleMailMessage.class)); // failed once, then delivered
    }

    @Test
    void deletedBugIsIgnored() {
        when(bugs.findById(8L)).thenReturn(Optional.empty());
        when(users.findById(3L)).thenReturn(Optional.of(developer));

        service(true).onBugAssigned(new BugAssignedEvent(8L, 3L, 1L));

        verify(mailSenderProvider, never()).getIfAvailable();
    }

    @Test
    void fixedBugAsksTheReporterToRetest() {
        List<SimpleMailMessage> mails = service(true).statusEmails(bug, BugStatus.IN_PROGRESS, BugStatus.FIXED, developer, bug.getAssignedTo());

        assertThat(mails).hasSize(1);
        assertThat(mails.get(0).getTo()).containsExactly("tester@example.com");
        assertThat(mails.get(0).getSubject())
                .isEqualTo("[BugTrack] Bug #8 is fixed and ready for testing: Search pagination skips page 2");
        assertThat(mails.get(0).getText())
                .contains("Hi Tess Tester,")
                .contains("Dev Developer marked bug #8 as fixed.")
                .contains("Open the bug: https://bugs.example.com/#bug-8")
                .contains("because you reported this bug");
    }

    @Test
    void returnedClosedAndReopenedBugsNotifyTheDeveloper() {
        NotificationService service = service(true);
        assertThat(service.statusEmails(bug, BugStatus.FIXED, BugStatus.IN_PROGRESS, tester, bug.getAssignedTo()))
                .singleElement().satisfies(m -> {
                    assertThat(m.getTo()).containsExactly("dev@example.com");
                    assertThat(m.getSubject()).startsWith("[BugTrack] Bug #8 was returned to you");
                });
        assertThat(service.statusEmails(bug, BugStatus.FIXED, BugStatus.CLOSED, tester, bug.getAssignedTo()))
                .singleElement().satisfies(m -> assertThat(m.getSubject()).startsWith("[BugTrack] Bug #8 was verified and closed"));
        assertThat(service.statusEmails(bug, BugStatus.CLOSED, BugStatus.OPEN, tester, bug.getAssignedTo()))
                .singleElement().satisfies(m -> assertThat(m.getSubject()).startsWith("[BugTrack] Bug #8 was reopened"));
    }

    @Test
    void statusEmailShowsTheStatusFromItsOwnChange() {
        bug.setStatus(BugStatus.CLOSED); // the bug has moved on by the time the queued email is written
        assertThat(service(true).statusEmails(bug, BugStatus.FIXED, BugStatus.IN_PROGRESS, tester, bug.getAssignedTo()))
                .singleElement()
                .satisfies(m -> assertThat(m.getText()).contains("Status:      In Progress").doesNotContain("Closed"));
    }

    @Test
    void statusEmailGoesOnlyToTheDeveloperAssignedAtTheTime() {
        User newDeveloper = user(4L, "New Dev", "new@example.com", Role.DEVELOPER);
        bug.getProject().addMember(newDeveloper);

        // Returned while Dev was assigned, then reassigned before the email went out: the new developer is not
        // told about a change that wasn't theirs, and Dev can no longer see the bug, so no details leak to them.
        bug.setAssignedTo(newDeveloper);
        assertThat(service(true).statusEmails(bug, BugStatus.FIXED, BugStatus.IN_PROGRESS, tester, developer)).isEmpty();

        // Still assigned to Dev when the email is written: Dev gets it.
        bug.setAssignedTo(developer);
        assertThat(service(true).statusEmails(bug, BugStatus.FIXED, BugStatus.IN_PROGRESS, tester, developer))
                .singleElement().satisfies(m -> assertThat(m.getTo()).containsExactly("dev@example.com"));
    }

    @Test
    void startingWorkSendsNothing() {
        assertThat(service(true).statusEmails(bug, BugStatus.OPEN, BugStatus.IN_PROGRESS, developer, bug.getAssignedTo())).isEmpty();
    }

    @Test
    void nobodyIsEmailedAboutTheirOwnAction() {
        bug.setReportedBy(admin);
        // The admin marks fixed a bug they reported themselves: no email to the admin.
        assertThat(service(true).statusEmails(bug, BugStatus.IN_PROGRESS, BugStatus.FIXED, admin, bug.getAssignedTo())).isEmpty();
    }

    @Test
    void disabledUsersAndUsersWhoCannotSeeTheBugAreSkipped() {
        tester.setEnabled(false);
        assertThat(service(true).statusEmails(bug, BugStatus.IN_PROGRESS, BugStatus.FIXED, developer, bug.getAssignedTo())).isEmpty();

        tester.setEnabled(true);
        bug.getProject().removeMember(tester.getId()); // the tester left the project
        assertThat(service(true).statusEmails(bug, BugStatus.IN_PROGRESS, BugStatus.FIXED, developer, bug.getAssignedTo())).isEmpty();
    }

    @Test
    void commentsGoToReporterAndAssigneeButNotTheAuthor() {
        Comment comment = new Comment(bug, developer, "Found the cause.\nFix is on the way.");
        List<SimpleMailMessage> mails = service(true).commentEmails(comment, bug.getAssignedTo());

        assertThat(mails).hasSize(1);
        SimpleMailMessage mail = mails.get(0);
        assertThat(mail.getTo()).containsExactly("tester@example.com");
        assertThat(mail.getSubject()).isEqualTo("[BugTrack] New comment on bug #8: Search pagination skips page 2");
        assertThat(mail.getText())
                .contains("Dev Developer commented on bug #8:")
                .contains("  Found the cause.\n  Fix is on the way.");

        Comment byAdmin = new Comment(bug, admin, "Any update?");
        assertThat(service(true).commentEmails(byAdmin, bug.getAssignedTo()))
                .extracting(m -> m.getTo()[0])
                .containsExactlyInAnyOrder("tester@example.com", "dev@example.com");
    }

    @Test
    void commentByReporterWhoIsAlsoAssigneeEmailsNobody() {
        bug.setReportedBy(developer); // same person reported and is assigned
        Comment comment = new Comment(bug, developer, "Note to self");
        assertThat(service(true).commentEmails(comment, bug.getAssignedTo())).isEmpty();
    }

    @Test
    void passwordChangedEmailForOwnChange() {
        SimpleMailMessage mail = service(true).passwordChangedEmail(tester, tester);
        assertThat(mail.getTo()).containsExactly("tester@example.com");
        assertThat(mail.getSubject()).isEqualTo("[BugTrack] Your password was changed");
        assertThat(mail.getText())
                .contains("You changed the password for your BugTrack account (tester@example.com).")
                .contains("If you did not expect this, contact a BugTrack administrator straight away.")
                .doesNotContain("old-password").doesNotContain("new-password");
    }

    @Test
    void passwordChangedEmailForAdminReset() {
        SimpleMailMessage mail = service(true).passwordChangedEmail(tester, admin);
        assertThat(mail.getText())
                .contains("An administrator set a new password for your BugTrack account (tester@example.com).")
                .contains("Changed by: Ada Admin");
    }

    @Test
    void disabledUsersGetNoPasswordEmail() {
        tester.setEnabled(false);
        when(users.findById(2L)).thenReturn(Optional.of(tester));
        service(true).onPasswordChanged(new PasswordChangedEvent(2L, 1L));
        verify(mailSenderProvider, never()).getIfAvailable();
    }

    @Test
    void statusChangeEventSendsTheEmail() {
        when(bugs.findById(8L)).thenReturn(Optional.of(bug));
        when(users.findById(3L)).thenReturn(Optional.of(developer));
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);

        service(true).onStatusChanged(new BugStatusChangedEvent(8L, BugStatus.IN_PROGRESS, BugStatus.FIXED, 3L, 3L));

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("tester@example.com");
    }

    private NotificationService service(boolean enabled) {
        NotificationService service = new NotificationService(bugs, users, comments, mailSenderProvider, enabled,
                "BugTrack <no-reply@example.com>", "https://bugs.example.com/");
        service.retryDelaysMs = new long[]{0, 0}; // retry immediately in tests
        return service;
    }

    private static User user(Long id, String name, String email, Role role) {
        User u = new User();
        u.setId(id);
        u.setName(name);
        u.setEmail(email);
        u.setRole(role);
        return u;
    }
}
