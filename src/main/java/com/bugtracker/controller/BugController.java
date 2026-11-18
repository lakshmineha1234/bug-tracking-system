package com.bugtracker.controller;

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
import com.bugtracker.entity.BugStatus;
import com.bugtracker.entity.Priority;
import com.bugtracker.entity.Severity;
import com.bugtracker.service.BugService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/bugs")
public class BugController {

    private final BugService bugService;

    public BugController(BugService bugService) {
        this.bugService = bugService;
    }

    @GetMapping
    public List<BugResponse> list(@RequestParam(required = false) String q,
                                  @RequestParam(required = false) BugStatus status,
                                  @RequestParam(required = false) Priority priority,
                                  @RequestParam(required = false) Severity severity,
                                  @RequestParam(required = false) Long projectId,
                                  @RequestParam(required = false) Long assigneeId,
                                  @RequestParam(required = false) Boolean unassigned) {
        return bugService.list(new BugFilter(q, status, priority, severity, projectId, assigneeId, unassigned));
    }

    @GetMapping("/{id}")
    public BugResponse get(@PathVariable Long id) {
        return bugService.get(id);
    }

    @PostMapping
    public ResponseEntity<BugResponse> create(@Valid @RequestBody CreateBugRequest req) {
        BugResponse created = bugService.create(req);
        return ResponseEntity.created(URI.create("/api/bugs/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public BugResponse update(@PathVariable Long id, @Valid @RequestBody UpdateBugRequest req) {
        return bugService.update(id, req);
    }

    @PatchMapping("/{id}/status")
    public BugResponse changeStatus(@PathVariable Long id, @Valid @RequestBody StatusChangeRequest req) {
        return bugService.changeStatus(id, req);
    }

    @PatchMapping("/{id}/assignment")
    public BugResponse assign(@PathVariable Long id, @RequestBody AssignmentRequest req) {
        return bugService.assign(id, req);
    }

    @GetMapping("/{id}/comments")
    public List<CommentResponse> comments(@PathVariable Long id) {
        return bugService.comments(id);
    }

    @PostMapping("/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse addComment(@PathVariable Long id, @Valid @RequestBody CommentRequest req) {
        return bugService.addComment(id, req);
    }

    @GetMapping("/{id}/history")
    public List<HistoryResponse> history(@PathVariable Long id) {
        return bugService.history(id);
    }

    @GetMapping("/{id}/assignments")
    public List<AssignmentHistoryResponse> assignments(@PathVariable Long id) {
        return bugService.assignmentHistory(id);
    }
}
