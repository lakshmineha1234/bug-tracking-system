package com.bugtracker.controller;

import com.bugtracker.entity.Project;
import com.bugtracker.entity.Role;
import com.bugtracker.entity.User;
import com.bugtracker.repository.ProjectRepository;
import com.bugtracker.repository.UserRepository;
import com.bugtracker.security.AppUserDetails;
import com.bugtracker.entity.BugStatus;
import com.bugtracker.service.BugAssignedEvent;
import com.bugtracker.service.BugStatusChangedEvent;
import com.bugtracker.service.CommentAddedEvent;
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
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0));
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
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].title").value("Second"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void bugListIsPaged() throws Exception {
        for (int i = 1; i <= 5; i++) {
            createBug("Bug " + i, "LOW", "MINOR");
        }

        mvc.perform(get("/api/bugs").param("size", "2").with(as(tester)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].title").value("Bug 5")) // newest first by default
                .andExpect(jsonPath("$.content[1].title").value("Bug 4"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));

        mvc.perform(get("/api/bugs").param("size", "2").param("page", "2").with(as(tester)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].title").value("Bug 1"));
    }

    @Test
    void bugListSortsByImportanceNotAlphabetically() throws Exception {
        createBug("Low one", "LOW", "MINOR");
        createBug("Critical one", "CRITICAL", "CRITICAL");
        createBug("Medium one", "MEDIUM", "MAJOR");
        createBug("High one", "HIGH", "MAJOR");

        mvc.perform(get("/api/bugs").param("sort", "priority").param("dir", "asc").with(as(tester)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].priority").value("CRITICAL"))
                .andExpect(jsonPath("$.content[1].priority").value("HIGH"))
                .andExpect(jsonPath("$.content[2].priority").value("MEDIUM"))
                .andExpect(jsonPath("$.content[3].priority").value("LOW"));

        mvc.perform(get("/api/bugs").param("sort", "title").param("dir", "asc").with(as(tester)))
                .andExpect(jsonPath("$.content[0].title").value("Critical one"))
                .andExpect(jsonPath("$.content[3].title").value("Medium one"));
    }

    @Test
    void invalidPagingParametersAreRejected() throws Exception {
        mvc.perform(get("/api/bugs").param("size", "101").with(as(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("size")));
        mvc.perform(get("/api/bugs").param("page", "-1").with(as(admin)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/bugs").param("sort", "password").with(as(admin)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/bugs").param("dir", "sideways").with(as(admin)))
                .andExpect(status().isBadRequest());
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
    void assignmentChangesAreRecordedInHistory() throws Exception {
        project.addMember(otherDeveloper);
        long bugId = createBug("Broken export", "HIGH", "MAJOR");
        assign(bugId, developer.getId());
        assign(bugId, developer.getId());      // no change: not recorded
        assign(bugId, otherDeveloper.getId()); // reassigned
        mvc.perform(patch("/api/bugs/" + bugId + "/assignment").with(as(admin)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"assigneeId\":null}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/bugs/" + bugId + "/assignments").with(as(tester)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].oldAssignee").value(nullValue()))
                .andExpect(jsonPath("$[0].newAssignee.name").value("Dev One"))
                .andExpect(jsonPath("$[0].changedBy.name").value("Admin"))
                .andExpect(jsonPath("$[1].oldAssignee.name").value("Dev One"))
                .andExpect(jsonPath("$[1].newAssignee.name").value("Dev Two"))
                .andExpect(jsonPath("$[2].oldAssignee.name").value("Dev Two"))
                .andExpect(jsonPath("$[2].newAssignee").value(nullValue()));

        // Other test classes may have committed assignments too, so only count this bug's entries.
        mvc.perform(get("/api/dashboard/summary").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentAssignments[?(@.bugId == " + bugId + ")]", hasSize(3)));
    }

    @Test
    void assignmentHistoryRequiresAccessToTheBug() throws Exception {
        long bugId = createBug("Private bug", "LOW", "MINOR");
        mvc.perform(get("/api/bugs/" + bugId + "/assignments").with(as(otherDeveloper)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/bugs/999999/assignments").with(as(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void statusChangesAndCommentsTriggerNotifications() throws Exception {
        long bugId = createBug("Slow login", "HIGH", "MAJOR");
        assign(bugId, developer.getId());
        mvc.perform(patch("/api/bugs/" + bugId + "/status").with(as(developer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/bugs/" + bugId + "/comments").with(as(developer)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commentText\":\"Looking into it\"}"))
                .andExpect(status().isCreated());

        assertThat(applicationEvents.stream(BugStatusChangedEvent.class))
                .containsExactly(new BugStatusChangedEvent(bugId, BugStatus.OPEN, BugStatus.IN_PROGRESS, developer.getId(), developer.getId()));
        assertThat(applicationEvents.stream(CommentAddedEvent.class)).hasSize(1);
    }

    @Test
    void reportSummarisesTheProjectsBugs() throws Exception {
        long first = createBug("First", "HIGH", "MAJOR");
        createBug("Second", "LOW", "MINOR");
        createBug("Third", "MEDIUM", "MINOR");
        assign(first, developer.getId());
        for (String[] step : new String[][]{{"IN_PROGRESS", "dev"}, {"FIXED", "dev"}, {"CLOSED", "tester"}}) {
            mvc.perform(patch("/api/bugs/" + first + "/status").with(as(step[1].equals("dev") ? developer : tester)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + step[0] + "\"}"))
                    .andExpect(status().isOk());
        }

        mvc.perform(get("/api/reports").param("weeks", "4").param("projectId", project.getId().toString()).with(as(tester)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectName").value("Web App"))
                .andExpect(jsonPath("$.weekly", hasSize(4)))
                .andExpect(jsonPath("$.weekly[3].opened").value(3))   // this week
                .andExpect(jsonPath("$.weekly[3].closed").value(1))
                .andExpect(jsonPath("$.weekly[3].openAtEnd").value(2))
                .andExpect(jsonPath("$.totalBugs").value(3))
                .andExpect(jsonPath("$.openBugs").value(2))
                .andExpect(jsonPath("$.timeToFix.count").value(1))
                .andExpect(jsonPath("$.timeToClose.count").value(1))
                .andExpect(jsonPath("$.byProject[0].name").value("Web App"))
                .andExpect(jsonPath("$.byAssignee[?(@.name == 'Dev One')].closed").value(1));
    }

    @Test
    void reportPdfIsARealPdfWithTheReport() throws Exception {
        createBug("Printer jams", "HIGH", "MAJOR");
        byte[] pdf = mvc.perform(get("/api/reports/pdf").param("projectId", project.getId().toString()).with(as(tester)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", containsString("bugtrack-report-")))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        org.openpdf.text.pdf.PdfReader reader = new org.openpdf.text.pdf.PdfReader(pdf);
        String text = new org.openpdf.text.pdf.parser.PdfTextExtractor(reader).getTextFromPage(1);
        assertThat(text).contains("BugTrack report").contains("Project: Web App").contains("Opened in period");
    }

    @Test
    void reportRulesAreEnforced() throws Exception {
        mvc.perform(get("/api/reports").param("weeks", "3").with(as(tester))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/reports").param("weeks", "53").with(as(tester))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/reports").param("projectId", "999999").with(as(tester))).andExpect(status().isNotFound());
        mvc.perform(get("/api/reports").param("projectId", project.getId().toString()).with(as(otherDeveloper)))
                .andExpect(status().isForbidden()); // not a member of the project
        mvc.perform(get("/api/reports")).andExpect(status().isUnauthorized());
    }

    @Test
    void removingAMemberUnassignsTheirBugsAndEndsTheirAccess() throws Exception {
        long bugId = createBug("Login loop", "HIGH", "MAJOR");
        assign(bugId, developer.getId());
        mvc.perform(get("/api/bugs/" + bugId).with(as(developer))).andExpect(status().isOk());

        mvc.perform(delete("/api/projects/" + project.getId() + "/members/" + developer.getId()).with(as(admin)).with(csrf()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/bugs/" + bugId).with(as(developer))).andExpect(status().isForbidden());
        mvc.perform(get("/api/bugs/" + bugId).with(as(admin)))
                .andExpect(jsonPath("$.assignedTo").value(nullValue()));
        mvc.perform(get("/api/bugs/" + bugId + "/assignments").with(as(admin)))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].oldAssignee.name").value("Dev One"))
                .andExpect(jsonPath("$[1].newAssignee").value(nullValue()))
                .andExpect(jsonPath("$[1].changedBy.name").value("Admin"));
    }

    @Test
    void searchTreatsPercentAndUnderscoreLiterally() throws Exception {
        createBug("Checkout shows 50% discount twice", "LOW", "MINOR");
        createBug("500 error on save", "LOW", "MINOR");
        createBug("login_page crashes", "LOW", "MINOR");
        createBug("login-page slow", "LOW", "MINOR");

        mvc.perform(get("/api/bugs").param("q", "50%").with(as(tester)))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].title").value("Checkout shows 50% discount twice"));
        mvc.perform(get("/api/bugs").param("q", "login_page").with(as(tester)))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].title").value("login_page crashes"));
    }

    @Test
    void adminCannotResetTheirOwnPasswordWithoutTheCurrentOne() throws Exception {
        mvc.perform(put("/api/users/" + admin.getId() + "/password").with(as(admin)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"takeover-password\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Your account")));
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
