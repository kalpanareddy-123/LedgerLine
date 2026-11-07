# LedgerLine - Viva & Interview Defence Guide

This guide is designed for a software engineer to defend every architectural choice and line of code live in an interview. It contains an end-to-end file-by-file walkthrough of a money transfer request with exact method names, followed by **20 comprehensive interview questions and technical answers**.

---

## Part 1: Step-by-Step Code Walkthrough (The Transfer Flow)

When a client initiates a money transfer:
`POST /api/transfers` with headers `Authorization: Bearer <JWT>` and `Idempotency-Key: <UUID>`:

```
[Customer Client (React SPA / Mobile)]
         │
         ▼ 1. RequestIdFilter.doFilterInternal()
         │    - File: com.snovasys.ledgerline.filter.RequestIdFilter
         │    - Generates or extracts `X-Request-Id` UUID for distributed tracing.
         │    - Injects security headers: X-Content-Type-Options: nosniff,
         │      X-Frame-Options: DENY, Referrer-Policy, Cache-Control: no-store.
         │    - Sets MDC logging context so every log line prints [requestId=...].
         ▼ 2. JwtAuthenticationFilter.doFilterInternal()
         │    - File: com.snovasys.ledgerline.security.JwtAuthenticationFilter
         │    - Extracts `Authorization: Bearer <token>`.
         │    - Invokes `jwtService.extractUsername(token)` and verifies HMAC-SHA256 signature.
         │    - Constructs `UserPrincipal(userId, username, email, role)` completely statelessly
         │      WITHOUT querying the database (preventing connection pool starvation).
         │    - Injects principal into Spring's `SecurityContextHolder`.
         ▼ 3. TransferController.transfer()
         │    - File: com.snovasys.ledgerline.controller.TransferController
         │    - Validates `@Valid TransferRequest` DTO (positive amount, valid UUIDs).
         │    - Reads `@RequestHeader("Idempotency-Key") String idempotencyKey`.
         │    - Delegates to `idempotencyService.executeIdempotent()`.
         ▼ 4. IdempotencyService.executeIdempotent()
         │    - File: com.snovasys.ledgerline.service.IdempotencyService
         │    - Computes SHA-256 hash of the JSON payload.
         │    - Single-Instance Barrier: Checks in-memory `inflightRequests` ConcurrentHashMap.
         │    - Cross-Instance Claim: Executes native SQL:
         │      INSERT INTO idempotency_keys (id, user_id, key, request_hash, response_status, response_body)
         │      VALUES (gen_random_uuid(), ?::uuid, ?, ?, 102, 'PROCESSING')
         │      ON CONFLICT (user_id, key) DO NOTHING RETURNING id;
         │    - If record already exists:
         │      - Compares SHA-256 payload hashes. If mismatch, throws 422 IDEMPOTENCY_PAYLOAD_MISMATCH.
         │      - If matching, returns cached response with header `Idempotent-Replay: true`.
         ▼ 5. LedgerService.transfer() [@Transactional]
         │    - File: com.snovasys.ledgerline.service.LedgerService
         │    - Role Separation: `validateRoleCanMoveMoney(actor)` verifies role is ACCOUNT_HOLDER.
         │      Rejects ADMIN and AUDITOR with HTTP 403 ACCESS_DENIED.
         │    - Self-Transfer Check: Rejects if sourceAccountId.equals(destinationAccountId).
         │    - Upper Limit Check: Rejects if amount > maxTransactionAmount.
         │    - Deterministic Deadlock-Free Pessimistic Locking:
         │      `lockAccountsInOrder(srcId, dstId)`:
         │      Compares UUIDs: `if (srcId.compareTo(dstId) < 0)` locks Source first, then Dest.
         │      Executes: `SELECT * FROM accounts WHERE id = ? FOR UPDATE`.
         │    - Zero-Leak Ownership Check: Verifies `sourceAcc.getOwnerId().equals(actor.getId())`.
         │      If mismatch: Throws `ResourceNotFoundException` (HTTP 404 NOT_FOUND, not 403).
         │    - Status Check: Verifies both accounts are `AccountStatus.ACTIVE`.
         │    - Balance Re-read under Lock & Exact Math:
         │      `if (sourceAcc.getBalance() < amount) throw new InsufficientFundsException(...)`
         │      `long newSourceBal = Math.subtractExact(sourceAcc.getBalance(), amount);`
         │      `long newDestBal = Math.addExact(destAcc.getBalance(), amount);`
         │    - Ledger Entries Creation:
         │      `ledgerEntryRepository.save(new LedgerEntry(sourceAcc.getId(), DEBIT, amount, newSourceBal, ...))`
         │      `ledgerEntryRepository.save(new LedgerEntry(destAcc.getId(), CREDIT, amount, newDestBal, ...))`
         │      `transactionRepository.save(new Transaction(...))`
         ▼ 6. AuditService.recordAction()
         │    - File: com.snovasys.ledgerline.service.AuditService
         │    - Writes SUCCESS audit row via direct `JdbcTemplate` autocommit insert (no lock coupling).
         ▼ 7. Transaction Commit & Database Triggers
         │    - PostgreSQL engine triggers verify append-only rules (no UPDATE/DELETE on ledgers).
         │    - Table constraint `chk_account_balance` verifies `balance >= 0`.
         │    - Row-level pessimistic locks are released.
         ▼ 8. Idempotency Completion
         │    - Updates `idempotency_keys` row with HTTP 200 and response JSON body.
         │    - Completes local `CompletableFuture` barrier to wake any waiting sibling threads.
         ▼ 9. HTTP Response
         │    - Returns HTTP 200 with `TransactionResponse` JSON body.
```

