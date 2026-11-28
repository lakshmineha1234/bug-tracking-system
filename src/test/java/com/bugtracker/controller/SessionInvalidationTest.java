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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sessions end when credentials change: each test signs in for real (like separate browsers) and checks
 * which sessions still work afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SessionInvalidationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private User admin;

    @BeforeEach
    void setUp() {
        admin = users.save(newUser("Admin", "admin@session.test", Role.ADMIN, "admin-password-1"));
        users.save(newUser("Tess", "tess@session.test", Role.TESTER, "tess-password-1"));
    }

    @Test
    void changingYourPasswordSignsOutOtherSessionsButKeepsThisOne() throws Exception {
        MockHttpSession laptop = signIn("tess@session.test", "tess-password-1");
        MockHttpSession stolen = signIn("tess@session.test", "tess-password-1");

        mvc.perform(post("/api/auth/password").session(laptop).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"tess-password-1\",\"newPassword\":\"tess-password-2\"}"))
                .andExpect(status().isNoContent());

        me(laptop).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Tess"));
        me(stolen).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Your session has ended. Please sign in again."));
        // The session that changed the password keeps working for further changes too.
        mvc.perform(get("/api/projects").session(laptop)).andExpect(status().isOk());
    }

    @Test
    void adminResetSignsTheUserOutEverywhere() throws Exception {
        MockHttpSession tess = signIn("tess@session.test", "tess-password-1");
        User user = users.findByEmailIgnoreCase("tess@session.test").orElseThrow();

        mvc.perform(put("/api/users/" + user.getId() + "/password").with(as(admin)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"reset-password-3\"}"))
                .andExpect(status().isNoContent());

        me(tess).andExpect(status().isUnauthorized());
        me(signIn("tess@session.test", "reset-password-3")).andExpect(status().isOk());
    }

    @Test
    void roleChangeSignsTheUserOut() throws Exception {
        MockHttpSession tess = signIn("tess@session.test", "tess-password-1");
        updateUser("tess@session.test", "{\"role\":\"DEVELOPER\"}");

        me(tess).andExpect(status().isUnauthorized());
        me(signIn("tess@session.test", "tess-password-1")).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("DEVELOPER"));
    }

    @Test
    void sessionsDoNotComeBackWhenADisabledAccountIsEnabledAgain() throws Exception {
        MockHttpSession tess = signIn("tess@session.test", "tess-password-1");
        updateUser("tess@session.test", "{\"enabled\":false}");
        updateUser("tess@session.test", "{\"enabled\":true}");

        me(tess).andExpect(status().isUnauthorized());
        me(signIn("tess@session.test", "tess-password-1")).andExpect(status().isOk());
    }

    @Test
    void changesThatAlterNothingKeepSessions() throws Exception {
        MockHttpSession tess = signIn("tess@session.test", "tess-password-1");
        updateUser("tess@session.test", "{\"role\":\"TESTER\",\"enabled\":true}"); // same role, already enabled

        me(tess).andExpect(status().isOk());
    }

    private MockHttpSession signIn(String email, String password) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    private ResultActions me(MockHttpSession session) throws Exception {
        return mvc.perform(get("/api/auth/me").session(session));
    }

    private void updateUser(String email, String json) throws Exception {
        User user = users.findByEmailIgnoreCase(email).orElseThrow();
        mvc.perform(patch("/api/users/" + user.getId()).with(as(admin)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
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
