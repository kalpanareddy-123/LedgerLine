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
- **Why it was wrong**: PostgreSQL on macOS via Homebrew initializes with role matching the host system login and trust/peer auth, without `ledgerline_app` or specific passwords configured.
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

### 8. Foreign Key Constraint on `transactions.created_by` in Test Seed Data
- **Problem**: When seeding initial funds in test classes via `ledgerService.deposit(...)`, tests failed with `DataIntegrityViolationException: Key (created_by)=(<uuid>) is not present in table "users"`.
- **What was assumed / suggested**: Passing a dummy `UserPrincipal` with `UUID.randomUUID()` to represent the system or admin actor during test setup.
- **Why it was wrong**: The schema enforces referential integrity with a foreign key constraint `transactions.created_by REFERENCES users(id)`. Generating random UUIDs violates this constraint.
- **What was done**: Saved an explicit admin user in the test setup via `userRepository.save(...)` and passed its persisted ID in the `UserPrincipal`.

---

### 9. Hibernate `ObjectOptimisticLockingFailure` on Stale Entity Version Post-Deposit
- **Problem**: In `LedgerEngineTest.setup()`, setting `frozenAccount.setStatus(AccountStatus.FROZEN)` threw `ObjectOptimisticLockingFailure: Row was updated or deleted by another transaction`.
- **What was assumed / suggested**: Calling `accountRepository.save(frozenAccount)` using the local entity reference created prior to making a test deposit.
- **Why it was wrong**: Calling `ledgerService.deposit(...)` loaded and modified the account in its own transaction, which incremented the `@Version` column from 0 to 1. The local in-memory reference in `setup()` still held `version = 0`. Attempting to save it caused Hibernate to check for `WHERE version = 0`, detecting an optimistic lock collision.
- **What was done**: Reloaded the latest entity state from the database via `accountRepository.findById(frozenAccount.getId()).orElseThrow()` before updating its status to `FROZEN`.

---

### 10. Statement Entry Ordering for Banking Statements and Pagination
- **Problem**: Statement test expected the most recent transaction to appear as the first entry on page 0 (`$.entries.content[0]`), but received the oldest initial deposit.
- **What was assumed / suggested**: Querying ledger entries with `OrderByCreatedAtAsc` (oldest first).
- **Why it was wrong**: For user-facing financial statement endpoints and transaction history pagination, customers expect to see their latest activity immediately on the first page (`DESC` ordering), while retaining chronological running balance computation.
- **What was done**: Added `findByAccountIdOrderByCreatedAtDesc` and `findByAccountIdAndCreatedAtBetweenOrderByCreatedAtDesc` to `LedgerEntryRepository` and updated `StatementService` to return latest-first entries.

---

### 11. System Clearing Account Balance Desynchronization during Trigger Tests
- **Problem**: The reconciliation endpoint `GET /api/admin/reconciliation` reported `FAILED` with an account mismatch on `SYS_CASH_CLEARING`.
- **What was assumed / suggested**: In `SchemaAndHealthTest`, a raw SQL query inserted a DEBIT entry for `SYS_CASH_CLEARING` to balance a test CREDIT entry, but did not update the `balance` column on `accounts`.
- **Why it was wrong**: In LedgerLine, reconciliation compares both the total ledger sum (`SUM(entries) == 0`) AND each account's stored `balance` against `SUM(account_entries)`. Because `SYS_CASH_CLEARING.balance` remained 0 while its ledger entries summed to -1000, reconciliation correctly flagged a ledger-to-account mismatch.
- **What was done**: Added an `UPDATE accounts SET balance = balance - 1000 WHERE id = 'SYS_CASH_CLEARING'` statement in `SchemaAndHealthTest` to keep the stored balance perfectly synchronized with the appended ledger entry.

---

### 12. HikariCP Connection Pool Exhaustion from `Propagation.REQUIRES_NEW`
- **Problem**: Running 50 concurrent transactions timed out after 20,000ms with `SQLTransientConnectionException: HikariPool - Connection is not available, request timed out`.
- **What was assumed / suggested**: Annotating audit logging methods with `@Transactional(propagation = Propagation.REQUIRES_NEW)` inside service transactions that hold pessimistic row locks.
- **Why it was wrong**: Under high concurrency (e.g. 50 parallel requests with a pool of 30 connections), 30 threads each acquired an outer connection and blocked on pessimistic row locks. The active thread holding the lock then called the `REQUIRES_NEW` audit service, which requested a *second* connection from the pool. Because all 30 connections were already held by the waiting threads, a classic pool deadlock occurred where threads waited on each other indefinitely until connection timeouts fired.
- **What was done**: Increased pool size to 50 and decoupled audit log writes from Spring JPA transactions by using direct `JdbcTemplate` autocommit inserts in `AuditService`. Each thread now holds at most 1 connection, completely eliminating connection pool deadlocks.

