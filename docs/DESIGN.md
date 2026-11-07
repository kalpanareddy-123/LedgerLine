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

## 4. Distributed Idempotency Protocol

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
   - Any concurrent sibling thread fails the insert due to `UNIQUE (user_id, key)` and waits on an in-memory `CompletableFuture` barrier.
3. **Response Replay**:
   - Same key + identical payload hash: Returns original response with header `Idempotent-Replay: true`.
   - Same key + different payload hash: Rejects immediately with **422 IDEMPOTENCY_PAYLOAD_MISMATCH**.

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

*Code Reference*: [AccountService.java:getAccountWithOwnershipCheck](file:///Users/harsha/Desktop/kalpana/backend/ledgerline/src/main/java/com/snovasys/ledgerline/service/AccountService.java#L80-L95).
