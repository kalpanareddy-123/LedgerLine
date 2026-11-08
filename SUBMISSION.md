# Snovasys Hiring Challenge - Project Submission

**Project Title**: LedgerLine — Secure Double-Entry Financial Accounting & Money Movement Engine  
**One-Line Description**: A high-concurrency, double-entry banking system where books mathematically balance to zero and idempotent requests never pay twice.  
**Deadline**: Thursday, 12 Nov 2026, 6:00 PM IST  

---

## 1. Project Links

- **GitHub Repository**: https://github.com/kalpanareddy-123/LedgerLine *(Maintained private during development; set to public or invited evaluators at deadline)*
- **Live Web Application**: [FRONTEND_URL] *(e.g. `https://ledgerline-frontend.vercel.app`)*
- **Interactive Swagger API Documentation**: [BACKEND_URL]/swagger-ui.html *(e.g. `https://ledgerline-backend.onrender.com/swagger-ui.html`)*
- **Core Health Endpoint**: [BACKEND_URL]/api/health

> [!NOTE]
> **Free-Tier Cold Start Notice**: The backend is hosted on Render's free tier, which enters sleep mode after 15 minutes of inactivity. The initial wake-up request takes **30–60 seconds**. Please allow 1 minute for the initial spin-up or test the live URL after hitting `/api/health`.

---

## 2. Demo Credentials (**DEMO-ONLY**)

Quick-fill demo buttons are provided on the login page for instantaneous evaluation:

| Role | Username | Password | Purpose |
| :--- | :--- | :--- | :--- |
| **Account Holder** | `alice` | `AlicePass123!` | Primary customer with pre-funded balance for deposits & transfers |
| **Account Holder** | `bob` | `BobPass123!` | Destination customer for peer-to-peer transfers |
| **System Admin** | `admin` | `AdminPass123!` | Account lifecycle (freeze/close) and financial reconciliation audits |
| **Compliance Auditor** | `auditor` | `AuditorPass123!` | Read-only compliance access to statements and immutable audit logs |

---

## 3. What I Built (Verified & Tested Invariants)

I built a production-grade core banking engine adhering to strict financial guarantees:

1. **Exact Integer Arithmetic (Paise)**: All money is stored and computed strictly as minor units (`BIGINT` paise in PostgreSQL, `Long` in Java; ₹1 = 100 paise) avoiding floating-point binary rounding drift. All calculations use `Math.addExact` and `Math.subtractExact` to catch potential integer overflows.
2. **Double-Entry Bookkeeping**: Every transaction creates balanced `DEBIT` and `CREDIT` entries. External deposits and withdrawals clear against a system clearing account (`SYS_CASH_CLEARING`). The sum of entries for any operation is strictly zero, and the global ledger sum across all accounts equals zero at all times.
3. **Deadlock-Free Pessimistic Locking**: When transferring funds between two accounts, row locks (`SELECT ... FOR UPDATE`) are acquired in deterministic ascending UUID order (`idA.compareTo(idB) < 0`), eliminating circular lock wait conditions. Tested with 40 parallel opposite transfers ($A \to B$ and $B \to A$) with zero deadlocks.
4. **Database-Backed Idempotency**: State-changing endpoints require an `Idempotency-Key` header. Requests claim the key atomically via PostgreSQL `INSERT ... ON CONFLICT (user_id, key) DO NOTHING`. Sibling concurrent requests wait on an in-memory barrier and receive the replayed response with `Idempotent-Replay: true`. If a business failure occurs (e.g. insufficient funds), the key is released so the user can safely retry once funded.
5. **Append-Only Database Immutability**: Row-level database triggers intercept and block raw SQL `UPDATE` and `DELETE` on `ledger_entries` and `audit_log`. Statement-level triggers block `TRUNCATE`. Corrections must be new reversing entries.
6. **Zero-Leak Authorization**: Non-owned accounts return HTTP 404 NOT_FOUND (rather than 403) across all endpoints (read, deposit, withdraw, transfer, statement, CSV export), preventing account ID existence probing.
7. **Strict Separation of Duties**: Admin accounts manage lifecycle (freeze/unfreeze/close) but are barred from moving funds (HTTP 403). Auditors hold read-only compliance access.
8. **Automated Accounting Reconciliation**: An administrative endpoint mathematically compares each account's cached balance against the sum of its immutable ledger entries, and asserts $\sum \text{all entries} = 0$.

---

## 4. How to Run Locally

### Using Docker Compose:
```bash
git clone [REPO_URL]
cd ledgerline
cp .env.example .env
docker compose up -d --build
```
Access Frontend at `http://localhost:3000`, Backend at `http://localhost:8080`, Swagger at `http://localhost:8080/swagger-ui.html`.

### Using Native Windows CMD:
```cmd
cd backend\ledgerline
set DB_URL=jdbc:postgresql://localhost:5432/ledgerline_db
set DB_USER=ledgerline_app
set DB_PASSWORD=secret
set JWT_SECRET=v9y$B&E)H@MbQeThWmZq4t7w!z%C*F-JaNdRfUjXn2r5u8x/A?D(G+KbPeShVkYp
set DEMO_SEED=true
mvnw.cmd spring-boot:run
```
In another window:
```cmd
cd frontend
npm install
npm run dev
```

