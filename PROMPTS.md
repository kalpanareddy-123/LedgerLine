# PROMPTS.md - Engineering Log & Corrections

This document logs mistakes, wrong assumptions, build errors, and design corrections encountered during the development of **LedgerLine**, following the format:
- **Problem**
- **What was assumed / suggested**
- **Why it was wrong**
- **What was done**

---

### 1. Java 25 vs Installed Java 21
- **Problem**: The macOS environment default `java` pointed to Oracle JDK 25 (`build 25+37-LTS-3491`), whereas Spring Boot 3.3.x / 3.4.x and enterprise production targets are standardized on Java 21 LTS (`/opt/homebrew/opt/openjdk@21`).
- **What was assumed / suggested**: Running Maven with default ambient system Java (Java 25).
- **Why it was wrong**: Java 25 includes experimental features and compiler incompatibilities with current bytecode processors, Lombok, and standard Spring Boot 3 plugins expecting Java 21 LTS.
- **What was done**: Explicitly pinned `<java.version>21</java.version>` in `pom.xml`, set build scripts and test runs to use `JAVA_HOME=/opt/homebrew/opt/openjdk@21`, and configured Dockerfile targeting Eclipse Temurin 21 JRE/JDK.

---

### 2. Unresolvable "governance" Dependency
- **Problem**: References to a non-existent or proprietary starter `spring-boot-starter-governance` can appear in enterprise prompt requirements or scaffolds.
- **What was assumed / suggested**: Including `spring-boot-starter-governance` in `pom.xml`.
- **Why it was wrong**: No such public artifact exists in Maven Central; attempting to resolve it causes `ArtifactNotFoundException` and halts the build.
- **What was done**: Excluded the fictitious dependency and implemented governance and auditing natively using Spring Data JPA auditing, custom AuditLog service, and Spring Security filters.

---

### 3. Flyway PostgreSQL Dependency Version Management
- **Problem**: In Spring Boot 3.x, adding only `flyway-core` results in `FlywayException: Unsupported Database: PostgreSQL` or class loading errors during migration.
- **What was assumed / suggested**: Adding only `org.flywaydb:flyway-core` to `pom.xml`.
- **Why it was wrong**: Since Flyway 9/10, database-specific drivers were modularized. PostgreSQL requires `org.flywaydb:flyway-database-postgresql` alongside `flyway-core`.
- **What was done**: Included both `org.flywaydb:flyway-core` and `org.flywaydb:flyway-database-postgresql` with versions managed through Spring Boot starter parent dependencies.

---

### 4. PostgreSQL Authentication Failure
- **Problem**: Connecting to `ledgerline_db` failed with password authentication error for the application user.
- **What was assumed / suggested**: Default local peer authentication or assuming a pre-existing `ledgerline_app` user with a hardcoded password.
- **Why it was wrong**: PostgreSQL on macOS via Homebrew initializes with role matching the OS user (`harsha`) and trust/peer auth, without `ledgerline_app` or specific passwords configured.
- **What was done**: Initialized the database role `ledgerline_app` with explicit password authentication, granted all privileges on `ledgerline_db` and its `public` schema, and wired connection credentials cleanly through environment variables (`DB_USER`, `DB_PASSWORD`, `DB_URL`).

---
