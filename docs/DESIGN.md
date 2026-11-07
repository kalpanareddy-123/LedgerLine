# LedgerLine - Architectural & System Design Decisions

This document details the architectural decisions, domain invariants, and threat models implemented in **LedgerLine**, explaining **why** each choice was made and linking them directly to the codebase.

---

## 1. Minor Units (Paise) as BIGINT

### The Problem with Floats/Doubles in Finance
In IEEE 754 floating-point arithmetic (used by `float` and `double` in Java and Postgres), numbers like `0.1` cannot be represented exactly in binary:
```java
double a = 0.1;
double b = 0.2;
System.out.println(a + b); // Prints 0.30000000000000004 !
```
Over millions of transactions, binary rounding errors cause books to fall out of balance, violating the fundamental accounting invariant.

### Why BIGINT Paise Was Chosen
- **Minor Units (`Long` in Java, `BIGINT` in PostgreSQL)**: All monetary amounts represent the smallest indivisible unit of currency: **1 INR = 100 paise**.
- Storing `₹150.75` as `15075L` paise guarantees exact discrete arithmetic.
- Eliminates rounding drift, truncation anomalies, and floating-point comparison bugs.
- Fixed currency constraint in database: `currency VARCHAR(3) CHECK (currency = 'INR')`.

*Code Reference*: [V1__init_schema.sql](file:///Users/harsha/Desktop/kalpana/backend/ledgerline/src/main/resources/db/migration/V1__init_schema.sql), [Account.java](file:///Users/harsha/Desktop/kalpana/backend/ledgerline/src/main/java/com/snovasys/ledgerline/domain/Account.java).

---

## 2. Double-Entry Accounting Invariant

### Principle: "Money Never Appears from Nowhere or Disappears"
In single-entry systems, depositing money simply does `balance = balance + amount`. If a system crashes mid-way or bugs corrupt state, there is no mathematical proof of what went wrong.

### Why Double-Entry Was Implemented
In LedgerLine:
1. Every monetary transaction creates **two or more ledger entries** (at least one `DEBIT` and at least one `CREDIT`).
2. `CREDIT` represents an inflow (positive), `DEBIT` represents an outflow (negative).
3. The sum of entries in any transaction is strictly zero:
   $$\sum \text{LedgerEntries} = 0$$
4. Across all accounts in the entire system, the total net ledger balance must always equal zero at all times:
   $$\sum_{i=1}^{N} \text{AccountBalance}_i = 0$$

### The `SYS_CASH_CLEARING` System Account
External cash deposits and withdrawals have an external counterparty. To preserve the zero-sum invariant:
- Deposits: `SYS_CASH_CLEARING` is **DEBITED**, User Account is **CREDITED**.
- Withdrawals: User Account is **DEBITED**, `SYS_CASH_CLEARING` is **CREDITED**.
- Only `SYS_CASH_CLEARING` is permitted to carry a negative balance. Non-system user accounts are strictly constrained:
  ```sql
  CONSTRAINT chk_account_balance CHECK (is_system = true OR balance >= 0)
  ```

*Code Reference*: [LedgerService.java](file:///Users/harsha/Desktop/kalpana/backend/ledgerline/src/main/java/com/snovasys/ledgerline/service/LedgerService.java), [ReconciliationService.java](file:///Users/harsha/Desktop/kalpana/backend/ledgerline/src/main/java/com/snovasys/ledgerline/service/ReconciliationService.java).

---

## 3. Concurrency Safety & Deadlock Prevention

### The Problem: Circular Wait Deadlocks in Transfers
Consider two concurrent transfers occurring at the same millisecond:
- **Thread 1**: Transfers money from Account A to Account B. (Acquires lock on A, attempts to lock B).
- **Thread 2**: Transfers money from Account B to Account A. (Acquires lock on B, attempts to lock A).
In PostgreSQL, this causes a fatal cycle: Thread 1 waits for B while holding A, and Thread 2 waits for A while holding B. PostgreSQL's deadlock detector aborts one transaction with `40P01: deadlock detected`.

### The Solution: Deterministic Ascending Lock Ordering
LedgerLine completely eliminates circular wait deadlocks mathematically:
```java
// Sort account IDs deterministically by UUID compareTo
Account first = (srcId.compareTo(dstId) < 0) 
    ? lockAccount(srcId) : lockAccount(dstId);
Account second = (first.getId().equals(srcId)) 
    ? lockAccount(dstId) : lockAccount(srcId);
```
Because all threads worldwide acquire locks in the exact same ordinal order, a circular wait condition is impossible. Both Thread 1 and Thread 2 will attempt to lock A first. Whichever reaches A first proceeds; the second waits predictably until the first finishes.

### Double-Layer Balance Protection
1. **Layer 1 (Application)**: Re-check `sourceAcc.getBalance() >= amount` *inside* the transaction **after** acquiring the pessimistic write lock (`SELECT ... FOR UPDATE`).
2. **Layer 2 (Database Constraint)**: PostgreSQL engine check constraint `balance >= 0`. Even if application code had a bug, the database rejects any statement that would cause an overdraft.
3. **Safety Net**: Optimistic locking `@Version` column on `Account` guards against out-of-band updates.

*Code Reference*: [LedgerService.java:lockAccountsInOrder](file:///Users/harsha/Desktop/kalpana/backend/ledgerline/src/main/java/com/snovasys/ledgerline/service/LedgerService.java#L280-L315), [AccountRepository.java:findByIdForUpdate](file:///Users/harsha/Desktop/kalpana/backend/ledgerline/src/main/java/com/snovasys/ledgerline/repository/AccountRepository.java#L20-L24).

---

## 4. Database-Backed Idempotency Protocol

### The Problem: Network Timeouts & Replay Duplication
A mobile client submits a ₹5,000 transfer. The server processes the transfer, debits the account, but the cellular network drops before the HTTP 200 response reaches the phone. The client retries. Without idempotency, the user pays twice.

### Why Pre-Execution Atomic Claim Was Implemented
Naive implementations check for existing records, process the transaction, and insert the record afterwards. Under 20 parallel threads, all 20 threads read "no record exists" simultaneously and execute 20 debits.

LedgerLine implements a **3-stage atomic protocol**:
1. **Payload Hash Check**: The incoming JSON request body is hashed via SHA-256 (`request_hash`).
2. **Atomic Pre-Execution Claim**:
   ```sql
   INSERT INTO idempotency_keys (id, user_id, key, request_hash, response_status, response_body, created_at)
   VALUES (gen_random_uuid(), ?::uuid, ?, ?, 102, 'PROCESSING', CURRENT_TIMESTAMP)
   ON CONFLICT (user_id, key) DO NOTHING RETURNING id;
   ```
   - Exactly one thread inserts the record and receives an `id`.
   - Any concurrent sibling thread fails the insert due to `UNIQUE (user_id, key)`.
   - **Concurrency Barrier Architecture**: The in-memory `CompletableFuture` barrier is an optimization that works on **one server instance only** to avoid spinning. The real cross-instance guarantee across horizontal server clusters is PostgreSQL's `UNIQUE(user_id, key)` constraint with `ON CONFLICT DO NOTHING`.
3. **Response Replay**:
   - Same key + identical payload hash: Returns original response with header `Idempotent-Replay: true`.
   - Same key + different payload hash: Rejects immediately with **422 IDEMPOTENCY_PAYLOAD_MISMATCH**.
   - If the first execution fails due to a domain error (e.g. insufficient funds or validation failure), the key is deleted from `idempotency_keys` so the user can safely retry once funded.

*Code Reference*: [IdempotencyService.java](file:///Users/harsha/Desktop/kalpana/backend/ledgerline/src/main/java/com/snovasys/ledgerline/service/IdempotencyService.java).

---

## 5. Append-Only Database Triggers

### Principle: "Audit Trails and Ledgers Cannot Be Rewritten"
In financial systems, corrupt or rogue actors must not be able to execute `UPDATE ledger_entries` or `DELETE FROM audit_log` to cover money laundering or embezzlement. Even developers or DBAs running manual queries must be prevented from tampering.

### Why Database Triggers Were Chosen Over Application-Level Restrictions
Application-level checks can be bypassed by connecting directly via `psql`.
We installed PostgreSQL trigger functions attached `BEFORE UPDATE OR DELETE`:
```sql
CREATE OR REPLACE FUNCTION prevent_update_or_delete()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Table % is strictly append-only. UPDATE and DELETE are prohibited.', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_append_only
BEFORE UPDATE OR DELETE ON ledger_entries
FOR EACH ROW EXECUTE FUNCTION prevent_update_or_delete();

CREATE TRIGGER trg_audit_log_append_only
BEFORE UPDATE OR DELETE ON audit_log
FOR EACH ROW EXECUTE FUNCTION prevent_update_or_delete();
```
Corrections must always be made by recording new reversing transactions, never by altering historical entries.

*Code Reference*: [V1__init_schema.sql](file:///Users/harsha/Desktop/kalpana/backend/ledgerline/src/main/resources/db/migration/V1__init_schema.sql#L85-L105).

---

## 6. Authorization: 404 NOT_FOUND vs 403 FORBIDDEN

### Threat Model: Account Existence Enumeration
If an attacker tests account UUIDs:
- If an existing account belonging to someone else returns `403 FORBIDDEN`
- And an invalid account returns `404 NOT_FOUND`
The attacker can iterate through UUIDs or account numbers to discover valid account IDs across the bank (Account Probing).

### Why 404 Was Chosen for Ownership Violations
When an `ACCOUNT_HOLDER` attempts to inspect, deposit, or withdraw from an account they do not own, the service intentionally returns:
```json
{
  "status": 404,
  "code": "RESOURCE_NOT_FOUND",
  "message": "Account not found with ID: ..."
}
```
The existence of other customers' accounts is completely concealed.
Only genuine authorization barriers (e.g. an `AUDITOR` attempting to execute a transfer, or an `ACCOUNT_HOLDER` accessing `/api/admin/*`) return `403 ACCESS_DENIED`.

*Code Reference*: [LedgerEngineTest.java:ownershipProtectionOnAllAccountEndpoints](file:///Users/harsha/Desktop/kalpana/backend/ledgerline/src/test/java/com/snovasys/ledgerline/LedgerEngineTest.java).

---

## 7. Statement-Level TRUNCATE Protection

### The Problem: TRUNCATE Bypasses Row-Level Triggers
PostgreSQL's `TRUNCATE` is a DDL-level bulk operation that empties a table by unlinking disk pages rather than scanning and deleting rows individually. Row-level triggers (`FOR EACH ROW`) are completely ignored during `TRUNCATE`.

### The Solution: Statement-Level Triggers & Privilege Revocation
Flyway migration `V2__block_truncate.sql` attaches statement-level triggers to `ledger_entries` and `audit_log`:
```sql
CREATE OR REPLACE FUNCTION prevent_truncate()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Table % is append-only. TRUNCATE is strictly prohibited.', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_prevent_truncate
BEFORE TRUNCATE ON ledger_entries
FOR EACH STATEMENT EXECUTE FUNCTION prevent_truncate();

CREATE TRIGGER trg_audit_log_prevent_truncate
BEFORE TRUNCATE ON audit_log
FOR EACH STATEMENT EXECUTE FUNCTION prevent_truncate();

REVOKE TRUNCATE ON ledger_entries FROM PUBLIC;
REVOKE TRUNCATE ON audit_log FROM PUBLIC;
```
Even an operator connecting directly via `psql` executing `TRUNCATE ledger_entries;` will receive an uncatchable PostgreSQL exception `P0001: Table ledger_entries is append-only. TRUNCATE is strictly prohibited.`

---

## 8. Token Architecture & Honest Storage Assessment

### Stateless JWT Bearer Auth
LedgerLine issues HMAC-SHA256 (HS256) signed JSON Web Tokens upon authentication:
- Claims: `sub` (username), `userId` (UUID), `role` (`ADMIN`, `AUDITOR`, `ACCOUNT_HOLDER`), `iat`, and `exp`.
- Filter layer: `JwtAuthenticationFilter` validates token signature and claims statelessly without database hits, preventing DB connection pool starvation during high-concurrency requests.

### Honest Assessment of sessionStorage
The React frontend stores the JWT in browser `sessionStorage`:
- **Advantage**: Unlike `localStorage`, `sessionStorage` is tied strictly to the browser tab lifecycle and is immediately purged upon closing the tab, preventing residual tokens on shared computers.
- **Honest Security Reality**: `sessionStorage` does **NOT** defend against Cross-Site Scripting (XSS). Any malicious script injected into the DOM can execute `sessionStorage.getItem('ledgerline_token')` and exfiltrate the token.
- **Enterprise Recommendation**: In enterprise banking environments with dedicated domains, authentication should utilize `HttpOnly`, `SameSite=Strict`, `Secure` cookies paired with custom CSRF header validation.

---

## 9. Arithmetic Overflow Protection via Exact Math

### Threat: Long Overflow Wrap-Around
In Java, 64-bit signed integers wrap around to negative numbers when exceeding `Long.MAX_VALUE`:
```java
long val = Long.MAX_VALUE;
val = val + 1; // Wraps to -9223372036854775808L!
```
If an attacker supplied extreme monetary amounts, standard arithmetic could wrap a massive credit into an accidental debit or bypass balance checks.

### The Solution: `Math.addExact` and `Math.subtractExact`
Every balance calculation in `LedgerService` uses Java's exact math primitives:
```java
long newBalance = Math.addExact(account.getBalance(), amount);
long debitedBalance = Math.subtractExact(account.getBalance(), amount);
```
If an arithmetic overflow or underflow occurs, Java immediately throws an `ArithmeticException` which is intercepted by `GlobalExceptionHandler` and mapped to HTTP `400 BAD_REQUEST: Arithmetic overflow in monetary calculation`.

---

## 10. Idempotency Failure Semantics: Key Release on Domain Errors

### The Dilemma: What Happens When a Request Fails?
Suppose Alice submits a transfer of ₹5,000 with `Idempotency-Key: X`. Her account only has ₹1,000, so the request fails with `422 INSUFFICIENT_FUNDS`.
- If key `X` was permanently locked, Alice could never retry the transaction after depositing funds without generating a new UUID.
- If key `X` had no concurrency control, duplicate requests could still race.

### The Solution: Key Release on Business Failure
1. **Pre-Execution Claim**: When key `X` arrives, it is inserted with status `102 PROCESSING`.
2. **Success Path**: On successful commit, the status is updated to `200 OK` (or `201`) with the response payload cached for subsequent identical replays.
3. **Business Error Path**: If a domain exception (`InsufficientFundsException`, `ValidationException`) is thrown:
   ```java
   idempotencyRepository.deleteByUserIdAndKey(userId, key);
   ```
   The key is immediately deleted from `idempotency_keys`. Once Alice deposits funds, she or the automated client can safely re-submit with the exact same `Idempotency-Key` and succeed.


*Code Reference*: [AccountService.java:getAccountWithOwnershipCheck](file:///Users/harsha/Desktop/kalpana/backend/ledgerline/src/main/java/com/snovasys/ledgerline/service/AccountService.java#L80-L95).
