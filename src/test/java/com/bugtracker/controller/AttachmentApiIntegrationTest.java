package com.bugtracker.controller;

import com.bugtracker.entity.Project;
import com.bugtracker.entity.Role;
import com.bugtracker.entity.User;
import com.bugtracker.repository.ProjectRepository;
import com.bugtracker.repository.UserRepository;
import com.bugtracker.security.AppUserDetails;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Attachment API tests. Not @Transactional: changes are committed, so the tests can check that files
 * really appear in and disappear from the attachments folder. Each test uses its own users and project.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AttachmentApiIntegrationTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    private static final byte[] PDF = "%PDF-1.7\n%test document".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    static Path attachmentsDir;

    @DynamicPropertySource
    static void attachmentsFolder(DynamicPropertyRegistry registry) {
        registry.add("app.attachments.dir", () -> attachmentsDir.toString());
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private ProjectRepository projects;

    private User admin;
    private User tester;
    private User developer;
    private User outsider;
    private long bugId;

    @BeforeEach
    void setUp() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        admin = users.save(newUser("Admin", "admin-" + run + "@test.com", Role.ADMIN));
        tester = users.save(newUser("Tess", "tester-" + run + "@test.com", Role.TESTER));
        developer = users.save(newUser("Dev", "dev-" + run + "@test.com", Role.DEVELOPER));
        outsider = users.save(newUser("Other Dev", "other-" + run + "@test.com", Role.DEVELOPER));

        Project project = new Project();
        project.setProjectName("Project " + run);
        project.setCreatedBy(admin);
        project.addMember(admin);
        project.addMember(tester);
        project.addMember(developer);
        projects.save(project);

        String body = mvc.perform(post("/api/bugs").with(as(tester)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Crash\",\"priority\":\"HIGH\",\"severity\":\"MAJOR\",\"projectId\":" + project.getId() + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        bugId = ((Number) JsonPath.read(body, "$.id")).longValue();
        mvc.perform(patch("/api/bugs/" + bugId + "/assignment").with(as(admin)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"assigneeId\":" + developer.getId() + "}"))
                .andExpect(status().isOk());
    }

    @Test
    void uploadListAndDownloadAnImage() throws Exception {
        long id = upload(tester, "screenshot.png", PNG);

        mvc.perform(get("/api/bugs/" + bugId + "/attachments").with(as(developer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].filename").value("screenshot.png"))
                .andExpect(jsonPath("$[0].contentType").value("image/png"))
                .andExpect(jsonPath("$[0].sizeBytes").value(PNG.length))
                .andExpect(jsonPath("$[0].image").value(true))
                .andExpect(jsonPath("$[0].uploadedBy.name").value("Tess"))
                .andExpect(jsonPath("$[0].canDelete").value(false)); // the developer did not upload it

        mvc.perform(get("/api/attachments/" + id).with(as(developer)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(PNG));

        mvc.perform(get("/api/attachments/" + id).param("inline", "true").with(as(developer)))
                .andExpect(header().string("Content-Disposition", containsString("inline")));
    }

    @Test
    void nonImagesAreAlwaysDownloadedNotShownInline() throws Exception {
        long id = upload(tester, "report.pdf", PDF);
        mvc.perform(get("/api/attachments/" + id).param("inline", "true").with(as(tester)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", containsString("attachment")));
    }

    @Test
    void disallowedOrDisguisedFilesAreRejected() throws Exception {
        long before = filesOnDisk();
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        uploadExpecting(tester, "page.html", html, 400);
        uploadExpecting(tester, "drawing.svg", "<svg/>".getBytes(StandardCharsets.UTF_8), 400);
        uploadExpecting(tester, "fake.png", html, 400);      // HTML renamed to .png
        uploadExpecting(tester, "fake.pdf", PNG, 400);       // PNG renamed to .pdf
        uploadExpecting(tester, "empty.png", new byte[0], 400);
        assertThat(filesOnDisk()).isEqualTo(before);         // nothing was stored
        mvc.perform(get("/api/bugs/" + bugId + "/attachments").with(as(tester)))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void foldersAreStrippedFromFilenames() throws Exception {
        upload(tester, "..\\..\\windows\\evil.png", PNG);
        mvc.perform(get("/api/bugs/" + bugId + "/attachments").with(as(tester)))
                .andExpect(jsonPath("$[0].filename").value("evil.png"));
    }

    @Test
    void usersWithoutAccessToTheBugAreRefused() throws Exception {
        long id = upload(tester, "screenshot.png", PNG);
        uploadExpecting(outsider, "x.png", PNG, 403);
        mvc.perform(get("/api/bugs/" + bugId + "/attachments").with(as(outsider))).andExpect(status().isForbidden());
        mvc.perform(get("/api/attachments/" + id).with(as(outsider))).andExpect(status().isForbidden());
        mvc.perform(get("/api/attachments/999999").with(as(admin))).andExpect(status().isNotFound());
    }

    @Test
    void onlyTheUploaderOrAnAdminCanDelete() throws Exception {
        long id = upload(tester, "screenshot.png", PNG);
        long before = filesOnDisk();

        mvc.perform(delete("/api/attachments/" + id).with(as(developer)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/attachments/" + id).with(as(admin)).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(filesOnDisk()).isEqualTo(before - 1);
        mvc.perform(get("/api/attachments/" + id).with(as(tester))).andExpect(status().isNotFound());
    }

    @Test
    void uploaderCanDeleteTheirOwnAttachment() throws Exception {
        long id = upload(developer, "fix.log", "stack trace".getBytes(StandardCharsets.UTF_8));
        mvc.perform(delete("/api/attachments/" + id).with(as(developer)).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void uploadWithoutAFileIsRejected() throws Exception {
        mvc.perform(multipart("/api/bugs/" + bugId + "/attachments").with(as(tester)).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Choose a file to upload"));
    }

    private long upload(User user, String filename, byte[] bytes) throws Exception {
        String body = mvc.perform(multipart("/api/bugs/" + bugId + "/attachments")
                        .file(new MockMultipartFile("file", filename, "application/octet-stream", bytes))
                        .with(as(user)).with(csrf()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private void uploadExpecting(User user, String filename, byte[] bytes, int expectedStatus) throws Exception {
        mvc.perform(multipart("/api/bugs/" + bugId + "/attachments")
                        .file(new MockMultipartFile("file", filename, "application/octet-stream", bytes))
                        .with(as(user)).with(csrf()))
                .andExpect(status().is(expectedStatus));
    }

    private static long filesOnDisk() throws Exception {
        if (!Files.isDirectory(attachmentsDir)) {
            return 0;
        }
        try (var files = Files.list(attachmentsDir)) {
            return files.count();
        }
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
