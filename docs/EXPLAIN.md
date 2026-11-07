# LedgerLine - Viva & Interview Defence Guide

This guide is written for a software engineer to defend the codebase aloud in an interview. It contains an end-to-end walkthrough of a money transfer request, followed by 15 anticipated interview questions with direct, technical answers.

---

## Part 1: Step-by-Step Code Walkthrough (The Transfer Flow)

When a client initiates a money transfer:
`POST /api/transfers` with headers `Authorization: Bearer <JWT>` and `Idempotency-Key: <UUID>`:

```
[Client]
   │
   ▼ 1. RequestIdFilter
   │   - Generates or extracts X-Request-ID (stored in request attribute & MDC).
   ▼ 2. JwtAuthenticationFilter
   │   - Extracts Bearer token from header.
   │   - Validates HMAC-SHA256 signature and expiration via JwtService.
   │   - Builds UserPrincipal (UUID, username, role) WITHOUT hitting the database.
   │   - Places authentication into Spring SecurityContextHolder.
   ▼ 3. AccountController / TransferController
   │   - Validates @Valid TransferRequest DTO (Bean Validation: positive amount, valid UUIDs).
   │   - Forwards request to IdempotencyService.
   ▼ 4. IdempotencyService.executeIdempotent()
   │   - Computes SHA-256 hash of JSON payload.
   │   - Checks in-memory barrier (inflightRequests map).
   │   - Executes atomic DB claim:
   │     INSERT INTO idempotency_keys (...) ON CONFLICT (user_id, key) DO NOTHING.
   │   - If existing record found: verifies payload hash matches, returns cached response with
   │     header "Idempotent-Replay: true" (or throws 422 if payload changed).
   ▼ 5. LedgerService.transfer() [@Transactional]
   │   - Role check: verifies actor can move money (rejects AUDITOR with 403).
   │   - Self-transfer check: rejects if sourceAccountId.equals(destinationAccountId).
   │   - Deadlock Prevention (Lock Ordering):
   │     Compares UUIDs: if (sourceId.compareTo(destId) < 0) locks Source first, then Dest.
   │     Issues: SELECT * FROM accounts WHERE id = ? FOR UPDATE.
   │   - Ownership Validation: confirms source account owner matches authenticated user ID.
   │     If mismatch: throws ResourceNotFoundException (404 NOT_FOUND).
   │   - Account Status Validation: verifies both accounts are ACTIVE (rejects FROZEN/CLOSED).
   │   - Balance Check (Layer 1): verifies sourceAcc.getBalance() >= amount.
   │   - Balance Mutation:
   │     sourceAcc.setBalance(sourceAcc.getBalance() - amount);
   │     destAcc.setBalance(destAcc.getBalance() + amount);
   │   - Ledger Entries Creation:
   │     Creates DEBIT entry for source account with balanceAfter.
   │     Creates CREDIT entry for dest account with balanceAfter.
   │     Saves Transaction audit record.
   ▼ 6. AuditService.recordAction()
   │   - Writes SUCCESS row to audit_log table via JdbcTemplate.
   ▼ 7. Transaction Commit
   │   - PostgreSQL triggers verify no append-only violations.
   │   - Database CHECK constraint (chk_account_balance) verifies balance >= 0 (Layer 2).
   │   - Pessimistic locks on account rows are released.
   ▼ 8. Idempotency Completion
   │   - Updates idempotency_keys row with HTTP 200 and response JSON payload.
   │   - Releases waiting concurrent threads through in-memory CompletableFuture.
   ▼ 9. HTTP Response
   │   - Returns 200 OK with TransactionResponse DTO to client.
```

---

## Part 2: 15 Likely Interview Questions & Answers

### Q1: Why did you store money in minor units (paise) instead of standard BigDecimal or Double?
**Answer**: "Floating-point numbers (`double`/`float`) suffer from binary rounding errors because base-10 decimals cannot be represented exactly in IEEE 754 binary. Storing amounts in minor units (paise) as a `BIGINT`/`Long` guarantees discrete, lossless integer arithmetic. `BigDecimal` is safe against rounding, but integer `BIGINT` in the database is faster to index, simpler to compute across millions of rows, and enforces that fractional subunits cannot exist."

### Q2: What happens if two users simultaneously transfer money to each other (A -> B and B -> A)? How did you prevent deadlocks?
**Answer**: "A classic database deadlock occurs when Thread 1 locks Account A and requests Account B, while Thread 2 locks Account B and requests Account A (circular wait). In `LedgerService.lockAccountsInOrder()`, I sort the two account IDs lexicographically (`UUID.compareTo()`) and always acquire pessimistic locks (`SELECT ... FOR UPDATE`) in ascending order. Both threads will attempt to lock the smaller UUID first. Whichever acquires it proceeds while the other waits, mathematically eliminating circular wait conditions."

### Q3: Why did you return 404 NOT_FOUND instead of 403 FORBIDDEN when an account holder accesses another person's account?
**Answer**: "To prevent Account Enumeration and existence probing. If an attacker receives a 403, they know that the account exists and belongs to someone else. If they receive 404, an invalid account and an unauthorized account look identical, preventing information leakage."