---

### 13. UnexpectedRollbackException When Auditing Failures in GlobalExceptionHandler
- **Problem**: Handling business exceptions in `GlobalExceptionHandler` resulted in `NestedServletException` with 500 errors instead of returning standard 400/404/422 responses.
- **What was assumed / suggested**: Using `@Transactional(propagation = Propagation.REQUIRED)` for `AuditService.record(...)`.
- **Why it was wrong**: When an exception was thrown inside a `@Transactional` service, Spring marked the active transaction as `rollback-only`. When `GlobalExceptionHandler` caught the exception and invoked `auditService.record(...)`, Spring's transaction interceptor joined the rollback-only transaction. Exiting `record(...)` caused Spring to throw `UnexpectedRollbackException: Transaction silently rolled back because it has been marked as rollback-only`, crashing the exception handler.
- **What was done**: Rewrote `AuditService` to use direct `JdbcTemplate` autocommit executions without `@Transactional`, allowing audit logging of both successful and failed attempts without coupling to Hibernate transaction state or causing rollback exceptions.

---

### 14. Status Code Mismatch on Insufficient Funds Test Assertion (422 vs 400)
- **Problem**: In `ConcurrencyAndConservationTest.fiftyParallelWithdrawalsLimitedFunds`, the count of insufficient funds failures was recorded as 0 instead of 30.
- **What was assumed / suggested**: Asserting that rejected withdrawal responses returned HTTP 400 Bad Request.
- **Why it was wrong**: In the domain model, insufficient funds represents a semantically valid request syntax that cannot be processed due to business domain constraints, which `InsufficientFundsException` correctly maps to HTTP 422 `UNPROCESSABLE_ENTITY`. The test assertion strictly checked `status == 400`.
- **What was done**: Updated the test assertions to accept HTTP 422 `UNPROCESSABLE_ENTITY` (or 400) when verifying `INSUFFICIENT_FUNDS` rejection payloads.

---

### 15. Lack of Explicit Test Profile on Test Classes Endangering Production/Dev Database
- **Problem**: Six test classes (`SchemaAndHealthTest`, `AccountAndAdminTest`, `AuthAndSecurityTest`, `IdempotencyTest`, `LedgerEngineTest`, `StatementReconciliationAuditTest`) lacked the `@ActiveProfiles("test")` annotation, defaulting to the active environment in `application.yml` (`ledgerline_db`).
- **What was assumed / suggested**: Relying on default Spring Boot test context resolution without explicit test profile declaration.
- **Why it was wrong**: Under test execution, if an ambient `TEST_DB_URL` is not set or default profile is used, tests would run against the primary `ledgerline_db`, violating the global isolation rule and corrupting demo or production state.
- **What was done**: Added `@ActiveProfiles("test")` across all 7 test classes and configured `application-test.yml` to target the isolated `ledgerline_test_db`.

---

### 16. Username-Based Rate Limiting vs Per-IP Lockout with X-Forwarded-For
- **Problem**: `LoginRateLimiter` keyed attempts by lowercase username rather than client IP, allowing attackers to cycle through usernames from a single IP without triggering lockout, and never expired stale failed attempts.
- **What was assumed / suggested**: Keying the login rate limiter by `request.username().toLowerCase()` with infinite attempt lifetime.
- **Why it was wrong**: Keying by username allows password spraying attacks across many user accounts from a single malicious IP. Furthermore, without a sliding window expiration, a user who failed 4 times across several weeks would be locked out upon a single typo weeks later.
- **What was done**: Keyed the rate limiter by client IP (extracting the leftmost client IP from `X-Forwarded-For` header safely), added a 10-minute sliding window (`ATTEMPT_WINDOW_SECONDS`) to expire stale attempts, and ensured successful logins clear the IP record.

---

### 17. PostgreSQL TRUNCATE Cannot Use Row-Level Triggers
- **Problem**: Adding append-only protection against `TRUNCATE` using `FOR EACH ROW` failed with `ERROR: TRUNCATE triggers must be FOR EACH STATEMENT`.
- **What was assumed / suggested**: Attaching the existing `prevent_tamper()` trigger function (which uses `FOR EACH ROW`) to `TRUNCATE` events on `ledger_entries` and `audit_log`.
- **Why it was wrong**: In PostgreSQL, the `TRUNCATE` operation is a bulk DDL/statement-level command that does not scan or process individual rows; hence, PostgreSQL specifically forbids `FOR EACH ROW` on `TRUNCATE` triggers.
- **What was done**: Created Flyway migration `V2__block_truncate.sql` defining a dedicated `prevent_truncate()` trigger function executing `BEFORE TRUNCATE ... FOR EACH STATEMENT` on `ledger_entries` and `audit_log` raising an exception, and explicitly revoked `TRUNCATE` privileges from public.

