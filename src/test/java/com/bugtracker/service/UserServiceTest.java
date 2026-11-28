package com.bugtracker.service;

import com.bugtracker.dto.RegisterRequest;
import com.bugtracker.entity.Role;
import com.bugtracker.entity.User;
import com.bugtracker.exception.ConflictException;
import com.bugtracker.repository.UserRepository;
import com.bugtracker.security.CurrentUserService;
import com.bugtracker.security.LoginAttemptLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository users;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private CurrentUserService currentUser;
    @Mock private ApplicationEventPublisher events;
    @Mock private LoginAttemptLimiter limiter;

    @Test
    void simultaneousSignUpWithTheSameEmailIsAConflictNotAServerError() {
        // Both requests passed the "email already exists?" check; the database's unique constraint stops the second.
        when(users.existsByEmailIgnoreCase("eve@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed");
        when(users.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("uk_users_email"));
        UserService service = new UserService(users, passwordEncoder, currentUser, events, limiter, true);

        assertThatThrownBy(() -> service.register(new RegisterRequest("Eve", "eve@example.com", "password123", Role.TESTER)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("An account with this email already exists");
    }
}
