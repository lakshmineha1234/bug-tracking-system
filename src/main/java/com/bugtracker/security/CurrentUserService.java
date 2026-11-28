package com.bugtracker.security;

import com.bugtracker.entity.User;
import com.bugtracker.exception.ForbiddenException;
import com.bugtracker.exception.UnauthorizedException;
import com.bugtracker.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the acting user from the authenticated session, never from request data.
 * The user is re-read from the database on every call, so role changes and disabling take effect immediately,
 * and a session that signed in before the user's credentials version last changed (password change or reset,
 * role change, disabling) is refused, which signs that user out everywhere else.
 */
@Component
public class CurrentUserService {

    private final UserRepository users;

    public CurrentUserService(UserRepository users) {
        this.users = users;
    }

    public User get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserDetails details)) {
            throw new UnauthorizedException("Not logged in");
        }
        User user = users.findById(details.getId())
                .orElseThrow(() -> new UnauthorizedException("Account no longer exists"));
        if (!user.isEnabled()) {
            throw new ForbiddenException("This account has been disabled");
        }
        if (user.getCredentialsVersion() != details.getCredentialsVersion()) {
            throw new UnauthorizedException("Your session has ended. Please sign in again.");
        }
        return user;
    }
}
