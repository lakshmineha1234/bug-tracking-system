package com.bugtracker.service;

import com.bugtracker.entity.Bug;
import com.bugtracker.entity.BugStatus;
import com.bugtracker.entity.Priority;
import com.bugtracker.entity.Project;
import com.bugtracker.entity.Role;
import com.bugtracker.entity.Severity;
import com.bugtracker.entity.User;
import com.bugtracker.repository.BugRepository;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private BugRepository bugs;
    @Mock private UserRepository users;
    @Mock private ObjectProvider<JavaMailSender> mailSenderProvider;
    @Mock private JavaMailSender mailSender;

    private Bug bug;
    private User admin;
    private User developer;

    @BeforeEach
    void setUp() {
        admin = user(1L, "Ada Admin", "admin@example.com", Role.ADMIN);
        User tester = user(2L, "Tess Tester", "tester@example.com", Role.TESTER);
        developer = user(3L, "Dev Developer", "dev@example.com", Role.DEVELOPER);
        Project project = new Project();
        project.setProjectName("Web App");
        bug = new Bug();
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
    }

    @Test
    void deletedBugIsIgnored() {
        when(bugs.findById(8L)).thenReturn(Optional.empty());
        when(users.findById(3L)).thenReturn(Optional.of(developer));

        service(true).onBugAssigned(new BugAssignedEvent(8L, 3L, 1L));

        verify(mailSenderProvider, never()).getIfAvailable();
    }

    private NotificationService service(boolean enabled) {
        return new NotificationService(bugs, users, mailSenderProvider, enabled,
                "BugTrack <no-reply@example.com>", "https://bugs.example.com/");
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
