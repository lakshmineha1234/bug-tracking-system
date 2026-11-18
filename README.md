# BugTrack – Bug Tracking and Management System

[![Tests](https://github.com/lakshmineha1234/bug-tracking-system/actions/workflows/ci.yml/badge.svg)](https://github.com/lakshmineha1234/bug-tracking-system/actions/workflows/ci.yml)

A web application for software teams to report, assign, track and resolve bugs.
Testers report defects, admins manage projects and assignments, and developers work on the bugs assigned to them.

![Dashboard](docs/screenshots/dashboard.png)

## Screenshots

### Board
Bugs grouped by status. Cards can be dragged to another column, but only moves the user's role allows are accepted.

![Kanban board](docs/screenshots/board.png)

### All bugs
Search by ID, title or description, and filter by status, priority, severity, project and assignee. Columns are sortable and the list can be exported to CSV.

![Bug list with filters](docs/screenshots/bug-list.png)

### Bug details
Each report records the description, steps to reproduce, expected result and actual result, with priority and severity as separate fields.

![Bug details](docs/screenshots/bug-details.png)

### Workflow, comments and history
The status buttons shown depend on the user's role and the bug's current status. Admins assign the bug to a developer in the project. Every status change is recorded.

![Status, assignment, comments and history](docs/screenshots/bug-workflow.png)

### Projects
Admins create projects and manage their members. Testers and developers see only the projects they belong to.

![Projects](docs/screenshots/projects.png)

### Dark mode
The interface follows the operating system's light or dark setting by default. The sun/moon button in the header switches theme, and the choice is remembered in the browser.

![Board in dark mode](docs/screenshots/board-dark.png)

### Sign in
![Sign-in page](docs/screenshots/login.png)

## Features

- Registration and login with BCrypt-hashed passwords and session-based authentication
- Role-based access (Admin, Tester, Developer), enforced in the backend
- Projects with members; testers see their projects' bugs, developers see bugs assigned to them
- Bug reports with description, steps to reproduce, expected and actual results, priority and severity
- Assignment to developers who are members of the bug's project
- Controlled status workflow, with a history of every status change and every assignment change
- Comments on bugs
- Dashboard: counts by status, priority, severity, project and assignee, plus recent activity
- Search and filters (keyword, status, priority, severity, project, assignee), Kanban board, CSV export
- Email notification to a developer when a bug is assigned to them, with a link that opens the bug
- Light and dark themes, with a toggle in the header

## User roles

| Role | Can do |
|---|---|
| Admin | Manage users (role, active/disabled) and projects (create, edit, members); see all bugs; assign bugs; any status transition |
| Tester | Report bugs in their projects; edit bugs they reported; comment; verify fixes (close, or return to the developer); reopen closed bugs |
| Developer | See bugs assigned to them; comment; start work; mark fixes ready for testing |

New users register as Tester or Developer. Admin accounts cannot be self-registered: the first admin is created at startup from environment variables (see below), and admins can promote other users.

## Bug lifecycle

| From | To | Who |
|---|---|---|
| OPEN | IN_PROGRESS | Assigned developer |
| IN_PROGRESS | FIXED | Assigned developer |
| FIXED | CLOSED | Tester (fix verified) |
| FIXED | IN_PROGRESS | Tester (fix failed retest, returned to developer) |
| CLOSED | OPEN | Tester (reopen) |

Admins may perform any of these transitions. Every transition is recorded in `bug_history`, and every change of assignee (assigned, reassigned or unassigned) in `assignment_history`. The bug details show both as one timeline.

## Technology stack

Java 17+, Spring Boot 3.3 (Web, Data JPA, Security, Validation), MySQL 8, HTML/CSS/JavaScript, Maven, JUnit 5, Mockito, Spring Boot Test (H2 for tests).

## Prerequisites

- JDK 17 or later (for example Eclipse Temurin 21), with `JAVA_HOME` set
- MySQL Server 8.x running locally
- Maven is **not** required: the included wrapper (`mvnw.cmd` / `mvnw`) downloads it on first use

## Database setup

```sql
CREATE DATABASE bug_tracking_db;
```

The tables (`users`, `projects`, `project_members`, `bugs`, `comments`, `bug_history`, `assignment_history`) are created on first start by [Flyway](https://documentation.red-gate.com/flyway) migrations in `src/main/resources/db/migration`. Flyway records what it has applied in `flyway_schema_history`, and Hibernate only checks that the tables match the entities (`ddl-auto=validate`).

To change the schema, add a new file such as `V3__add_attachments.sql`; never edit a migration that has already been applied. A database created before migrations were introduced is detected automatically, marked as version 1, and then upgraded.

## Configuration

Settings are read from environment variables, so no secrets are stored in the code.

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/bug_tracking_db` | JDBC URL |
| `DB_USERNAME` | `root` | Database user |
| `DB_PASSWORD` | *(empty)* | Database password |
| `BUGTRACK_ADMIN_EMAIL` | *(none)* | Email of the first admin, created if no admin exists |
| `BUGTRACK_ADMIN_PASSWORD` | *(none)* | Password of the first admin (at least 8 characters) |
| `BUGTRACK_ADMIN_NAME` | `Administrator` | Display name of the first admin |
| `MAIL_ENABLED` | `false` | Send email notifications. When `false`, emails are written to the log instead |
| `MAIL_HOST` / `MAIL_PORT` | `localhost` / `587` | SMTP server |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | *(empty)* | SMTP login |
| `MAIL_SMTP_AUTH` / `MAIL_STARTTLS` | `false` / `false` | Set both to `true` for most hosted mail services |
| `MAIL_FROM` | `BugTrack <no-reply@bugtrack.local>` | Sender address |
| `APP_BASE_URL` | `http://localhost:8080` | Address used for links in emails |

### Email notifications

When an admin assigns a bug to a developer (or reassigns it to a different developer), that developer receives an email with the bug's details and a link such as `http://localhost:8080/#bug-8`, which opens the bug after signing in. Emails are sent in the background after the assignment is saved; if the mail server is unreachable the assignment still succeeds and the failure is logged.

Example for Gmail (create an [app password](https://support.google.com/accounts/answer/185833) first), in `application-local.properties`:

```properties
app.mail.enabled=true
spring.mail.host=smtp.gmail.com
spring.mail.port=587
spring.mail.username=your.address@gmail.com
spring.mail.password=your-app-password
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.starttls.enable=true
app.mail.from=BugTrack <your.address@gmail.com>
```

To try it without a real mail account, run Python's test mail server (Python 3.11 or earlier), which prints every email it receives, and point the app at it with `spring.mail.host=127.0.0.1`, `spring.mail.port=1025` and `app.mail.enabled=true`:

```
python -m smtpd -n -c DebuggingServer 127.0.0.1:1025
```

## Running

Quick start on Windows: copy `application-local.properties.example` to `application-local.properties` in the project folder and fill in your database password and first admin account (the copy is git-ignored, so your passwords stay local), then:

1. `start-mysql.cmd` if MySQL is not already running as a service (uses a data folder under `%USERPROFILE%\.bugtrack-mysql`)
2. `run.cmd` to start the app on http://localhost:8080

Or with environment variables in PowerShell:

```powershell
$env:DB_PASSWORD = "your-mysql-password"
$env:BUGTRACK_ADMIN_EMAIL = "admin@example.com"
$env:BUGTRACK_ADMIN_PASSWORD = "choose-a-strong-password"
.\mvnw.cmd spring-boot:run
```

Then open http://localhost:8080 and sign in as the admin. The backend serves the frontend, so there is nothing separate to start.

Suggested first steps: create a project, have a tester and a developer register, add them to the project (Projects → Members), report a bug as the tester, assign it as the admin, then work it through the lifecycle.

## API summary

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/auth/csrf` | Get the CSRF token to send on state-changing requests |
| POST | `/api/auth/register` | Register (Tester or Developer) |
| POST | `/api/auth/login` | Log in (starts a session) |
| POST | `/api/auth/logout` | Log out |
| GET | `/api/auth/me` | Current user |
| GET | `/api/users` | List users (admin) |
| PATCH | `/api/users/{id}` | Change a user's role or active status (admin) |
| GET | `/api/projects` | Projects visible to the current user |
| POST | `/api/projects` | Create a project (admin) |
| PUT | `/api/projects/{id}` | Edit a project (admin) |
| GET | `/api/projects/{id}/members` | List project members |
| POST / DELETE | `/api/projects/{id}/members/{userId}` | Add / remove a member (admin) |
| GET | `/api/bugs` | List accessible bugs. Filters: `q`, `status`, `priority`, `severity`, `projectId`, `assigneeId`, `unassigned=true` |
| GET | `/api/bugs/{id}` | Get one bug |
| POST | `/api/bugs` | Report a bug (tester, admin) |
| PUT | `/api/bugs/{id}` | Edit bug details (reporter, admin) |
| PATCH | `/api/bugs/{id}/status` | Change status (see lifecycle) |
| PATCH | `/api/bugs/{id}/assignment` | Assign or unassign (admin) |
| GET / POST | `/api/bugs/{id}/comments` | List / add comments |
| GET | `/api/bugs/{id}/history` | Status history |
| GET | `/api/bugs/{id}/assignments` | Assignment history (assigned, reassigned, unassigned) |
| GET | `/api/dashboard/summary` | Dashboard statistics |

Errors are returned as JSON: `{timestamp, status, error, message, fieldErrors}`.

To call the API from Postman: `GET /api/auth/csrf`, then send the token in the `X-CSRF-TOKEN` header on every POST/PUT/PATCH/DELETE (Postman keeps the session cookie).

## Running the tests

```powershell
.\mvnw.cmd test
```

Tests use an in-memory H2 database, so MySQL is not needed. `BugApiIntegrationTest` covers test cases TC-01 to TC-08 from the project documentation, plus permission rules; `BugWorkflowTest` and `BugServiceTest` are unit tests of the workflow and service logic.

## Project structure

```
bug-tracking-system/
├── pom.xml, mvnw, mvnw.cmd, .mvn/wrapper/
└── src/
    ├── main/java/com/bugtracker/
    │   ├── BugTrackingApplication.java
    │   ├── config/        SecurityConfig, AdminInitializer
    │   ├── controller/    Auth, Bug, Project, User, Dashboard controllers
    │   ├── dto/           Request and response records
    │   ├── entity/        JPA entities and enums
    │   ├── exception/     API exceptions and the global handler
    │   ├── repository/    Spring Data JPA repositories
    │   ├── security/      User details, current-user lookup
    │   └── service/       Business logic, permissions, workflow
    ├── main/resources/
    │   ├── application.properties
    │   ├── db/migration/  Flyway migrations (V1__initial_schema.sql, V2__assignment_history.sql)
    │   └── static/        index.html, style.css, script.js
    └── test/              Unit and integration tests
```

## Known limitations and future improvements

- The Flyway version managed by Spring Boot 3.3 logs a warning that it has been tested only up to MySQL 8.1. It works with MySQL 8.4 (verified), and upgrading Spring Boot will bring a newer Flyway.
- No CORS configuration is needed because the backend serves the frontend from the same origin. If the frontend is ever hosted separately, allow only that origin.
- Not yet implemented: file attachments, email notifications, sprints/releases, PDF reports, GitHub integration, pagination of bug lists.
