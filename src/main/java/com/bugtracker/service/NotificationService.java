package com.bugtracker.service;

import com.bugtracker.config.AsyncConfig;
import com.bugtracker.entity.Bug;
import com.bugtracker.entity.BugStatus;
import com.bugtracker.entity.Comment;
import com.bugtracker.entity.User;
import com.bugtracker.repository.BugRepository;
import com.bugtracker.repository.CommentRepository;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Sends email notifications about bugs. Emails go out only after the change has been committed, on a
 * background thread, and a mail failure is logged rather than reported to the user.
 * <p>
 * Recipients never include the person who made the change, disabled accounts, or anyone who can no
 * longer see the bug, and nobody receives the same email twice.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final BugRepository bugs;
    private final UserRepository users;
    private final CommentRepository comments;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final boolean enabled;
    private final String from;
    private final String baseUrl;

    /** Waits before each retry of a failed send; after the last one the email is given up and logged. */
    long[] retryDelaysMs = {2_000, 5_000};

    public NotificationService(BugRepository bugs, UserRepository users, CommentRepository comments,
                               ObjectProvider<JavaMailSender> mailSender,
                               @Value("${app.mail.enabled:false}") boolean enabled,
                               @Value("${app.mail.from:no-reply@bugtrack.local}") String from,
                               @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.bugs = bugs;
        this.users = users;
        this.comments = comments;
        this.mailSender = mailSender;
        this.enabled = enabled;
        this.from = from;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** The assigned developer is told about the bug, with its full details. */
    @Async(AsyncConfig.MAIL_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onBugAssigned(BugAssignedEvent event) {
        Bug bug = bugs.findById(event.bugId()).orElse(null);
        User assignee = users.findById(event.assigneeId()).orElse(null);
        if (bug == null || assignee == null) {
            return;
        }
        User assignedBy = event.assignedById() == null ? null : users.findById(event.assignedById()).orElse(null);
        for (User recipient : recipients(bug, assignedBy, assignee)) {
            send(assignmentEmail(bug, recipient, assignedBy));
        }
    }

    /**
     * Fixed: the reporter is asked to retest. Returned, closed or reopened: the assigned developer is told.
     * Starting work sends nothing.
     */
    @Async(AsyncConfig.MAIL_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onStatusChanged(BugStatusChangedEvent event) {
        Bug bug = bugs.findById(event.bugId()).orElse(null);
        if (bug == null) {
            return;
        }
        User actor = findUser(event.changedById());
        for (SimpleMailMessage message : statusEmails(bug, event.from(), event.to(), actor, findUser(event.assigneeId()))) {
            send(message);
        }
    }

    /** The reporter and the assigned developer receive the comment. */
    @Async(AsyncConfig.MAIL_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onCommentAdded(CommentAddedEvent event) {
        Comment comment = comments.findById(event.commentId()).orElse(null);
        if (comment == null) {
            return;
        }
        for (SimpleMailMessage message : commentEmails(comment, findUser(event.assigneeId()))) {
            send(message);
        }
    }

    /** The account owner is told their password changed, so a change they did not make is noticed. */
    @Async(AsyncConfig.MAIL_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onPasswordChanged(PasswordChangedEvent event) {
        User user = users.findById(event.userId()).orElse(null);
        if (user == null || !user.isEnabled()) {
            return;
        }
        User changedBy = event.changedById() == null ? null : users.findById(event.changedById()).orElse(null);
        send(passwordChangedEmail(user, changedBy));
    }

    SimpleMailMessage passwordChangedEmail(User user, User changedBy) {
        boolean byThemself = changedBy != null && changedBy.getId().equals(user.getId());
        String how = byThemself
                ? "You changed the password for your BugTrack account (" + user.getEmail() + ")."
                : "An administrator set a new password for your BugTrack account (" + user.getEmail()
                        + "). They will give it to you; please keep it private."
                        + (changedBy != null ? "\nChanged by: " + changedBy.getName() : "");
        String body = "Hi " + user.getName() + ",\n\n" + how + "\n\n"
                + "If you did not expect this, contact a BugTrack administrator straight away.\n\n"
                + "Sign in: " + baseUrl + "/\n\n"
                + "-- \nYou received this email because the password for your account changed.\n";
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(user.getEmail());
        message.setSubject("[BugTrack] Your password was changed");
        message.setText(body);
        return message;
    }

    SimpleMailMessage assignmentEmail(Bug bug, User assignee, User assignedBy) {
        return bugEmail(bug, assignee, bug.getStatus(),
                "Bug #" + bug.getId() + " assigned to you",
                name(assignedBy, "An administrator") + " assigned bug #" + bug.getId() + " to you.",
                true, "a bug in BugTrack was assigned to you");
    }

    /** assignee is the developer assigned when the status changed, not necessarily the one assigned now. */
    List<SimpleMailMessage> statusEmails(Bug bug, BugStatus from, BugStatus to, User actor, User assignee) {
        String who = name(actor, "Someone");
        String id = "#" + bug.getId();
        String reasonAssignee = "you are assigned to this bug";
        if (to == BugStatus.FIXED) {
            return recipients(bug, actor, bug.getReportedBy()).stream().map(r -> bugEmail(bug, r, to,
                    "Bug " + id + " is fixed and ready for testing",
                    who + " marked bug " + id + " as fixed. Please retest it, then close it, or return it to the "
                            + "developer if the problem is still there.",
                    false, "you reported this bug")).toList();
        }
        if (from == BugStatus.FIXED && to == BugStatus.IN_PROGRESS) {
            return recipients(bug, actor, assignee).stream().map(r -> bugEmail(bug, r, to,
                    "Bug " + id + " was returned to you",
                    who + " retested bug " + id + " and returned it to you: the problem is still there.",
                    false, reasonAssignee)).toList();
        }
        if (to == BugStatus.CLOSED) {
            return recipients(bug, actor, assignee).stream().map(r -> bugEmail(bug, r, to,
                    "Bug " + id + " was verified and closed",
                    who + " verified the fix and closed bug " + id + ".",
                    false, reasonAssignee)).toList();
        }
        if (from == BugStatus.CLOSED && to == BugStatus.OPEN) {
            return recipients(bug, actor, assignee).stream().map(r -> bugEmail(bug, r, to,
                    "Bug " + id + " was reopened",
                    who + " reopened bug " + id + ".",
                    false, reasonAssignee)).toList();
        }
        return List.of();
    }

    /** assignee is the developer assigned when the comment was posted. */
    List<SimpleMailMessage> commentEmails(Comment comment, User assignee) {
        Bug bug = comment.getBug();
        User author = comment.getUser();
        String quoted = "  " + comment.getCommentText().strip().replace("\n", "\n  ");
        return recipients(bug, author, bug.getReportedBy(), assignee).stream().map(r -> bugEmail(bug, r, bug.getStatus(),
                "New comment on bug #" + bug.getId(),
                author.getName() + " commented on bug #" + bug.getId() + ":\n\n" + quoted,
                false, "you reported or are assigned to this bug")).toList();
    }

    /** The candidates minus the actor, missing or disabled users, people who can't see the bug, and duplicates. */
    private static List<User> recipients(Bug bug, User actor, User... candidates) {
        List<User> result = new ArrayList<>();
        for (User u : Arrays.stream(candidates).filter(Objects::nonNull).toList()) {
            boolean isActor = actor != null && u.getId().equals(actor.getId());
            boolean duplicate = result.stream().anyMatch(r -> r.getId().equals(u.getId()));
            if (!isActor && !duplicate && u.isEnabled() && BugPermissions.canView(u, bug)) {
                result.add(u);
            }
        }
        return result;
    }

    private SimpleMailMessage bugEmail(Bug bug, User recipient, BugStatus status, String subject, String intro,
                                       boolean fullDetails, String reason) {
        StringBuilder body = new StringBuilder()
                .append("Hi ").append(recipient.getName()).append(",\n\n")
                .append(intro).append("\n\n")
                .append("  ").append(bug.getTitle()).append("\n")
                .append("  Project:     ").append(bug.getProject().getProjectName()).append("\n")
                .append("  Priority:    ").append(label(bug.getPriority().name())).append("\n")
                .append("  Severity:    ").append(label(bug.getSeverity().name())).append("\n")
                .append("  Status:      ").append(label(status.name())).append("\n")
                .append("  Reported by: ").append(bug.getReportedBy().getName()).append("\n");
        if (bug.getAssignedTo() != null) {
            body.append("  Assigned to: ").append(bug.getAssignedTo().getName()).append("\n");
        }
        if (fullDetails) {
            appendSection(body, "Description", bug.getDescription());
            appendSection(body, "Steps to reproduce", bug.getStepsToReproduce());
            appendSection(body, "Expected result", bug.getExpectedResult());
            appendSection(body, "Actual result", bug.getActualResult());
        }
        body.append("\nOpen the bug: ").append(baseUrl).append("/#bug-").append(bug.getId()).append("\n\n")
                .append("-- \nYou received this email because ").append(reason).append(".\n");

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient.getEmail());
        message.setSubject("[BugTrack] " + subject + ": " + bug.getTitle());
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
        for (int attempt = 0; ; attempt++) {
            try {
                sender.send(message);
                log.info("Sent email to {}: {}", to, message.getSubject());
                return;
            } catch (MailException e) {
                if (attempt >= retryDelaysMs.length) {
                    log.warn("Could not send email to {} after {} attempts: {}", to, attempt + 1, e.getMessage());
                    return;
                }
                log.info("Sending email to {} failed ({}); retrying in {} ms", to, e.getMessage(), retryDelaysMs[attempt]);
                try {
                    Thread.sleep(retryDelaysMs[attempt]);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private User findUser(Long id) {
        return id == null ? null : users.findById(id).orElse(null);
    }

    private static String name(User user, String fallback) {
        return user != null ? user.getName() : fallback;
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
