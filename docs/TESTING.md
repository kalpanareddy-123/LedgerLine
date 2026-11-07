# LedgerLine - Comprehensive Testing Guide

This guide explains how to run the LedgerLine automated test suite, how the tests achieve zero-flakiness without arbitrary sleeps, and the exact invariants proven by each of the 7 test classes.

---

## 1. Environment & Prerequisites

### System Requirements
- **Java**: Eclipse Adoptium OpenJDK 21 LTS (`java -version` must show Java 21)
- **Database**: PostgreSQL 15+ (Running locally or in Docker)
- **Isolated Test Database**: `ledgerline_test_db` (Ensures `ledgerline_db` is never modified by tests)

### Database Setup
Before running tests, verify the test database and role exist:
```sql
CREATE DATABASE ledgerline_test_db;
CREATE USER ledgerline_app WITH ENCRYPTED PASSWORD 'secret';
GRANT ALL PRIVILEGES ON DATABASE ledgerline_test_db TO ledgerline_app;
\c ledgerline_test_db
GRANT ALL ON SCHEMA public TO ledgerline_app;
```

---

## 2. How to Run on Windows Command Prompt (CMD)

Open `cmd.exe` as Administrator or your standard user, navigate to `backend\ledgerline`, and run:

```cmd
cd backend\ledgerline

:: 1. Set Java 21 Home
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.x-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%

:: 2. Configure Isolated Test Database Credentials
set TEST_DB_URL=jdbc:postgresql://localhost:5432/ledgerline_test_db
set TEST_DB_USER=ledgerline_app
set TEST_DB_PASSWORD=secret
set JWT_SECRET=v9y$B&E)H@MbQeThWmZq4t7w!z%C*F-JaNdRfUjXn2r5u8x/A?D(G+KbPeShVkYp

:: 3. Run the Full Test Suite
mvnw.cmd test
```

### Running a Specific Test Class on Windows:
```cmd
mvnw.cmd test -Dtest=ConcurrencyAndConservationTest
```

---

## 3. How to Run on Linux / macOS (Bash / Zsh)

```bash
cd backend/ledgerline

# 1. Set Java 21 Home
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH=$JAVA_HOME/bin:$PATH

# 2. Configure Isolated Test Database Credentials
export TEST_DB_URL=jdbc:postgresql://localhost:5432/ledgerline_test_db
export TEST_DB_USER=ledgerline_app
export TEST_DB_PASSWORD=secret
export JWT_SECRET=v9y$B&E)H@MbQeThWmZq4t7w!z%C*F-JaNdRfUjXn2r5u8x/A?D(G+KbPeShVkYp

# 3. Run the Full Test Suite
./mvnw test
```

---

## 4. Zero-Flakiness Guarantee: Latches vs Sleeps

In distributed and concurrent systems testing, using `Thread.sleep(...)` for synchronization is an anti-pattern:
1. It slows down the test suite unnecessarily.
2. It fails intermittently under high CPU load or on slower CI runner hardware.

**LedgerLine test suites use zero sleeps for thread coordination.** All concurrent tests use Java Concurrency primitives:
- `CountDownLatch(1)` as a starting gun to guarantee all threads hit the database simultaneously.
- `CountDownLatch(N)` to wait for all worker threads to complete before asserting invariants.
- `CompletableFuture.allOf(...)` to coordinate non-blocking futures.

---

## 5. What Each Test Class Proves

### 1. `ConcurrencyAndConservationTest.java` (3 Tests)
- **`doubleEntryConservationUnderHighConcurrency`**:
  - *Scenario*: Spawns 50 threads executing randomized deposits, withdrawals, and inter-account transfers.
  - *Invariant Proven*: Global conservation $\sum \text{ledger\_entries} = 0$ holds at all times. For every account, its cached `balance` column strictly equals $\sum \text{account\_entries}$.
- **`fiftyParallelWithdrawalsLimitedFunds`**:
  - *Scenario*: 50 concurrent threads simultaneously attempt to withdraw ₹5.00 from an account with only ₹100.00.
  - *Invariant Proven*: Exactly 20 withdrawals succeed, exactly 30 fail with `422 INSUFFICIENT_FUNDS`, and the final balance is exactly ₹0.00. Proves that balances never drop below zero under race conditions.