---

## Part 2: 20 Essential Interview Questions & Defences

### Q1: Why did you store money in minor units (paise) instead of standard BigDecimal or Double?
**Answer**: "Floating-point numbers (`double`/`float`) suffer from binary rounding errors because base-10 decimals cannot be represented exactly in IEEE 754 binary (e.g. `0.1 + 0.2 = 0.30000000000000004`). Over millions of transactions, rounding drift accumulates and corrupts global balance invariants. We store amounts in minor units (paise) as a `BIGINT` in PostgreSQL and `Long` in Java (₹1.00 = 100 paise). This guarantees discrete, exact integer arithmetic with zero floating-point imprecision. While `BigDecimal` is safe in application code, `BIGINT` in the database is significantly faster to index, guarantees hardware-level integer arithmetic, and mathematically forbids invalid fractional subunits (such as sub-paise fractions)."

---

### Q2: What happens if two users simultaneously transfer money to each other ($A \to B$ and $B \to A$)? How did you prevent deadlocks?
**Answer**: "A classic database deadlock occurs when Thread 1 locks Account A and attempts to lock Account B, while Thread 2 locks Account B and attempts to lock Account A (a circular wait condition). In `LedgerService.lockAccountsInOrder()`, I sort the two account IDs deterministically by their UUIDs (`idA.compareTo(idB) < 0`) and always acquire pessimistic row locks (`SELECT ... FOR UPDATE`) in strictly ascending order. Regardless of whether the transaction is $A \to B$ or $B \to A$, both threads will attempt to lock the smaller UUID first. Whichever thread reaches the smaller UUID first proceeds, while the second thread blocks predictably outside without creating a cycle. This mathematically proves zero deadlocks."

---