### Q4: How does your idempotency layer handle 20 identical requests hitting the server at the exact same millisecond?
**Answer**: "We use an atomic pre-execution claim in PostgreSQL: `INSERT INTO idempotency_keys (...) ON CONFLICT (user_id, key) DO NOTHING RETURNING id`. Because `(user_id, key)` is a unique database constraint, exactly one thread inserts the record and receives the ID. The remaining 19 threads fail to insert and wait on an in-memory `CompletableFuture` barrier. When Thread 1 finishes and commits, it notifies the barrier, and all 19 threads return the identical cached response with header `Idempotent-Replay: true` without re-executing."

### Q5: What happens if someone sends the same Idempotency-Key with a different transaction body?
**Answer**: "We store the SHA-256 hash of the request body alongside the key. If an incoming request presents an existing key but the payload hash differs, the system immediately rejects it with HTTP 422 `IDEMPOTENCY_PAYLOAD_MISMATCH` to prevent malicious or accidental payload spoofing."

### Q6: How do you guarantee the book balance across the entire system is always zero?
**Answer**: "We implement a strict double-entry ledger. For internal transfers, money debited from Account A (+DEBIT) equals money credited to Account B (+CREDIT). For external deposits and withdrawals, we use a counterparty `SYS_CASH_CLEARING` account. When a user deposits ₹100, the user account is credited ₹100 and `SYS_CASH_CLEARING` is debited ₹100. Thus, the sum of all debits and credits across the entire system, including the clearing account, mathematically equals zero at all times."

### Q7: Why are negative balances checked in two different layers?
**Answer**: "Defense-in-depth. Layer 1 is in the application service after acquiring the pessimistic lock (`if (account.getBalance() < amount) throw InsufficientFundsException`). Layer 2 is an immutable database CHECK constraint: `CONSTRAINT chk_account_balance CHECK (is_system = true OR balance >= 0)`. Even if an application bug or rogue query bypassed the service layer, the database engine itself rejects any update that would create a negative balance."

### Q8: Why did you use database triggers for append-only tables instead of just removing the UPDATE/DELETE endpoints in Spring Boot?
**Answer**: "Application-level enforcement only protects HTTP traffic. A developer, DBA, or SQL injection could execute a raw `UPDATE` or `DELETE` directly on the database. PostgreSQL triggers execute inside the storage engine on every `UPDATE` or `DELETE` statement and raise an exception, guaranteeing that historical accounting entries and audit logs cannot be modified even via direct database connections."

### Q9: Why was `spring.jpa.open-in-view` disabled?
**Answer**: "With `open-in-view: true` (Spring's default), a database connection from HikariCP is bound to the thread for the entire duration of the HTTP request, including JSON serialization and network transit. Under high concurrency with 50 threads, waiting threads hold connections idle, causing connection pool starvation. With `open-in-view: false`, database connections are acquired only when entering `@Transactional` service methods and immediately released upon method return."

### Q10: Why did you rewrite `AuditService` to use `JdbcTemplate` without `@Transactional` instead of `Propagation.REQUIRES_NEW`?
**Answer**: "When a thread is executing a transaction that holds row locks, calling a method with `@Transactional(propagation = Propagation.REQUIRES_NEW)` requires a *second* database connection from the Hikari pool. Under high concurrency, all available pool connections get acquired by outer transactions, and none can acquire the second connection for auditing, causing a pool deadlock. Furthermore, if a business exception is thrown, Spring marks the transaction as rollback-only; calling `@Transactional(REQUIRED)` in the exception handler would throw `UnexpectedRollbackException`. Using `JdbcTemplate` executes an isolated autocommit insert in ~1ms without holding locks or interfering with transaction rollback states."

### Q11: What is the purpose of the `@Version` column on the `Account` entity?
**Answer**: "The `@Version` column provides an optimistic locking safety net. While money movements use pessimistic write locks (`SELECT ... FOR UPDATE`), the version column ensures that any non-locking read-modify-write operations (such as admin status updates or profile changes) will fail with an `OptimisticLockException` if the account was concurrently modified."

### Q12: How are passwords stored and authenticated?
**Answer**: "Passwords are encrypted using Spring Security's `BCryptPasswordEncoder` with a salt factor of 10. Passwords are never stored in plaintext, never logged, and the password hash column is never exposed in user DTOs."

### Q13: How does your brute-force protection work on login?
**Answer**: "We implemented an in-memory `LoginRateLimiter` using a concurrent hash map tracking failed login attempts by client IP address. After 5 consecutive failed attempts, the IP is locked out for 5 minutes, returning HTTP 429 `RATE_LIMIT_EXCEEDED`. Successful login clears the failure counter."

### Q14: How does reconciliation detect if someone illegally altered an account balance?
**Answer**: "Our reconciliation service (`GET /api/admin/reconciliation`) runs two checks:
1. Global Sum: Computes `SUM(CREDIT) - SUM(DEBIT)` across all ledger entries in the entire database, which must equal 0.
2. Per-Account Audit: For every single account, compares `account.balance` against `SUM(entries for this account)`. If anyone manually modified an account balance without a corresponding ledger entry, reconciliation reports `FAILED` and highlights the exact discrepancy."

### Q15: Why is the JWT stored in `sessionStorage` rather than `localStorage` or `cookies` in the frontend?
**Answer**: "`sessionStorage` isolates the token to the active browser tab and automatically clears when the tab is closed, preventing session lingering on shared computers. `localStorage` persists indefinitely and is vulnerable to persistent XSS retrieval. In enterprise production, HTTP-only secure cookies with CSRF protection are ideal, but for single-page API architectures with multiple client origins, `sessionStorage` with short-lived tokens represents a clean, secure tradeoff."