- **`deadlockFreeOppositeConcurrentTransfers`**:
  - *Scenario*: 40 concurrent threads execute opposite transfers ($A \to B$ and $B \to A$) simultaneously.
  - *Invariant Proven*: Because row locks are acquired in strictly ascending UUID order (`idA.compareTo(idB) < 0`), circular lock wait conditions are mathematically impossible. Zero deadlocks occur.

### 2. `IdempotencyTest.java` (6 Tests)
- **`twentyWayConcurrentRace`**:
  - *Scenario*: 20 threads submit the same `Idempotency-Key` and identical JSON payload simultaneously.
  - *Invariant Proven*: Exactly one thread performs the debit/credit. The remaining 19 threads receive the cached result with `Idempotent-Replay: true`. The account balance is deducted exactly once.
- **`sameKeyDifferentBodyRejected`**:
  - *Scenario*: A client submits the same `Idempotency-Key` with a different payload.
  - *Invariant Proven*: Rejects immediately with `422 IDEMPOTENCY_PAYLOAD_MISMATCH`, preventing key hijacking.
- **`replayReturnsIdenticalResponseBody`**:
  - *Scenario*: Consecutive requests with the same key and payload.
  - *Invariant Proven*: Returns identical HTTP status and body as the original transaction.
- **`keyReleaseOnBusinessFailure`**:
  - *Scenario*: First attempt fails due to insufficient funds.
  - *Invariant Proven*: The idempotency key is released/deleted from `idempotency_keys` so the user can safely retry once the account is funded.
- **`userScopedIdempotency`**:
  - *Scenario*: Alice and Bob submit requests with the same key string.
  - *Invariant Proven*: Because keys are scoped `UNIQUE(user_id, key)`, Alice and Bob do not interfere with each other.

### 3. `LedgerEngineTest.java` (14 Tests)
- **`forcedMidTransferFailureRollsBackFully`**:
  - *Scenario*: Simulates an unexpected JVM or database crash right after debiting the source account but before crediting the destination.
  - *Invariant Proven*: Spring `@Transactional` and PostgreSQL rollback ensure neither account's balance changes, no ledger entries are created, and no transaction record remains.
- **`balanceEqualsSumOfOwnLedgerEntriesAfterEveryOperation`**:
  - *Scenario*: Sequential deposits, transfers, and withdrawals.
  - *Invariant Proven*: After every operation, stored balance matches the ledger sum.
- **`ownershipProtectionOnAllAccountEndpoints`**:
  - *Scenario*: Bob attempts to access Alice's account across all endpoints: `GET /api/accounts/{id}`, `POST /deposit`, `POST /withdraw`, `POST /transfer` (source), `GET /statement`, `GET /statement/csv`.
  - *Invariant Proven*: Every endpoint returns HTTP `404 NOT_FOUND` (never 403), preventing attackers from enumerating valid account IDs.
- **`auditorAndAdminBarredFromMovingMoney`**:
  - *Scenario*: An auditor or admin attempts to deposit, withdraw, or transfer funds.
  - *Invariant Proven*: Banking separation of duties; both roles receive HTTP `403 ACCESS_DENIED`.
- **`validationRejections`**:
  - *Scenario*: Self-transfers, zero amounts, negative amounts, closed accounts, frozen accounts, and nonexistent accounts.
  - *Invariant Proven*: Rejected with clear, structured 400 or 422 error responses.
- **`arithmeticOverflowProtectedWithExactMath`**:
  - *Scenario*: Calculations approaching `Long.MAX_VALUE`.
  - *Invariant Proven*: Enforced via `Math.addExact` and `Math.subtractExact`; overflows throw `ArithmeticException` mapped to 400, preventing integer wrap-around.

### 4. `AuthAndSecurityTest.java` (13 Tests)
- **Token Tampering & Security**:
  - Expired tokens, tampered payloads, wrong HMAC signatures, and `alg=none` tokens all return HTTP `401 UNAUTHORIZED`.
- **Non-Public Endpoint Protection**:
  - All protected endpoints return HTTP 401 when accessed without an `Authorization: Bearer <token>` header.