### Q3: How does your idempotency layer handle 20 identical requests hitting the server at the exact same millisecond?
**Answer**: "We implement a pre-execution atomic claim in PostgreSQL:
```sql
INSERT INTO idempotency_keys (id, user_id, key, request_hash, response_status, response_body)
VALUES (gen_random_uuid(), ?::uuid, ?, ?, 102, 'PROCESSING')
ON CONFLICT (user_id, key) DO NOTHING RETURNING id;
```
Because `(user_id, key)` is a database unique constraint, exactly one thread inserts the row and receives an ID. The other 19 threads fail the insert. On that server instance, the 19 threads join an in-memory `CompletableFuture` barrier. When the winning thread finishes and commits the transaction, it writes the result to `idempotency_keys` and completes the future. All 19 threads wake up and return the identical HTTP 200 response with header `Idempotent-Replay: true` without debiting the account a second time."

---

### Q4: What happens if the server crashes in the middle of a transfer after debiting Account A but before crediting Account B?
**Answer**: "Both debits, credits, ledger rows, and transaction records execute inside a single Spring `@Transactional` boundary managed by PostgreSQL ACID transactions. In relational databases, a transaction is atomic: either all operations commit, or none do. If the JVM crashes, the connection drops, or a runtime exception occurs before `COMMIT`, PostgreSQL's Write-Ahead Log (WAL) rolls back the entire transaction. Account A is not debited, Account B is not credited, and no orphan ledger entries exist. We prove this invariant in `LedgerEngineTest.forcedMidTransferFailureRollsBackFully`."

---

### Q5: How does the system handle retries when a mobile network drops before receiving the HTTP 200 response?
**Answer**: "Because mobile clients generate an `Idempotency-Key` (UUIDv4) prior to sending the request, when the connection drops, the client automatically retries with the *exact same* `Idempotency-Key` and payload. When the retry reaches the server, `IdempotencyService` finds the committed record in `idempotency_keys`, verifies that the SHA-256 payload hash matches, and immediately replays the original response with header `Idempotent-Replay: true`. The user is safely informed of success without paying twice."

---

### Q6: If we scale from 1 server instance to 10 horizontally clustered pods, what breaks and what holds?
**Answer**: 
- **What Holds (100% Safe)**:
  - Idempotency correctness: PostgreSQL's `UNIQUE(user_id, key)` constraint holds across all nodes worldwide. Only one pod can claim the key.
  - Money movement safety: Pessimistic row locking (`SELECT ... FOR UPDATE`) is enforced centrally by the PostgreSQL database engine across all pods.
  - Double-entry invariants and trigger protections hold universally.
- **What Breaks / Local Limitations**:
  - The in-memory `CompletableFuture` barrier is local to a single JVM instance. If two sibling requests with the exact same key hit Pod 1 and Pod 2 simultaneously, Pod 1 claims the key, but Pod 2 cannot wait on Pod 1's JVM future. Pod 2 will poll the database or receive a 409 conflict until Pod 1 commits.
  - The login rate limiter uses an in-memory `ConcurrentHashMap`. In a 10-pod cluster, a distributed cache like Redis (with Bucket4j) is required to aggregate failed login attempts across pods.

---

### Q7: Why did you return HTTP 404 NOT_FOUND instead of HTTP 403 FORBIDDEN when an account holder accesses another person's account?
**Answer**: "To prevent Account Enumeration attacks (existence probing). If an attacker tests random UUIDs:
- If unowned accounts return `403 FORBIDDEN`, and nonexistent accounts return `404 NOT_FOUND`, the attacker can distinguish between real and fake account IDs across the institution.
- By returning `404 NOT_FOUND` for both nonexistent accounts and accounts not owned by the caller, unauthorized probing reveals zero information. Only genuine role-based restrictions (e.g. an Auditor attempting to move money) return 403."

---

### Q8: Can an administrative user or DBA tamper with historical transactions to fix a discrepancy?
**Answer**: "No. We installed PostgreSQL database triggers attached `BEFORE UPDATE OR DELETE FOR EACH ROW` and `BEFORE TRUNCATE FOR EACH STATEMENT` on `ledger_entries` and `audit_log`. Even if a DBA or compromised admin account logs into `psql` and runs `UPDATE ledger_entries SET amount = ...` or `TRUNCATE ledger_entries;`, the PostgreSQL storage engine raises an uncatchable exception: `P0001: Table ledger_entries is append-only. UPDATE, DELETE, and TRUNCATE are prohibited.` Any correction must be entered as a new, balanced reversing transaction."

