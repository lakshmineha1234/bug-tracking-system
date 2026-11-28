package com.bugtracker.config;

import com.bugtracker.entity.Role;
import com.bugtracker.entity.User;
import com.bugtracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Admins cannot self-register, so the first admin is created at startup from
 * BUGTRACK_ADMIN_EMAIL / BUGTRACK_ADMIN_PASSWORD when no admin exists yet.
 */
@Component
public class AdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;
    private final String name;

    public AdminInitializer(UserRepository users, PasswordEncoder passwordEncoder,
                            @Value("${app.admin.email:}") String email,
                            @Value("${app.admin.password:}") String password,
                            @Value("${app.admin.name:Administrator}") String name) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.password = password;
        this.name = name;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Only an admin who can actually sign in counts: if every admin is disabled, the configured one is restored.
        if (users.existsByRoleAndEnabledTrue(Role.ADMIN)) {
            return;
        }
        if (email.isBlank() || password.isBlank()) {
            log.warn("No enabled admin account exists. Set BUGTRACK_ADMIN_EMAIL and BUGTRACK_ADMIN_PASSWORD and restart to create one.");
            return;
        }
        if (password.length() < 8) {
            log.warn("BUGTRACK_ADMIN_PASSWORD must be at least 8 characters. Admin account was not created.");
            return;
        }
        User existing = users.findByEmailIgnoreCase(email.trim()).orElse(null);
        User admin = existing != null ? existing : new User();
        admin.setName(admin.getName() == null ? name : admin.getName());
        admin.setEmail(email.trim().toLowerCase());
        admin.setPassword(passwordEncoder.encode(password));
        admin.setRole(Role.ADMIN);
        admin.setEnabled(true);
        if (existing != null) {
            admin.invalidateSessions(); // the password was replaced, so any old sessions end
        }
        users.save(admin);
        log.info("{} admin account {}", existing == null ? "Created" : "Restored", admin.getEmail());
    }
}
