package com.bugtracker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One change of a bug's assignee. oldAssignee is null when the bug was unassigned before;
 * newAssignee is null when the bug was unassigned.
 */
@Entity
@Table(name = "assignment_history")
public class AssignmentHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bug_id", nullable = false)
    private Bug bug;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "changed_by", nullable = false)
    private User changedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "old_assignee")
    private User oldAssignee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "new_assignee")
    private User newAssignee;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected AssignmentHistory() {
    }

    public AssignmentHistory(Bug bug, User changedBy, User oldAssignee, User newAssignee) {
        this.bug = bug;
        this.changedBy = changedBy;
        this.oldAssignee = oldAssignee;
        this.newAssignee = newAssignee;
    }

    @PrePersist
    void onCreate() {
        changedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Bug getBug() { return bug; }
    public User getChangedBy() { return changedBy; }
    public User getOldAssignee() { return oldAssignee; }
    public User getNewAssignee() { return newAssignee; }
    public Instant getChangedAt() { return changedAt; }
}