### Running the Automated Test Suite (57 Tests):
```bash
cd backend/ledgerline
./mvnw test
```

---

## 5. AI Tools Used & Engineering Attribution

In compliance with challenge transparency guidelines:
- **ChatGPT**: Used for initial brainstorming, high-level user story decomposition, and draft architecture diagrams.
- **Antigravity**: Used as pair-programming agent for code generation, test scaffolding, and continuous refactoring.

### What the AI Got Wrong and What I Fixed (Real Engineering Log from PROMPTS.md):
1. **TRUNCATE Trigger Mechanics**: AI suggested using `FOR EACH ROW` triggers for `TRUNCATE` operations on `ledger_entries`. PostgreSQL rejected this because `TRUNCATE` does not scan rows. I researched PostgreSQL trigger specifications and created Flyway migration `V2__block_truncate.sql` with a dedicated `BEFORE TRUNCATE ... FOR EACH STATEMENT` trigger and revoked TRUNCATE privileges from public.
2. **Admin Moving Money (Separation of Duties Violation)**: Early generated test fixtures used the `ADMIN` persona to perform initial customer deposits. I recognized that in real banking core systems, admins manage user lifecycles but must never touch balances. I refactored the domain service to enforce that only `ACCOUNT_HOLDER` principals can deposit, withdraw, or transfer, and updated all test fixtures to use account holders.
3. **Currency Check Constraint vs Multi-Currency Setup**: When writing currency validation tests, AI attempted to save an account with currency `USD` to test cross-currency transfer rejection. The database threw `DataIntegrityViolationException` because the initial schema migration strictly enforced `CHECK (currency = 'INR')`. I corrected the test to assert the database constraint directly and enforce currency matching in the domain service.
4. **Idempotency Race on Post-Execution Inserts**: AI initially proposed checking for existing idempotency records, moving money, and inserting the record post-transaction. Under 20 concurrent threads, all 20 threads read "no record exists" simultaneously, causing 19 duplicate payouts. I redesigned the architecture to execute an atomic pre-execution claim using PostgreSQL `INSERT ... ON CONFLICT DO NOTHING`, guaranteeing exactly one execution.
5. **Connection Pool Deadlock from REQUIRES_NEW Auditing**: AI used `@Transactional(propagation = Propagation.REQUIRES_NEW)` for audit logging inside transactions holding row locks. Under high concurrency, 30 threads each held an outer connection, starving the pool when attempting to acquire a second connection for audit logging. I rewrote `AuditService` to use direct `JdbcTemplate` autocommit inserts, eliminating connection deadlocks.

---

## 6. What is Not Finished / Honest Limitations

1. **Fixed Currency ("INR")**: The system strictly processes INR paise. Multi-currency exchange rates and foreign exchange (FX) spread calculations are not implemented.
2. **Single-Instance In-Memory Barrier vs Multi-Node Scaling**: The in-memory `CompletableFuture` barrier is an optimization for a single JVM instance. Across multiple Kubernetes pods, the true concurrency guarantee is PostgreSQL's `UNIQUE(user_id, key)` with `ON CONFLICT DO NOTHING`.
3. **In-Memory Rate Limiting**: The brute-force login limiter tracks failed attempts per IP in an in-memory `ConcurrentHashMap`. In a horizontally clustered deployment, an external Redis cache with Bucket4j would be used.
4. **Per-IP Lockout Behind Shared NAT**: Locking out by IP address can inadvertently impact users sharing a single corporate WiFi or egress proxy.
5. **Token Storage**: The JWT is stored in `sessionStorage`. While this ensures tokens are cleared upon closing the tab, it does **not** protect against XSS.

---

## 7. Demo Video Shot List (2–3 Minutes Walkthrough)

- **[0:00 - 0:30] Introduction & Architecture**:
  - Show project running live on Vercel frontend and Render backend.
  - Explain the core invariant: exact integer arithmetic in paise (`BIGINT`) with double-entry counterparty clearing.
- **[0:30 - 1:00] Customer Alice Flow (Deposit & Idempotency)**:
  - Log in as `alice` using quick-fill button.
  - Deposit ₹500.00. Show real-time balance update.
  - Open Network Tab, show `Idempotency-Key` header.
  - Re-send exact same deposit with the same key; demonstrate instant HTTP 200 replay with header `Idempotent-Replay: true` and zero balance duplication.
- **[1:00 - 1:30] Peer-to-Peer Transfer & Deadlock Prevention**:
  - Transfer ₹250.00 from Alice to Bob (`ACC-BOB-01`).
  - Explain deterministic lock ordering: locks are acquired in ascending UUID order to mathematically eliminate deadlocks.
- **[1:30 - 2:00] Admin Dashboard & Reconciliation**:
  - Log in as `admin`.
  - Click **Run Reconciliation Audit**. Show that total ledger sum is ₹0.00 and all account balances match ledger sums.
- **[2:00 - 2:30] Immutability & Conclusion**:
  - Explain database triggers blocking `UPDATE`, `DELETE`, and `TRUNCATE` on `ledger_entries`.
  - Highlight 57/57 automated tests passing without sleeps.
