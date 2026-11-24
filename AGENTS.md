# Project guidelines

## Context
Backend coding challenge for Cocos Capital. The original challenge mentions Node.js, but the implementation must be in Java.

## Technology stack
- Java 21.
- Spring Boot.
- Maven.
- Spring Data JPA.
- PostgreSQL.
- Jakarta Bean Validation.
- JUnit and Testcontainers.

## Architecture
- Use package-by-feature organization.
- Follow SOLID principles without overengineering.
- Use constructor injection.
- Use Java Records for DTOs.
- Keep controllers thin and business logic in services.
- Do not expose JPA entities through REST endpoints.
- Prefer simple and readable solutions over unnecessary abstractions.

## Database
- Preserve the original database schema.
- Use BigDecimal for monetary calculations.
- Use Integer for database identifiers.
- Use explicit mappings for database column names.
- Persist enums as strings.
- Use lazy loading for entity relationships.
- Configure Hibernate with ddl-auto: validate.
- Never hardcode credentials.
- Never modify or execute write operations against the remote Neon database.
- Run integration tests against an isolated PostgreSQL instance.

## Development workflow
- Implement one feature at a time.
- Do not introduce unnecessary dependencies.
- Do not modify unrelated files.
- Explain relevant architectural decisions.
- Identify ambiguous business requirements before implementing them.
- Run compilation and relevant tests after each implementation.
- Do not commit or push changes without explicit approval.

## Token and Context Efficiency

- Keep responses concise and technical.
- Avoid repeating requirements already documented in CHALLENGE.md.
- Do not explain basic Java or Spring concepts unless requested.
- Inspect only files relevant to the current task.
- Prefer targeted searches over reading entire directories.
- Avoid unnecessary tool calls.
- Do not print complete source files when changes are already applied.
- Prefer minimal, focused changes.
- Do not re-read unchanged files unnecessarily.
- Run targeted tests during development and the full test suite at feature completion.
- Use external documentation only when necessary.
- Avoid speculative implementations and premature abstractions.
- At the end of each task, report only:
  - Changes implemented.
  - Modified files.
  - Compilation and test results.
  - Relevant technical decisions.
  - Remaining issues or blockers.

## Language
- Write source code, comments, documentation and commit messages in English.