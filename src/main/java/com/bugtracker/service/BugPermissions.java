package com.bugtracker.service;

import com.bugtracker.entity.Bug;
import com.bugtracker.entity.Role;
import com.bugtracker.entity.User;

/**
 * Who may see and change a bug.
 * <ul>
 *   <li>Admin: every bug.</li>
 *   <li>Tester: bugs in projects they are a member of.</li>
 *   <li>Developer: bugs assigned to them.</li>
 * </ul>
 */
public final class BugPermissions {

    private BugPermissions() {
    }

    public static boolean canView(User user, Bug bug) {
        return switch (user.getRole()) {
            case ADMIN -> true;
            case TESTER -> bug.getProject().isMember(user);
            case DEVELOPER -> isAssignee(user, bug);
        };
    }

    /** Admins, and the tester who reported the bug, may edit its details. */
    public static boolean canEdit(User user, Bug bug) {
        if (user.getRole() == Role.ADMIN) {
            return true;
        }
        return bug.getReportedBy().getId().equals(user.getId()) && canView(user, bug);
    }

    public static boolean canAssign(User user) {
        return user.getRole() == Role.ADMIN;
    }

    public static boolean isAssignee(User user, Bug bug) {
        return bug.getAssignedTo() != null && bug.getAssignedTo().getId().equals(user.getId());
    }
}
