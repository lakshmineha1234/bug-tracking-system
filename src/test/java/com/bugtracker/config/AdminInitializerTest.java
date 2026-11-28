package com.bugtracker.config;

import com.bugtracker.entity.Role;
import com.bugtracker.entity.User;
import com.bugtracker.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminInitializerTest {

    @Mock private UserRepository users;
    @Mock private PasswordEncoder passwordEncoder;

    private AdminInitializer initializer() {
        return new AdminInitializer(users, passwordEncoder, "admin@example.com", "configured-password", "Administrator");
    }

    @Test
    void doesNothingWhileAnEnabledAdminExists() {
        when(users.existsByRoleAndEnabledTrue(Role.ADMIN)).thenReturn(true);
        initializer().run(null);
        verify(users, never()).save(any());
    }

    @Test
    void restoresTheConfiguredAdminWhenEveryAdminIsDisabled() {
        User disabledAdmin = new User();
        disabledAdmin.setId(1L);
        disabledAdmin.setName("Admin");
        disabledAdmin.setEmail("admin@example.com");
        disabledAdmin.setRole(Role.ADMIN);
        disabledAdmin.setEnabled(false);
        when(users.existsByRoleAndEnabledTrue(Role.ADMIN)).thenReturn(false);
        when(users.findByEmailIgnoreCase("admin@example.com")).thenReturn(Optional.of(disabledAdmin));
        when(passwordEncoder.encode("configured-password")).thenReturn("hashed");

        initializer().run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().isEnabled()).isTrue();
        assertThat(saved.getValue().getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getValue().getPassword()).isEqualTo("hashed");
        assertThat(saved.getValue().getCredentialsVersion()).isEqualTo(1); // old sessions end
    }

    @Test
    void createsTheConfiguredAdminWhenNoneExists() {
        when(users.existsByRoleAndEnabledTrue(Role.ADMIN)).thenReturn(false);
        when(users.findByEmailIgnoreCase("admin@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("configured-password")).thenReturn("hashed");

        initializer().run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("admin@example.com");
        assertThat(saved.getValue().getName()).isEqualTo("Administrator");
        assertThat(saved.getValue().isEnabled()).isTrue();
    }
}
