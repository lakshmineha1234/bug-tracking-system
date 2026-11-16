package com.bugtracker.service;

import com.bugtracker.entity.Bug;
import com.bugtracker.entity.User;
import com.bugtracker.repository.BugRepository;
import com.bugtracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Locale;

/**
 * Sends email notifications. Emails go out only after the assignment has been committed, on a background
 * thread, and a mail failure is logged rather than reported to the user.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final BugRepository bugs;
    private final UserRepository users;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final boolean enabled;
    private final String from;
    private final String baseUrl;

    public NotificationService(BugRepository bugs, UserRepository users, ObjectProvider<JavaMailSender> mailSender,
                               @Value("${app.mail.enabled:false}") boolean enabled,
                               @Value("${app.mail.from:no-reply@bugtrack.local}") String from,
                               @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.bugs = bugs;
        this.users = users;
        this.mailSender = mailSender;
        this.enabled = enabled;
        this.from = from;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onBugAssigned(BugAssignedEvent event) {
        Bug bug = bugs.findById(event.bugId()).orElse(null);
        User assignee = users.findById(event.assigneeId()).orElse(null);
        if (bug == null || assignee == null) {
            return;
        }
        User assignedBy = event.assignedById() == null ? null : users.findById(event.assignedById()).orElse(null);
        send(assignmentEmail(bug, assignee, assignedBy));
    }

    SimpleMailMessage assignmentEmail(Bug bug, User assignee, User assignedBy) {
        String link = baseUrl + "/#bug-" + bug.getId();
        StringBuilder body = new StringBuilder()
                .append("Hi ").append(assignee.getName()).append(",\n\n")
                .append(assignedBy != null ? assignedBy.getName() : "An administrator")
                .append(" assigned bug #").append(bug.getId()).append(" to you.\n\n")
                .append("  ").append(bug.getTitle()).append("\n")
                .append("  Project:     ").append(bug.getProject().getProjectName()).append("\n")
                .append("  Priority:    ").append(label(bug.getPriority().name())).append("\n")
                .append("  Severity:    ").append(label(bug.getSeverity().name())).append("\n")
                .append("  Status:      ").append(label(bug.getStatus().name())).append("\n")
                .append("  Reported by: ").append(bug.getReportedBy().getName()).append("\n");
        appendSection(body, "Description", bug.getDescription());
        appendSection(body, "Steps to reproduce", bug.getStepsToReproduce());
        appendSection(body, "Expected result", bug.getExpectedResult());
        appendSection(body, "Actual result", bug.getActualResult());
        body.append("\nOpen the bug: ").append(link).append("\n\n")
                .append("-- \nYou received this email because a bug in BugTrack was assigned to you.\n");

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(assignee.getEmail());
        message.setSubject("[BugTrack] Bug #" + bug.getId() + " assigned to you: " + bug.getTitle());
        message.setText(body.toString());
        return message;
    }

    private void send(SimpleMailMessage message) {
        String to = String.join(", ", message.getTo());
        if (!enabled) {
            log.info("Email notifications are disabled (set MAIL_ENABLED=true to send). Would send to {}: {}\n{}",
                    to, message.getSubject(), message.getText());
            return;
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.warn("No mail server is configured; email to {} was not sent", to);
            return;
        }
        try {
            sender.send(message);
            log.info("Sent email to {}: {}", to, message.getSubject());
        } catch (MailException e) {
            log.warn("Could not send email to {}: {}", to, e.getMessage());
        }
    }

    private static void appendSection(StringBuilder body, String heading, String text) {
        if (text != null && !text.isBlank()) {
            body.append("\n").append(heading).append(":\n").append(text.strip()).append("\n");
        }
    }

    private static String label(String enumName) {
        String s = enumName.replace('_', ' ').toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        for (String word : s.split(" ")) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }
}
