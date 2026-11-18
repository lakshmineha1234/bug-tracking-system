-- Initial BugTrack schema: users, projects, project members, bugs, comments and status history.
-- Matches the schema used before migrations were introduced, so existing databases are baselined at V1.

CREATE TABLE users (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6)  NOT NULL,
    email      VARCHAR(150) NOT NULL,
    enabled    BIT(1)       NOT NULL,
    name       VARCHAR(100) NOT NULL,
    password   VARCHAR(255) NOT NULL,
    role       ENUM('ADMIN','DEVELOPER','TESTER') NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE TABLE projects (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    created_at   DATETIME(6)   NOT NULL,
    description  VARCHAR(1000) NULL,
    project_name VARCHAR(100)  NOT NULL,
    created_by   BIGINT        NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_projects_name UNIQUE (project_name),
    CONSTRAINT fk_projects_created_by FOREIGN KEY (created_by) REFERENCES users (id)
);

CREATE TABLE project_members (
    project_id BIGINT NOT NULL,
    user_id    BIGINT NOT NULL,
    PRIMARY KEY (project_id, user_id),
    CONSTRAINT fk_project_members_project FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT fk_project_members_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE bugs (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    actual_result      VARCHAR(2000) NULL,
    created_at         DATETIME(6)   NOT NULL,
    description        VARCHAR(4000) NULL,
    expected_result    VARCHAR(2000) NULL,
    priority           ENUM('CRITICAL','HIGH','LOW','MEDIUM') NOT NULL,
    severity           ENUM('CRITICAL','MAJOR','MINOR') NOT NULL,
    status             ENUM('CLOSED','FIXED','IN_PROGRESS','OPEN') NOT NULL,
    steps_to_reproduce VARCHAR(4000) NULL,
    title              VARCHAR(200)  NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    assigned_to        BIGINT        NULL,
    project_id         BIGINT        NOT NULL,
    reported_by        BIGINT        NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_bugs_assigned_to FOREIGN KEY (assigned_to) REFERENCES users (id),
    CONSTRAINT fk_bugs_project FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT fk_bugs_reported_by FOREIGN KEY (reported_by) REFERENCES users (id)
);

CREATE TABLE comments (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    comment_text VARCHAR(2000) NOT NULL,
    created_at   DATETIME(6)   NOT NULL,
    bug_id       BIGINT        NOT NULL,
    user_id      BIGINT        NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_comments_bug FOREIGN KEY (bug_id) REFERENCES bugs (id),
    CONSTRAINT fk_comments_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE bug_history (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    changed_at DATETIME(6) NOT NULL,
    new_status ENUM('CLOSED','FIXED','IN_PROGRESS','OPEN') NOT NULL,
    old_status ENUM('CLOSED','FIXED','IN_PROGRESS','OPEN') NULL,
    bug_id     BIGINT      NOT NULL,
    changed_by BIGINT      NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_bug_history_bug FOREIGN KEY (bug_id) REFERENCES bugs (id),
    CONSTRAINT fk_bug_history_changed_by FOREIGN KEY (changed_by) REFERENCES users (id)
);
