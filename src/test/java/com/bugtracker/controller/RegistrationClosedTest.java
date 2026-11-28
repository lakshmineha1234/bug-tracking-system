package com.bugtracker.controller;

import com.bugtracker.entity.Role;
import com.bugtracker.entity.User;
import com.bugtracker.repository.UserRepository;
import com.bugtracker.security.AppUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Invitation-only mode (public sign-up switched off), admin-created accounts, and the health check. */
@SpringBootTest(properties = {"app.registration.enabled=false", "spring.servlet.multipart.max-file-size=10MB"})
@AutoConfigureMockMvc
@Transactional
class RegistrationClosedTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;

    private User admin;
    private User tester;

    @BeforeEach
    void setUp() {
        admin = users.save(newUser("Admin", "admin@closed.test", Role.ADMIN));
        tester = users.save(newUser("Tess", "tester@closed.test", Role.TESTER));
    }

    @Test
    void signUpIsClosed() throws Exception {
        mvc.perform(get("/api/auth/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationEnabled").value(false))
                .andExpect(jsonPath("$.maxUploadBytes").value(10 * 1024 * 1024))
                .andExpect(jsonPath("$.maxUploadLabel").value("10 MB"));
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Eve", "eve@closed.test", "password123", "TESTER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Sign-up is closed. Ask an admin to create an account for you."));
    }

    @Test
    void adminCreatesAnAccountThatCanSignIn() throws Exception {
        mvc.perform(post("/api/users").with(as(admin)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("New Dev", "New.Dev@Closed.test", "temporary123", "DEVELOPER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("new.dev@closed.test"))
                .andExpect(jsonPath("$.role").value("DEVELOPER"));

        // The password is stored hashed and works for a real sign-in.
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new.dev@closed.test\",\"password\":\"temporary123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Dev"));
    }

    @Test
    void adminCanCreateAnotherAdmin() throws Exception {
        mvc.perform(post("/api/users").with(as(admin)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Second Admin", "admin2@closed.test", "temporary123", "ADMIN")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void onlyAdminsCanCreateAccounts() throws Exception {
        mvc.perform(post("/api/users").with(as(tester)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Sneaky", "sneaky@closed.test", "password123", "ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void duplicateOrInvalidAccountsAreRejected() throws Exception {
        mvc.perform(post("/api/users").with(as(admin)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Again", "TESTER@closed.test", "password123", "TESTER")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/users").with(as(admin)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Short", "short@closed.test", "short", "TESTER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    void healthCheckIsPublicAndRevealsNoDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized()); // nothing else is exposed
    }

    private static String json(String name, String email, String password, String role) {
        return "{\"name\":\"%s\",\"email\":\"%s\",\"password\":\"%s\",\"role\":\"%s\"}".formatted(name, email, password, role);
    }

    private static RequestPostProcessor as(User user) {
        return SecurityMockMvcRequestPostProcessors.user(new AppUserDetails(user));
    }

    private static User newUser(String name, String email, Role role) {
        User u = new User();
        u.setName(name);
        u.setEmail(email);
        u.setPassword("not-used-in-tests");
        u.setRole(role);
        u.setEnabled(true);
        return u;
    }
}
