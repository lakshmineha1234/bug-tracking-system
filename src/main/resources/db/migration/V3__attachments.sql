-- Files attached to bugs (screenshots, logs, documents).
-- The file contents live on disk under app.attachments.dir; storage_key is the file's random name there.

CREATE TABLE attachments (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    bug_id            BIGINT       NOT NULL,
    uploaded_by       BIGINT       NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type      VARCHAR(100) NOT NULL,
    size_bytes        BIGINT       NOT NULL,
    storage_key       VARCHAR(64)  NOT NULL,
    created_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_attachments_storage_key UNIQUE (storage_key),
    CONSTRAINT fk_attachments_bug FOREIGN KEY (bug_id) REFERENCES bugs (id),
    CONSTRAINT fk_attachments_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES users (id)
);
