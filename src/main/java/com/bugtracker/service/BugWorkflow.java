package com.bugtracker.service;

import com.bugtracker.entity.Bug;
import com.bugtracker.entity.BugStatus;
import com.bugtracker.entity.Role;
import com.bugtracker.entity.User;

import java.util.List;

import static com.bugtracker.entity.BugStatus.CLOSED;
import static com.bugtracker.entity.BugStatus.FIXED;
import static com.bugtracker.entity.BugStatus.IN_PROGRESS;
import static com.bugtracker.entity.BugStatus.OPEN;

/**
 * The allowed status transitions and which role performs each one.
 * <pre>
 *   OPEN        -> IN_PROGRESS  developer (assignee) starts work
 *   IN_PROGRESS -> FIXED        developer (assignee) marks the fix ready for testing
 *   FIXED       -> CLOSED       tester verifies the fix
 *   FIXED       -> IN_PROGRESS  tester: fix failed retest, returned to the developer
 *   CLOSED      -> OPEN         tester reopens a closed bug
 * </pre>
 * An admin may perform any of these transitions.
 */
public final class BugWorkflow {

    private record Rule(BugStatus from, BugStatus to, Role actor) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule(OPEN, IN_PROGRESS, Role.DEVELOPER),
            new Rule(IN_PROGRESS, FIXED, Role.DEVELOPER),
            new Rule(FIXED, CLOSED, Role.TESTER),
            new Rule(FIXED, IN_PROGRESS, Role.TESTER),
            new Rule(CLOSED, OPEN, Role.TESTER));

    private BugWorkflow() {
    }

    /** Whether the transition exists at all, regardless of who performs it. */
    public static boolean isValidTransition(BugStatus from, BugStatus to) {
        return RULES.stream().anyMatch(r -> r.from() == from && r.to() == to);
    }

    /** Whether this user may move this bug to the target status. */
    public static boolean canTransition(User user, Bug bug, BugStatus to) {
        if (!BugPermissions.canView(user, bug)) {
            return false;
        }
        return RULES.stream().anyMatch(r -> r.from() == bug.getStatus() && r.to() == to
                && (user.getRole() == Role.ADMIN || r.actor() == user.getRole()));
    }

    public static List<BugStatus> allowedTargets(User user, Bug bug) {
        return RULES.stream()
                .filter(r -> r.from() == bug.getStatus())
                .map(Rule::to)
                .distinct()
                .filter(to -> canTransition(user, bug, to))
                .toList();
    }
}