- **IP-Based Login Rate Limiting**:
  - 5 consecutive failed attempts from an IP lock out further attempts for 5 minutes.
  - Safely extracts the leftmost client IP from `X-Forwarded-For` to prevent reverse-proxy spoofing.
  - Stale failed attempts expire after a sliding window.
- **Password Policy**:
  - Passwords < 8 characters rejected with 400 `VALIDATION_FAILED`.
  - Passwords and BCrypt hashes are strictly excluded from all API response DTOs and logging formatters.

### 5. `SchemaAndHealthTest.java` (4 Tests)
- **`healthEndpointReturnsOk`**:
  - Verifies `/api/health` is public and returns `{"status":"UP","timestamp":...}` without authentication.
- **`ledgerEntriesTamperTriggerBlocksUpdateAndDelete`**:
  - Attempts to execute raw SQL `UPDATE ledger_entries` or `DELETE FROM ledger_entries` trigger PostgreSQL exception `P0001: ledger_entries is append-only`.
- **`auditLogTamperTriggerBlocksUpdateAndDelete`**:
  - Attempts to execute raw SQL `UPDATE audit_log` or `DELETE FROM audit_log` fail at the PostgreSQL trigger level.
- **`ledgerEntriesTruncateBlocked` & `auditLogTruncateBlocked`**:
  - Flyway migration `V2__block_truncate.sql` statement-level triggers block `TRUNCATE ledger_entries` and `TRUNCATE audit_log`.

### 6. `StatementReconciliationAuditTest.java` (11 Tests)
- **`reconciliationDetectsCorruptedBalanceAndRecovers`**:
  - *Scenario*: In the test database, an account's balance is intentionally corrupted via direct SQL (`UPDATE accounts SET balance = balance + 100000`).
  - *Invariant Proven*: `GET /api/admin/reconciliation` returns `status: "FAILED"` with discrepancy details. When corrected, reconciliation returns `status: "OK"`.
- **`deniedActionWritesAuditLogRow`**:
  - *Scenario*: An unauthorized user attempts to access a protected account.
  - *Invariant Proven*: Even when an HTTP request fails or is rejected with 403/404, an immutable audit log entry is written recording the attempt.
- **`statementAndCsvExport`**:
  - Verifies running balance computation, descending pagination, and RFC 4180 CSV export generation.

### 7. `AccountAndAdminTest.java` (6 Tests)
- **`accountLifecycleManagement`**:
  - Verifies account creation, retrieval, and status transitions: `ACTIVE` $\to$ `FROZEN` $\to$ `ACTIVE` $\to$ `CLOSED`.
- **`adminRoleAccessControl`**:
  - Verifies that only users with the `ADMIN` role can freeze, unfreeze, or close customer accounts.

---

## 6. Test Suite Invariant Summary Table

| Category | Invariant Proven | Enforcement Layer | Test Count |
| :--- | :--- | :--- | :--- |
| **Double-Entry** | Total book sum $\sum = 0$; $\text{balance} = \sum \text{entries}$ | Service + DB Triggers | 3 |
| **Concurrency** | No overdrafts, zero deadlocks | Row locks (`FOR UPDATE` ASC) | 2 |
| **Idempotency** | Exactly one execution per key; replay identical; mismatch 422 | DB `ON CONFLICT` + Single-Node Barrier | 6 |
| **Security & Auth** | 401 unauth/tampered, per-IP limiter, password min length | Spring Security + JWT Service | 13 |
| **Access Control** | 404 for unowned accounts, 403 for admin/auditor moving money | Controller + Service layer | 15 |
| **Immutability** | Zero `UPDATE`, `DELETE`, or `TRUNCATE` permitted on ledgers/audits | PostgreSQL `BEFORE` Triggers | 4 |
| **Audit & Reconcile** | Reconcile flags SQL corruption; failed attempts audited | Reconciliation Service + Direct JDBC | 8 |
| **Account Lifecycle**| Freeze/unfreeze/close restricted to admin | Account Service | 6 |
| **Total** | **All Invariants Verified & Green** | **Multi-Layered** | **57 Tests** |
