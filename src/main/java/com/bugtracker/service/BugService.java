package com.bugtracker.service;

import com.bugtracker.dto.AssignmentHistoryResponse;
import com.bugtracker.dto.AssignmentRequest;
import com.bugtracker.dto.BugFilter;
import com.bugtracker.dto.BugResponse;
import com.bugtracker.dto.CommentRequest;
import com.bugtracker.dto.CommentResponse;
import com.bugtracker.dto.CreateBugRequest;
import com.bugtracker.dto.HistoryResponse;
import com.bugtracker.dto.StatusChangeRequest;
import com.bugtracker.dto.UpdateBugRequest;
import com.bugtracker.entity.AssignmentHistory;
import com.bugtracker.entity.Bug;
import com.bugtracker.entity.BugHistory;
import com.bugtracker.entity.BugStatus;
import com.bugtracker.entity.Comment;
import com.bugtracker.entity.Project;
import com.bugtracker.entity.Role;
import com.bugtracker.entity.User;
import com.bugtracker.exception.BadRequestException;
import com.bugtracker.exception.ForbiddenException;
import com.bugtracker.exception.NotFoundException;
import com.bugtracker.repository.AssignmentHistoryRepository;
import com.bugtracker.repository.BugHistoryRepository;
import com.bugtracker.repository.BugRepository;
import com.bugtracker.repository.CommentRepository;
import com.bugtracker.repository.ProjectRepository;
import com.bugtracker.repository.UserRepository;
import com.bugtracker.security.CurrentUserService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class BugService {

    private final BugRepository bugs;
    private final ProjectRepository projects;
    private final UserRepository users;
    private final CommentRepository comments;
    private final BugHistoryRepository history;
    private final AssignmentHistoryRepository assignmentHistory;
    private final CurrentUserService currentUser;
    private final ApplicationEventPublisher events;

    public BugService(BugRepository bugs, ProjectRepository projects, UserRepository users,
                      CommentRepository comments, BugHistoryRepository history,
                      AssignmentHistoryRepository assignmentHistory, CurrentUserService currentUser,
                      ApplicationEventPublisher events) {
        this.bugs = bugs;
        this.projects = projects;
        this.users = users;
        this.comments = comments;
        this.history = history;
        this.assignmentHistory = assignmentHistory;
        this.currentUser = currentUser;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<BugResponse> list(BugFilter filter) {
        User me = currentUser.get();
        return bugs.findAll(BugSpecifications.visibleTo(me).and(BugSpecifications.matches(filter)),
                        Sort.by(Sort.Direction.DESC, "id"))
                .stream()
                .map(bug -> toResponse(bug, me))
                .toList();
    }

    @Transactional(readOnly = true)
    public BugResponse get(Long id) {
        User me = currentUser.get();
        return toResponse(findVisible(id, me), me);
    }

    public BugResponse create(CreateBugRequest req) {
        User me = currentUser.get();
        if (me.getRole() == Role.DEVELOPER) {
            throw new ForbiddenException("Only testers and admins can report bugs");
        }
        Project project = projects.findById(req.projectId())
                .orElseThrow(() -> new NotFoundException("Project " + req.projectId() + " not found"));
        if (me.getRole() == Role.TESTER && !project.isMember(me)) {
            throw new ForbiddenException("You are not a member of project " + project.getProjectName());
        }
        Bug bug = new Bug();
        bug.setTitle(req.title().trim());
        bug.setDescription(req.description());
        bug.setStepsToReproduce(req.stepsToReproduce());
        bug.setExpectedResult(req.expectedResult());
        bug.setActualResult(req.actualResult());
        bug.setPriority(req.priority());
        bug.setSeverity(req.severity());
        bug.setStatus(BugStatus.OPEN);
        bug.setProject(project);
        bug.setReportedBy(me);
        bugs.save(bug);
        history.save(new BugHistory(bug, me, null, BugStatus.OPEN));
        return toResponse(bug, me);
    }

    public BugResponse update(Long id, UpdateBugRequest req) {
        User me = currentUser.get();
        Bug bug = findVisible(id, me);
        if (!BugPermissions.canEdit(me, bug)) {
            throw new ForbiddenException("Only the reporter or an admin can edit this bug");
        }
        bug.setTitle(req.title().trim());
        bug.setDescription(req.description());
        bug.setStepsToReproduce(req.stepsToReproduce());
        bug.setExpectedResult(req.expectedResult());
        bug.setActualResult(req.actualResult());
        bug.setPriority(req.priority());
        bug.setSeverity(req.severity());
        bug.touch();
        return toResponse(bug, me);
    }

    public BugResponse changeStatus(Long id, StatusChangeRequest req) {
        User me = currentUser.get();
        Bug bug = findVisible(id, me);
        BugStatus from = bug.getStatus();
        BugStatus to = req.status();
        if (from == to) {
            throw new BadRequestException("Bug is already " + to);
        }
        if (!BugWorkflow.isValidTransition(from, to)) {
            throw new BadRequestException("Status cannot change from " + from + " to " + to);
        }
        if (!BugWorkflow.canTransition(me, bug, to)) {
            throw new ForbiddenException("Your role cannot change status from " + from + " to " + to);
        }
        bug.setStatus(to);
        bug.touch();
        history.save(new BugHistory(bug, me, from, to));
        return toResponse(bug, me);
    }

    public BugResponse assign(Long id, AssignmentRequest req) {
        User me = currentUser.get();
        if (!BugPermissions.canAssign(me)) {
            throw new ForbiddenException("Only admins can assign bugs");
        }
        Bug bug = bugs.findById(id).orElseThrow(() -> new NotFoundException("Bug " + id + " not found"));
        User previous = bug.getAssignedTo();
        User developer = null;
        if (req.assigneeId() != null) {
            developer = users.findById(req.assigneeId())
                    .orElseThrow(() -> new NotFoundException("User " + req.assigneeId() + " not found"));
            if (developer.getRole() != Role.DEVELOPER) {
                throw new BadRequestException("Bugs can only be assigned to developers");
            }
            if (!developer.isEnabled()) {
                throw new BadRequestException("This developer's account is disabled");
            }
            if (!bug.getProject().isMember(developer)) {
                throw new BadRequestException(developer.getName() + " is not a member of project "
                        + bug.getProject().getProjectName());
            }
        }
        boolean changed = previous == null
                ? developer != null
                : developer == null || !previous.getId().equals(developer.getId());
        if (changed) {
            bug.setAssignedTo(developer);
            bug.touch();
            assignmentHistory.save(new AssignmentHistory(bug, me, previous, developer));
            if (developer != null) {
                events.publishEvent(new BugAssignedEvent(bug.getId(), developer.getId(), me.getId()));
            }
        }
        return toResponse(bug, me);
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> comments(Long bugId) {
        findVisible(bugId, currentUser.get());
        return comments.findByBugIdOrderByIdAsc(bugId).stream().map(CommentResponse::from).toList();
    }

    public CommentResponse addComment(Long bugId, CommentRequest req) {
        User me = currentUser.get();
        Bug bug = findVisible(bugId, me);
        Comment comment = comments.save(new Comment(bug, me, req.commentText().trim()));
        return CommentResponse.from(comment);
    }

    @Transactional(readOnly = true)
    public List<HistoryResponse> history(Long bugId) {
        findVisible(bugId, currentUser.get());
        return history.findByBugIdOrderByIdAsc(bugId).stream().map(HistoryResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<AssignmentHistoryResponse> assignmentHistory(Long bugId) {
        findVisible(bugId, currentUser.get());
        return assignmentHistory.findByBugIdOrderByIdAsc(bugId).stream().map(AssignmentHistoryResponse::from).toList();
    }

    /** 404 if the bug does not exist, 403 if it exists but the user may not see it. */
    private Bug findVisible(Long id, User me) {
        Bug bug = bugs.findById(id).orElseThrow(() -> new NotFoundException("Bug " + id + " not found"));
        if (!BugPermissions.canView(me, bug)) {
            throw new ForbiddenException("You do not have access to bug " + id);
        }
        return bug;
    }

    private BugResponse toResponse(Bug bug, User me) {
        return BugResponse.from(bug, BugWorkflow.allowedTargets(me, bug),
                BugPermissions.canEdit(me, bug), BugPermissions.canAssign(me));
    }
}
