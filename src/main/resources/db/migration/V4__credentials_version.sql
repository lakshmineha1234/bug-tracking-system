-- Increases whenever a user's password, role or enabled state changes. Each sign-in session remembers the
-- value it started with, and a session whose value no longer matches is ended, so a password change or
-- admin reset signs the user out everywhere else.

ALTER TABLE users ADD COLUMN credentials_version INT NOT NULL DEFAULT 0;