---

### Q9: What happens to the idempotency key if a transaction fails due to insufficient funds or validation errors?
**Answer**: "We implement Key Release on Business Failure. If an initial attempt fails due to insufficient funds (`InsufficientFundsException`), the key is deleted from `idempotency_keys`:
`idempotencyRepository.deleteByUserIdAndKey(userId, key);`
This ensures that the user can deposit funds and safely retry the transaction using the same `Idempotency-Key`. If we permanently cached the failure, the user would be blocked from retrying that payment until generating a new key."

---

### Q10: Why did you use `Math.addExact` and `Math.subtractExact` instead of the standard `+` and `-` operators?
**Answer**: "In Java, standard 64-bit integer arithmetic wraps around upon exceeding `Long.MAX_VALUE`. For example, `Long.MAX_VALUE + 1` silently produces `-9223372036854775808L`. An attacker submitting extreme amounts could potentially wrap an account balance into negative numbers or bypass overdraft checks. `Math.addExact` and `Math.subtractExact` throw an `ArithmeticException` if an overflow or underflow occurs, which `GlobalExceptionHandler` converts into an HTTP 400 Bad Request."

---

### Q11: Why does the system generate two ledger entries for deposits and withdrawals instead of just one?
**Answer**: "To preserve the universal double-entry conservation law: $\sum \text{ledger\_entries} = 0$. Deposits and withdrawals involve external cash outside the banking database. To balance the books internally, we clear external cash against the system clearing account (`SYS_CASH_CLEARING`). A deposit credits the customer account and debits `SYS_CASH_CLEARING`. A withdrawal debits the customer account and credits `SYS_CASH_CLEARING`. This guarantees that global net ledger balance is mathematically zero at all times."

---

### Q12: Why are negative balances checked in two different layers?
**Answer**: "Defense-in-depth. 
1. **Application Layer**: After acquiring the pessimistic lock, `LedgerService` re-reads the account balance and asserts `balance >= amount`, throwing `InsufficientFundsException` (HTTP 422).
2. **Database Engine Layer**: PostgreSQL enforces table check constraint `CONSTRAINT chk_account_balance CHECK (is_system = true OR balance >= 0)`.
Even if a developer accidentally commented out the service check or an asynchronous job bypassed the service, the PostgreSQL storage engine will abort the statement."

---

### Q13: How does the reconciliation engine detect balance tampering, and what exact SQL invariants does it verify?
**Answer**: "Our reconciliation service (`GET /api/admin/reconciliation`) executes two independent mathematical audits:
1. **Global Conservation Check**: Queries `SELECT SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END) FROM ledger_entries;`. This must equal exactly 0.
2. **Per-Account Invariant Check**: For every account, compares stored `accounts.balance` against `SELECT SUM(amount) FROM ledger_entries WHERE account_id = ?`. If an attacker directly alters the `balance` column via SQL, the stored balance will diverge from the sum of immutable ledger entries. Reconciliation reports `status: FAILED` and returns the exact account ID and discrepancy amount."

---

### Q14: How does the IP-based login rate limiter work, and what are its limitations behind corporate NAT?
**Answer**: "`LoginRateLimiter` tracks failed attempts per client IP using a 10-minute sliding window. We safely extract the leftmost IP from `X-Forwarded-For` to prevent reverse-proxy IP spoofing. After 5 consecutive failed attempts, the IP is locked out for 5 minutes, returning HTTP 429 `RATE_LIMIT_EXCEEDED`. A successful login clears the IP attempt counter.
*Limitation*: In corporate environments where hundreds of employees share a single egress NAT gateway or office WiFi, locking out the public IP can inadvertently lock out legitimate users. In enterprise production, we would combine IP rate limiting with username-based adaptive captcha."

