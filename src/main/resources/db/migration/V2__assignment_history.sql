-- Records every change of a bug's assignee: assigned, reassigned or unassigned.
-- old_assignee is NULL when the bug was unassigned before; new_assignee is NULL when it was unassigned.

CREATE TABLE assignment_history (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    changed_at   DATETIME(6) NOT NULL,
    bug_id       BIGINT      NOT NULL,
    changed_by   BIGINT      NOT NULL,
    old_assignee BIGINT      NULL,
    new_assignee BIGINT      NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_assignment_history_bug FOREIGN KEY (bug_id) REFERENCES bugs (id),
    CONSTRAINT fk_assignment_history_changed_by FOREIGN KEY (changed_by) REFERENCES users (id),
    CONSTRAINT fk_assignment_history_old FOREIGN KEY (old_assignee) REFERENCES users (id),
    CONSTRAINT fk_assignment_history_new FOREIGN KEY (new_assignee) REFERENCES users (id)
);
