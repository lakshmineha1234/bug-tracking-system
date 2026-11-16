package com.bugtracker.controller;

import com.bugtracker.entity.Project;
import com.bugtracker.entity.Role;
import com.bugtracker.entity.User;
import com.bugtracker.repository.ProjectRepository;
import com.bugtracker.repository.UserRepository;
import com.bugtracker.security.AppUserDetails;
import com.bugtracker.service.BugAssignedEvent;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** API tests for the sample test cases TC-01 to TC-08 in the project documentation, plus key permission rules. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@RecordApplicationEvents
class BugApiIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ApplicationEvents applicationEvents;
    @Autowired
    private UserRepository users;
    @Autowired
    private ProjectRepository projects;

    private User admin;
    private User tester;
    private User developer;
    private User otherDeveloper;
    private Project project;

    @BeforeEach
    void setUp() {
        admin = users.save(newUser("Admin", "admin@test.com", Role.ADMIN));
        tester = users.save(newUser("Tess Tester", "tester@test.com", Role.TESTER));
        developer = users.save(newUser("Dev One", "dev1@test.com", Role.DEVELOPER));
        otherDeveloper = users.save(newUser("Dev Two", "dev2@test.com", Role.DEVELOPER));

        project = new Project();
        project.setProjectName("Web App");
        project.setCreatedBy(admin);
        project.addMember(admin);
        project.addMember(tester);
        project.addMember(developer);
        project = projects.save(project);
    }

    @Test
    void tc01_createBugWithValidDetails() throws Exception {
        mvc.perform(post("/api/bugs").with(as(tester)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(bugJson("Login fails", "HIGH", "MAJOR", project.getId())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/bugs/")))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.reportedBy.name").value("Tess Tester"))
                .andExpect(jsonPath("$.stepsToReproduce").value("1. Open page"));
    }

    @Test
    void tc02_blankTitleIsRejected() throws Exception {
        mvc.perform(post("/api/bugs").with(as(tester)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(bugJson("  ", "HIGH", "MAJOR", project.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.title").exists());
    }

    @Test
    void tc03_unknownProjectReturnsNotFound() throws Exception {
        mvc.perform(post("/api/bugs").with(as(tester)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(bugJson("Crash", "HIGH", "MAJOR", 999999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("Project 999999 not found")));
    }

    @Test
    void tc04_unknownBugReturnsNotFound() throws Exception {
        mvc.perform(get("/api/bugs/999999").with(as(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void tc05_allowedStatusChangeIsAppliedAndRecorded() throws Exception {
        long bugId = createBug("Crash on save", "CRITICAL", "CRITICAL");
        assign(bugId, developer.getId());

        mvc.perform(patch("/api/bugs/" + bugId + "/status").with(as(developer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.allowedTransitions[0]").value("FIXED"));

        mvc.perform(get("/api/bugs/" + bugId + "/history").with(as(tester)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].oldStatus").value("OPEN"))
                .andExpect(jsonPath("$[1].newStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$[1].changedBy.name").value("Dev One"));
    }

    @Test
    void tc05b_statusChangeNotAllowedForRoleIsForbidden() throws Exception {
        long bugId = createBug("Typo", "LOW", "MINOR");
        mvc.perform(patch("/api/bugs/" + bugId + "/status").with(as(tester)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/bugs/" + bugId + "/status").with(as(admin)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tc06_developerCannotAccessBugAssignedToSomeoneElse() throws Exception {
        long bugId = createBug("Slow page", "MEDIUM", "MINOR");
        assign(bugId, developer.getId());

        mvc.perform(get("/api/bugs/" + bugId).with(as(otherDeveloper)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/bugs").with(as(otherDeveloper)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void tc07_invalidPriorityIsRejected() throws Exception {
        mvc.perform(post("/api/bugs").with(as(tester)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(bugJson("Crash", "URGENT", "MAJOR", project.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("priority")));
    }

    @Test
    void tc08_searchByStatusReturnsOnlyMatchingBugs() throws Exception {
        long first = createBug("First", "LOW", "MINOR");
        createBug("Second", "LOW", "MINOR");
        mvc.perform(patch("/api/bugs/" + first + "/status").with(as(admin)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/bugs").param("status", "OPEN").with(as(tester)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("Second"));
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/bugs")).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyAdminsCanAssignAndOnlyToProjectDevelopers() throws Exception {
        long bugId = createBug("Broken link", "LOW", "MINOR");
        mvc.perform(patch("/api/bugs/" + bugId + "/assignment").with(as(tester)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"assigneeId\":" + developer.getId() + "}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/bugs/" + bugId + "/assignment").with(as(admin)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"assigneeId\":" + tester.getId() + "}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/bugs/" + bugId + "/assignment").with(as(admin)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"assigneeId\":" + otherDeveloper.getId() + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void assigningNotifiesTheDeveloperOnce() throws Exception {
        long bugId = createBug("Slow search", "MEDIUM", "MAJOR");
        assign(bugId, developer.getId());
        assign(bugId, developer.getId()); // saving the same assignment again sends nothing new

        assertThat(applicationEvents.stream(BugAssignedEvent.class))
                .containsExactly(new BugAssignedEvent(bugId, developer.getId(), admin.getId()));
    }

    @Test
    void developersCannotReportBugs() throws Exception {
        mvc.perform(post("/api/bugs").with(as(developer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(bugJson("Crash", "HIGH", "MAJOR", project.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCannotBeSelfRegistered() throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Eve\",\"email\":\"eve@test.com\",\"password\":\"password123\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
    }

    private long createBug(String title, String priority, String severity) throws Exception {
        String body = mvc.perform(post("/api/bugs").with(as(tester)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(bugJson(title, priority, severity, project.getId())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private void assign(long bugId, Long developerId) throws Exception {
        mvc.perform(patch("/api/bugs/" + bugId + "/assignment").with(as(admin)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"assigneeId\":" + developerId + "}"))
                .andExpect(status().isOk());
    }

    private static String bugJson(String title, String priority, String severity, Long projectId) {
        return """
                {"title":"%s","description":"Something is wrong","stepsToReproduce":"1. Open page",
                 "expectedResult":"Works","actualResult":"Fails",
                 "priority":"%s","severity":"%s","projectId":%d}
                """.formatted(title, priority, severity, projectId);
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
