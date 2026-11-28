package com.bugtracker.controller;

import com.bugtracker.entity.Role;
import com.bugtracker.entity.User;
import com.bugtracker.repository.UserRepository;
import com.bugtracker.security.AppUserDetails;
import com.bugtracker.service.PasswordChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Changing your own password and an admin resetting someone else's, checked with real sign-ins. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@RecordApplicationEvents
class PasswordApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private ApplicationEvents events;

    private User admin;
    private User tester;

    @BeforeEach
    void setUp() {
        admin = users.save(newUser("Admin", "admin@pw.test", Role.ADMIN, "admin-password-1"));
        tester = users.save(newUser("Tess", "tess@pw.test", Role.TESTER, "old-password-1"));
    }

    @Test
    void userChangesTheirOwnPassword() throws Exception {
        changeOwn(tester, "old-password-1", "new-password-2").andExpect(status().isNoContent());

        login("tess@pw.test", "old-password-1").andExpect(status().isUnauthorized());
        login("tess@pw.test", "new-password-2").andExpect(status().isOk());
        assertThat(events.stream(PasswordChangedEvent.class))
                .containsExactly(new PasswordChangedEvent(tester.getId(), tester.getId()));
    }

    @Test
    void wrongCurrentPasswordIsRefused() throws Exception {
        // 400, not 401: a wrong password here must not look like the session ended.
        changeOwn(tester, "not-my-password", "new-password-2")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Your current password is incorrect"));
        login("tess@pw.test", "old-password-1").andExpect(status().isOk());
        assertThat(events.stream(PasswordChangedEvent.class)).isEmpty();
    }

    @Test
    void newPasswordMustDifferAndBeLongEnough() throws Exception {
        changeOwn(tester, "old-password-1", "old-password-1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The new password must be different from the current one"));
        changeOwn(tester, "old-password-1", "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.newPassword").exists());
    }

    @Test
    void changingAPasswordRequiresSigningIn() throws Exception {
        mvc.perform(post("/api/auth/password").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(changeJson("x", "new-password-2")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminResetsSomeonesPassword() throws Exception {
        mvc.perform(put("/api/users/" + tester.getId() + "/password").with(as(admin)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"reset-password-3\"}"))
                .andExpect(status().isNoContent());

        login("tess@pw.test", "old-password-1").andExpect(status().isUnauthorized());
        login("tess@pw.test", "reset-password-3").andExpect(status().isOk());
        assertThat(events.stream(PasswordChangedEvent.class))
                .containsExactly(new PasswordChangedEvent(tester.getId(), admin.getId()));
    }

    @Test
    void onlyAdminsCanResetPasswords() throws Exception {
        mvc.perform(put("/api/users/" + admin.getId() + "/password").with(as(tester)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"takeover-password\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/users/999999/password").with(as(admin)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"whatever-password\"}"))
                .andExpect(status().isNotFound());
        login("admin@pw.test", "admin-password-1").andExpect(status().isOk());
    }

    private ResultActions changeOwn(User user, String current, String next) throws Exception {
        return mvc.perform(post("/api/auth/password").with(as(user)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(changeJson(current, next)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    private static String changeJson(String current, String next) {
        return "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next);
    }

    private static RequestPostProcessor as(User user) {
        return SecurityMockMvcRequestPostProcessors.user(new AppUserDetails(user));
    }

    private User newUser(String name, String email, Role role, String password) {
        User u = new User();
        u.setName(name);
        u.setEmail(email);
        u.setPassword(passwordEncoder.encode(password));
        u.setRole(role);
        u.setEnabled(true);
        return u;
    }
}