---

### Q15: Why is the JWT stored in `sessionStorage` in the frontend, and does it protect against XSS?
**Answer**: "Honest assessment: `sessionStorage` does **NOT** protect against Cross-Site Scripting (XSS). Any malicious script injected via XSS can execute `sessionStorage.getItem('ledgerline_token')`. We chose `sessionStorage` over `localStorage` strictly for lifecycle scoping: `sessionStorage` is automatically destroyed when the user closes the browser tab, preventing credentials from lingering on public or shared terminals. In enterprise banking, the gold standard is `HttpOnly`, `SameSite=Strict`, `Secure` cookies with CSRF headers."

---

### Q16: Why was `spring.jpa.open-in-view` disabled, and what is connection pool starvation?
**Answer**: "Spring Boot enables `open-in-view: true` by default, binding a database connection from HikariCP to the thread for the entire HTTP request lifecycle, including JSON serialization and network response delivery. Under high concurrency (such as 20 threads waiting on an idempotency barrier), all 20 connections in the pool were held by waiting threads. The executing thread was starved of a connection, causing pool deadlocks. Disabling `open-in-view` ensures database connections are acquired only when entering `@Transactional` service methods and immediately returned to the pool upon method completion."

---

### Q17: Why did you use direct `JdbcTemplate` for `AuditService` instead of Spring Data JPA with `REQUIRES_NEW`?
**Answer**: "Using `@Transactional(propagation = Propagation.REQUIRES_NEW)` inside a method that already holds pessimistic row locks forces the thread to acquire a *second* connection from the Hikari pool. When 30 concurrent threads hit the system, all 30 pool connections are held by outer transactions, making it impossible for any thread to acquire the second connection for audit logging, deadlocking the connection pool. Additionally, if an exception marks the transaction as rollback-only, standard Spring proxies crash with `UnexpectedRollbackException`. Using `JdbcTemplate` executes an isolated autocommit insert in ~1ms without holding locks or interfering with transaction rollback states."

---

### Q18: What is the purpose of the `@Version` column on the `Account` entity?
**Answer**: "The `@Version` column provides an optimistic locking safety net. While money movements use pessimistic row locking (`SELECT ... FOR UPDATE`), the version column ensures that any non-locking read-modify-write operations (such as admin status changes, freezing, or profile edits) will detect concurrent collisions and fail with `ObjectOptimisticLockingFailureException` rather than silently overwriting state."

---

### Q19: If an attacker alters the JWT payload or specifies `alg=none`, how does the backend reject it?
**Answer**: "Our `JwtService` verifies tokens using JJWT's strict HMAC-SHA256 parser (`Jwts.parser().verifyWith(secretKey).build()`). If an attacker alters the payload, the cryptographic signature check fails. If an attacker specifies `alg=none`, the JJWT parser rejects unsigned tokens by default. If the token is expired, `ExpiredJwtException` is thrown. All these cases return HTTP 401 UNAUTHORIZED."

---

### Q20: If you had another 2 weeks to work on LedgerLine, what top 3 architectural improvements would you prioritize?
**Answer**: 
1. **Distributed Coordination via Redis**: Replace the single-instance in-memory `CompletableFuture` barrier and IP rate limiter with Redis (Redisson distributed locks and Bucket4j token bucket) to provide identical zero-polling barrier semantics across a multi-node horizontal Kubernetes cluster.
2. **Automated Scheduled Reconciliation & PagerDuty Alerting**: Configure a `@Scheduled(cron = "0 0 * * * *")` hourly reconciliation job that runs the double-entry audit and automatically fires alerts to PagerDuty/Slack if any balance discrepancy is detected.
3. **Multi-Currency FX Ledger Engine**: Expand the single-currency INR model to support multi-currency accounts with real-time exchange rates, spread fees, and double-entry currency clearing accounts."
