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

### 5. Unbalanced Test Ledger Entries in Double-Entry System with Append-Only Triggers
- **Problem**: In `SchemaAndHealthTest`, a raw SQL insert tested the append-only trigger by inserting a single `CREDIT` row without a counterparty `DEBIT`. Subsequent double-entry conservation tests failed because the total ledger sum (`calculateTotalLedgerSum()`) was +4000 instead of 0.
- **What was assumed / suggested**: Inserting ad-hoc ledger entries in trigger tests without counterparties.
- **Why it was wrong**: In a double-entry ledger enforced by database append-only triggers, individual rows cannot be rolled back or deleted. An unbalanced test insert permanently corrupts the global book balance (`SUM(CREDIT) - SUM(DEBIT) != 0`).
- **What was done**: Updated `SchemaAndHealthTest` to always insert balanced pairs (+1000 credit on test account and -1000 debit on `SYS_CASH_CLEARING`), ensuring the invariant `total ledger sum = 0` is strictly preserved across all test suites.

---

### 6. Concurrency Race in Post-Execution Idempotency Storage
- **Problem**: When 20 simultaneous threads submitted the same `Idempotency-Key` concurrently, the account was debited multiple times (balance fell to 5,000 instead of 95,000).
- **What was assumed / suggested**: Checking for an existing idempotency record before running the transaction, executing the ledger movement, and then writing the idempotency record afterwards.
- **Why it was wrong**: Under high concurrency, all 20 threads simultaneously executed the check before any of them had committed the record. Each thread proceeded to lock accounts sequentially and move funds, resulting in 19 duplicate executions before the unique constraint was encountered.
- **What was done**: Implemented an atomic pre-execution claim using PostgreSQL `INSERT INTO idempotency_keys (...) VALUES (...) ON CONFLICT (user_id, key) DO NOTHING RETURNING id`. Exactly one thread wins the execution claim; all concurrent threads await the winner's result and receive the replayed response with `Idempotent-Replay: true`.

---

### 7. OpenEntityManagerInView Connection Pool Starvation during Concurrency Tests
- **Problem**: When testing 20 concurrent threads on idempotent endpoints, threads timed out waiting for the primary future to complete.
- **What was assumed / suggested**: Relying on default Spring Boot JPA settings with `spring.jpa.open-in-view: true` and loading user details from DB in `JwtAuthenticationFilter`.
- **Why it was wrong**: With `open-in-view` enabled, every incoming request acquires a database connection from HikariCP for the entire lifecycle of the HTTP request. When 20 threads hit simultaneously and 19 waited on the idempotency barrier, all 20 connections in the pool were held by the waiting threads. The primary executing thread became starved of a DB connection, resulting in a thread/connection deadlock.
- **What was done**: Disabled `spring.jpa.open-in-view: false`, increased pool size to 30, and made `JwtAuthenticationFilter` completely stateless by constructing `UserPrincipal` directly from verified JWT claims rather than querying the database per request.

---
