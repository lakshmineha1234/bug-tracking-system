package com.bugtracker.controller;

import com.bugtracker.entity.Role;
import com.bugtracker.entity.User;
import com.bugtracker.repository.UserRepository;
import com.bugtracker.security.AppUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sign-in limits over real HTTP requests, with small limits: 3 failures per account, 5 per address,
 * 2 wrong current passwords. Each test uses its own accounts and network address, because the limiter
 * keeps its counts for the whole test run.
 */
@SpringBootTest(properties = {
        "app.security.login.max-failures-per-account=3",
        "app.security.login.max-failures-per-ip=5",
        "app.security.password-change.max-failures=2"})
@AutoConfigureMockMvc
@Transactional
class LoginLimitApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void accountIsLockedAfterRepeatedFailuresEvenForTheRightPassword() throws Exception {
        String email = account("right-password-1");
        String ip = freshAddress();
        for (int i = 0; i < 3; i++) {
            login(email, "wrong-password", ip).andExpect(status().isUnauthorized());
        }
        login(email, "right-password-1", ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.message").value(startsWith("Too many failed sign-in attempts. Try again in")));
        login(email, "right-password-1", freshAddress()).andExpect(status().isTooManyRequests()); // from anywhere
    }

    @Test
    void lockedAndUnknownAccountsLookTheSame() throws Exception {
        String ip = freshAddress();
        String unknown = "nobody-" + UUID.randomUUID() + "@limit.test";
        for (int i = 0; i < 3; i++) {
            login(unknown, "guess-" + i, ip).andExpect(status().isUnauthorized());
        }
        login(unknown, "guess-4", ip).andExpect(status().isTooManyRequests()); // no hint the account doesn't exist
    }

    @Test
    void successfulSignInResetsTheCount() throws Exception {
        String email = account("right-password-1");
        String ip = freshAddress();
        login(email, "wrong-password", ip).andExpect(status().isUnauthorized());
        login(email, "wrong-password", ip).andExpect(status().isUnauthorized());
        login(email, "right-password-1", ip).andExpect(status().isOk());
        login(email, "wrong-password", ip).andExpect(status().isUnauthorized());
        login(email, "wrong-password", ip).andExpect(status().isUnauthorized());
        login(email, "right-password-1", ip).andExpect(status().isOk()); // 2 + 2 failures, never 3 in a row
    }

    @Test
    void oneAddressTryingManyAccountsIsBlocked() throws Exception {
        String ip = freshAddress();
        for (int i = 0; i < 5; i++) {
            login(account("right-password-1"), "wrong-password", ip).andExpect(status().isUnauthorized());
        }
        String innocent = account("right-password-1");
        login(innocent, "right-password-1", ip).andExpect(status().isTooManyRequests());
        login(innocent, "right-password-1", freshAddress()).andExpect(status().isOk()); // the account itself is fine
    }

    @Test
    void wrongCurrentPasswordsAreLimitedWhenChangingPassword() throws Exception {
        String email = account("right-password-1");
        User user = users.findByEmailIgnoreCase(email).orElseThrow();
        for (int i = 0; i < 2; i++) {
            changePassword(user, "wrong-current", "new-password-2").andExpect(status().isBadRequest());
        }
        changePassword(user, "right-password-1", "new-password-2")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(startsWith("Too many wrong current passwords.")));
    }

    private ResultActions login(String email, String password, String ip) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf()).with(r -> { r.setRemoteAddr(ip); return r; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    private ResultActions changePassword(User user, String current, String next) throws Exception {
        return mvc.perform(post("/api/auth/password").with(SecurityMockMvcRequestPostProcessors.user(new AppUserDetails(user)))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next)));
    }

    private String account(String password) {
        User u = new User();
        u.setName("Limit Test");
        u.setEmail("user-" + UUID.randomUUID() + "@limit.test");
        u.setPassword(passwordEncoder.encode(password));
        u.setRole(Role.TESTER);
        u.setEnabled(true);
        return users.save(u).getEmail();
    }

    private static String freshAddress() {
        int n = (int) (Math.random() * 250) + 1;
        int m = (int) (Math.random() * 250) + 1;
        return "10.77." + n + "." + m;
    }
}
