package com.bugtracker.service;

import com.bugtracker.dto.ProjectRequest;
import com.bugtracker.dto.ProjectResponse;
import com.bugtracker.dto.UserResponse;
import com.bugtracker.entity.AssignmentHistory;
import com.bugtracker.entity.Bug;
import com.bugtracker.entity.Project;
import com.bugtracker.entity.Role;
import com.bugtracker.entity.User;
import com.bugtracker.exception.ConflictException;
import com.bugtracker.exception.ForbiddenException;
import com.bugtracker.exception.NotFoundException;
import com.bugtracker.repository.AssignmentHistoryRepository;
import com.bugtracker.repository.BugRepository;
import com.bugtracker.repository.ProjectRepository;
import com.bugtracker.repository.UserRepository;
import com.bugtracker.security.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
@Transactional
public class ProjectService {

    private final ProjectRepository projects;
    private final UserRepository users;
    private final BugRepository bugs;
    private final AssignmentHistoryRepository assignmentHistory;
    private final CurrentUserService currentUser;

    public ProjectService(ProjectRepository projects, UserRepository users, BugRepository bugs,
                          AssignmentHistoryRepository assignmentHistory, CurrentUserService currentUser) {
        this.projects = projects;
        this.users = users;
        this.bugs = bugs;
        this.assignmentHistory = assignmentHistory;
        this.currentUser = currentUser;
    }

    /** Admins see every project; everyone else sees the projects they are a member of. */
    @Transactional(readOnly = true)
    public List<ProjectResponse> list() {
        User me = currentUser.get();
        List<Project> result = me.getRole() == Role.ADMIN
                ? projects.findAllByOrderByProjectNameAsc()
                : projects.findByMemberId(me.getId());
        return result.stream().map(ProjectResponse::from).toList();
    }

    public ProjectResponse create(ProjectRequest req) {
        User me = requireAdmin();
        String name = req.projectName().trim();
        if (projects.existsByProjectNameIgnoreCase(name)) {
            throw new ConflictException("A project named " + name + " already exists");
        }
        Project project = new Project();
        project.setProjectName(name);
        project.setDescription(req.description());
        project.setCreatedBy(me);
        project.addMember(me);
        return ProjectResponse.from(projects.save(project));
    }

    public ProjectResponse update(Long id, ProjectRequest req) {
        requireAdmin();
        Project project = find(id);
        String name = req.projectName().trim();
        if (!name.equalsIgnoreCase(project.getProjectName()) && projects.existsByProjectNameIgnoreCase(name)) {
            throw new ConflictException("A project named " + name + " already exists");
        }
        project.setProjectName(name);
        project.setDescription(req.description());
        return ProjectResponse.from(project);
    }

    @Transactional(readOnly = true)
    public List<UserResponse> members(Long id) {
        User me = currentUser.get();
        Project project = find(id);
        if (me.getRole() != Role.ADMIN && !project.isMember(me)) {
            throw new ForbiddenException("You are not a member of this project");
        }
        return project.getMembers().stream()
                .sorted(Comparator.comparing(User::getName, String.CASE_INSENSITIVE_ORDER))
                .map(UserResponse::from)
                .toList();
    }

    public void addMember(Long id, Long userId) {
        requireAdmin();
        Project project = find(id);
        User user = users.findById(userId).orElseThrow(() -> new NotFoundException("User " + userId + " not found"));
        project.addMember(user);
    }

    /**
     * Removes a member. Bugs in this project that are assigned to them become unassigned (and the change is
     * recorded), because developers see bugs through their assignment and must lose access with the membership.
     */
    public void removeMember(Long id, Long userId) {
        User admin = requireAdmin();
        Project project = find(id);
        project.removeMember(userId);
        for (Bug bug : bugs.findByProjectIdAndAssignedToId(id, userId)) {
            User previous = bug.getAssignedTo();
            bug.setAssignedTo(null);
            bug.touch();
            assignmentHistory.save(new AssignmentHistory(bug, admin, previous, null));
        }
    }

    private Project find(Long id) {
        return projects.findById(id).orElseThrow(() -> new NotFoundException("Project " + id + " not found"));
    }

    private User requireAdmin() {
        User me = currentUser.get();
        if (me.getRole() != Role.ADMIN) {
            throw new ForbiddenException("Only admins can manage projects");
        }
        return me;
    }
}
