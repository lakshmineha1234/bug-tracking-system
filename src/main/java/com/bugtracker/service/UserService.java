package com.bugtracker.service;

import com.bugtracker.dto.ChangePasswordRequest;
import com.bugtracker.dto.RegisterRequest;
import com.bugtracker.dto.ResetPasswordRequest;
import com.bugtracker.dto.UpdateUserRequest;
import com.bugtracker.dto.UserResponse;
import com.bugtracker.entity.Role;
import com.bugtracker.entity.User;
import com.bugtracker.exception.BadRequestException;
import com.bugtracker.exception.ConflictException;
import com.bugtracker.exception.ForbiddenException;
import com.bugtracker.exception.NotFoundException;
import com.bugtracker.repository.UserRepository;
import com.bugtracker.security.CurrentUserService;
import com.bugtracker.security.LoginAttemptLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@Transactional
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserService currentUser;
    private final ApplicationEventPublisher events;
    private final LoginAttemptLimiter loginLimiter;
    private final boolean registrationEnabled;

    public UserService(UserRepository users, PasswordEncoder passwordEncoder, CurrentUserService currentUser,
                       ApplicationEventPublisher events, LoginAttemptLimiter loginLimiter,
                       @Value("${app.registration.enabled:true}") boolean registrationEnabled) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.currentUser = currentUser;
        this.events = events;
        this.loginLimiter = loginLimiter;
        this.registrationEnabled = registrationEnabled;
    }

    public boolean isRegistrationEnabled() {
        return registrationEnabled;
    }

    /**
     * The signed-in user changes their own password, confirming the current one first. All their sessions
     * are ended; returns the updated user so the caller can keep the current session signed in.
     */
    public User changeOwnPassword(ChangePasswordRequest req) {
        User me = currentUser.get();
        loginLimiter.checkPasswordChange(me.getId());
        if (!passwordEncoder.matches(req.currentPassword(), me.getPassword())) {
            loginLimiter.passwordChangeFailed(me.getId());
            throw new BadRequestException("Your current password is incorrect");
        }
        if (passwordEncoder.matches(req.newPassword(), me.getPassword())) {
            throw new BadRequestException("The new password must be different from the current one");
        }
        loginLimiter.passwordChangeSucceeded(me.getId());
        me.setPassword(passwordEncoder.encode(req.newPassword()));
        me.invalidateSessions(); // signs the user out everywhere; the caller re-signs the current session
        events.publishEvent(new PasswordChangedEvent(me.getId(), me.getId()));
        return me;
    }

    /** An admin sets a new password for a user, for example one who forgot theirs. Ends all their sessions. */
    public void resetPassword(Long userId, ResetPasswordRequest req) {
        User admin = requireAdmin();
        User user = users.findById(userId).orElseThrow(() -> new NotFoundException("User " + userId + " not found"));
        user.setPassword(passwordEncoder.encode(req.newPassword()));
        user.invalidateSessions();
        events.publishEvent(new PasswordChangedEvent(user.getId(), admin.getId()));
    }

    /** Self-registration. Only TESTER and DEVELOPER may be chosen; admins change roles afterwards. */
    public UserResponse register(RegisterRequest req) {
        if (!registrationEnabled) {
            throw new ForbiddenException("Sign-up is closed. Ask an admin to create an account for you.");
        }
        if (req.role() == Role.ADMIN) {
            throw new BadRequestException("Admin accounts cannot be self-registered");
        }
        return UserResponse.from(createAccount(req));
    }

    /** An admin creates an account with any role, for example when sign-up is closed. */
    public UserResponse createByAdmin(RegisterRequest req) {
        requireAdmin();
        return UserResponse.from(createAccount(req));
    }

    private User createAccount(RegisterRequest req) {
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("An account with this email already exists");
        }
        User user = new User();
        user.setName(req.name().trim());
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(req.password()));
        user.setRole(req.role());
        user.setEnabled(true);
        return users.save(user);
    }

    @Transactional(readOnly = true)
    public UserResponse me() {
        return UserResponse.from(currentUser.get());
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        requireAdmin();
        return users.findAll(Sort.by("name")).stream().map(UserResponse::from).toList();
    }

    public UserResponse update(Long id, UpdateUserRequest req) {
        User me = requireAdmin();
        User user = users.findById(id).orElseThrow(() -> new NotFoundException("User " + id + " not found"));
        boolean demotingSelf = req.role() != null && req.role() != Role.ADMIN;
        boolean disablingSelf = Boolean.FALSE.equals(req.enabled());
        if (user.getId().equals(me.getId()) && (demotingSelf || disablingSelf)) {
            throw new BadRequestException("You cannot remove your own admin access");
        }
        if (req.role() != null) {
            if (req.role() != user.getRole()) {
                user.invalidateSessions(); // they sign in again with their new role
            }
            user.setRole(req.role());
        }
        if (req.enabled() != null) {
            if (user.isEnabled() && !req.enabled()) {
                user.invalidateSessions(); // old sessions must not come back if the account is enabled again
            }
            user.setEnabled(req.enabled());
        }
        return UserResponse.from(user);
    }

    private User requireAdmin() {
        User me = currentUser.get();
        if (me.getRole() != Role.ADMIN) {
            throw new ForbiddenException("Only admins can manage users");
        }
        return me;
    }
}
