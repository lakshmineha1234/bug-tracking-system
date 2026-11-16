package com.bugtracker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/** One status change of a bug. old_status is null for the entry recorded when the bug is reported. */
@Entity
@Table(name = "bug_history")
public class BugHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bug_id", nullable = false)
    private Bug bug;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "changed_by", nullable = false)
    private User changedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "old_status", length = 20)
    private BugStatus oldStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 20)
    private BugStatus newStatus;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected BugHistory() {
    }

    public BugHistory(Bug bug, User changedBy, BugStatus oldStatus, BugStatus newStatus) {
        this.bug = bug;
        this.changedBy = changedBy;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
    }

    @PrePersist
    void onCreate() {
        changedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Bug getBug() { return bug; }
    public User getChangedBy() { return changedBy; }
    public BugStatus getOldStatus() { return oldStatus; }
    public BugStatus getNewStatus() { return newStatus; }
    public Instant getChangedAt() { return changedAt; }
}