---

### 18. Admin Role Permitted to Move Money Violating Separation of Duties
- **Problem**: Admin accounts were previously allowed to execute deposits, withdrawals, and transfers, failing strict banking governance tests where admins manage users/accounts but are prohibited from moving money.
- **What was assumed / suggested**: Permitting `ADMIN` role to execute deposits or money movements directly.
- **Why it was wrong**: In financial core systems, administrative staff must never have the authority to create transactions or move balances. Doing so violates separation of duties and regulatory auditing requirements.
- **What was done**: Restricted deposit, withdrawal, and transfer endpoints in `LedgerService` to `ACCOUNT_HOLDER` principals only, throwing HTTP 403 `ACCESS_DENIED` if an `ADMIN` or `AUDITOR` attempts to move funds. Updated test suites to use account holder principals (`alicePrincipal`, `bobPrincipal`) for initial funding and money transfers.

---

### 19. Currency Check Constraint on Non-INR Accounts in Test Setup
- **Problem**: Attempting to create an account with currency `USD` to test cross-currency transfer rejection failed with `DataIntegrityViolationException: new row for relation "accounts" violates check constraint "chk_account_currency"`.
- **What was assumed / suggested**: Saving an account with `currency = "USD"` to test domain validation in `LedgerService`.
- **Why it was wrong**: The initial database migration `V1__init_schema.sql` has a strict table constraint `CONSTRAINT chk_account_currency CHECK (currency = 'INR')`, preventing non-INR accounts from being persisted at all.
- **What was done**: Added explicit domain validation in `LedgerService.transfer` checking that source and destination currencies match and are supported, and tested that raw SQL inserts of unsupported currencies are rejected by the database constraint `chk_account_currency`.

---

### 20. Local Docker CLI Not Available & Zero-Docker Maven Deployment
- **Problem**: Docker CLI was not installed on the developer machine, preventing local execution of `docker compose config` and `docker build`.
- **What was assumed / suggested**: Relying exclusively on Docker containers for deployment.
- **Why it was wrong**: Assuming Docker is universally installed on developer machines or evaluation environments blocks candidates and evaluators without Docker Desktop from building and running.
- **What was done**: Added a native Java/Maven deployment pathway for Render (`./mvnw clean package -DskipTests` and `java -jar target/ledgerline-0.0.1-SNAPSHOT.jar`), eliminating Docker dependency while preserving the Dockerfile for containerized environments.

---

### 21. Secrets Scan Scope Limitations & Distinguishing Dev Defaults from Cloud Secrets
- **Problem**: An initial regex secrets scan claimed "clean" because it checked specific patterns, but early development commits contained default strings (`ledgerline_secret`, initial mock JWT secret), and local terminal test commands used `DB_PASSWORD=secret`.
- **What was assumed / suggested**: Declaring the repository completely free of secrets based on regex scans alone.
- **Why it was wrong**: Regex scans do not recognize domain-specific dev passwords or early placeholder commits. Developers might mistakenly assume dev defaults are acceptable for production.
- **What was done**: Documented that all historical defaults (`ledgerline_secret`, local test tokens) must be treated strictly as **DEV-ONLY**. Instructed in `DEPLOY.md` and `README.md` that production cloud deployments must generate brand-new, cryptographically random keys via `openssl rand -base64 48` and never reuse dev defaults.

---

### 22. Two-Way CORS Handshake Between Render Backend and Vercel Frontend
- **Problem**: When deploying backend and frontend on separate cloud providers (Render and Vercel), initial backend deployments only permit localhost origins (`http://localhost:3000,http://localhost:5173`).
- **What was assumed / suggested**: Assuming the frontend would connect to the backend without explicit origin synchronization.
- **Why it was wrong**: Browsers strictly enforce the Same-Origin Policy. When the Vercel SPA sends `POST /api/auth/login` to Render, the browser blocks the preflight request, causing login to fail with `Network Error`.
- **What was done**: Added a prominent warning in `DEPLOY.md` detailing Step 5: immediately after Vercel assigns the live frontend domain, update `CORS_ALLOWED_ORIGINS` in Render and wait for the zero-downtime redeploy before testing.
