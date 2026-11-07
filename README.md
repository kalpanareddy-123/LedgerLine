# LedgerLine - Secure Double-Entry Financial Engine

> **Snovasys Fresher Hiring Challenge**  
> **Challenge Goal**: *"Move money so the books always balance and the same request never pays twice."*  
> **Target Evaluation Date**: Thursday, 12 Nov 2026, 6:00 PM IST  
> **Status**: Production-Ready, **57/57 Automated Tests Passing (Zero Flakiness)**  

---

## 1. Overview & Architecture

**LedgerLine** is an enterprise-grade double-entry financial accounting and money-movement engine built with Spring Boot 3, Java 21 LTS, and PostgreSQL, paired with a React single-page financial dashboard.

```
       [Client: React Web (Vercel) / Mobile / API Consumer]
                               │
                     HTTPS + JWT Bearer Auth + Idempotency-Key
                               ▼
       ┌────────────────────────────────────────────────────────┐
       │                Spring Boot 3 Web Layer                 │
       │   - RequestIdFilter (Distributed Tracing UUID)         │
       │   - Security Headers (nosniff, DENY, no-store cache)   │
       │   - JwtAuthenticationFilter (Stateless Claims Auth)    │
       │   - GlobalExceptionHandler (Standardized RFC Errors)   │
       │   - LoginRateLimiter (Per-IP Sliding Window Lockout)   │
       └────────────────────────┬───────────────────────────────┘
                                │
                                ▼
       ┌────────────────────────────────────────────────────────┐
       │          Database-Backed Idempotency Engine            │
       │   - SHA-256 Request Payload Hash Verification          │
       │   - Atomic PostgreSQL INSERT ... ON CONFLICT Claim     │
       │   - Single-Instance In-Memory CompletableFuture Barrier│
       │   - Key Released on Business Failure (Safe Retries)    │
       └────────────────────────┬───────────────────────────────┘
                                │
                                ▼
       ┌────────────────────────────────────────────────────────┐
       │               Double-Entry Ledger Engine               │
       │   - Minor Units (All money stored as BIGINT paise)     │
       │   - Exact Math (Math.addExact / subtractExact overflow)│
       │   - Lexicographic Lock Ordering (Ascending UUIDs)      │
       │   - Double-Entry Invariant (SUM of entries = 0)        │
       │   - Counterparty Clearing (SYS_CASH_CLEARING)          │
       │   - Role Separation (Admin/Auditor cannot move money)  │
       └────────────────────────┬───────────────────────────────┘
                                │
                                ▼
       ┌────────────────────────────────────────────────────────┐
       │                 PostgreSQL 16 Database                 │
       │   - Row Triggers: BEFORE UPDATE OR DELETE FOR EACH ROW │
       │   - Statement Triggers: BEFORE TRUNCATE STATEMENT      │
       │   - CHECK (is_system = true OR balance >= 0)           │
       │   - CHECK (currency = 'INR')                           │
       │   - Flyway Schema Migrations (V1 init, V2 truncate)    │
       └────────────────────────────────────────────────────────┘
```

---

## 2. Core Domain Rules

1. **Exact Integer Arithmetic (Paise)**: Money is strictly stored as minor units (`BIGINT` paise in PostgreSQL, `Long` in Java). Floats and doubles are forbidden. Currency is fixed to `"INR"`. Overflow is defended via `Math.addExact` and `Math.subtractExact`.
2. **Double-Entry Invariant**: Every deposit, withdrawal, and transfer produces balanced `DEBIT` and `CREDIT` entries where $\sum \text{entries} = 0$.
3. **Counterparty Clearing**: Deposits and withdrawals clear against the `SYS_CASH_CLEARING` system account. Non-system accounts are barred from negative balances at both service and database constraint levels.
4. **Append-Only Immutability**: PostgreSQL database triggers raise an uncatchable exception on any `UPDATE`, `DELETE`, or `TRUNCATE` attempt on `ledger_entries` or `audit_log`. Corrections must be new reversing entries.
5. **Deadlock-Free Pessimistic Locking**: Concurrent multi-account transfers acquire row-level locks (`SELECT ... FOR UPDATE`) in deterministic ascending UUID order.
6. **Database-Backed Idempotency**: State-changing endpoints require an `Idempotency-Key` header (UUID). The real cross-instance guarantee is PostgreSQL's `UNIQUE(user_id, key)` with `ON CONFLICT DO NOTHING`, paired with a single-instance in-memory `CompletableFuture` barrier. Replaying the identical payload returns the cached result with `Idempotent-Replay: true`. Sending a different payload with the same key returns HTTP 422. Failed transactions delete/release the key for safe user retries.
7. **Zero-Leak Authorization**: Account holders receive `404 NOT_FOUND` (not 403) when requesting accounts they do not own, preventing account enumeration probing.
8. **Separation of Duties**: System Admins manage users and account statuses (active, frozen, closed) but are strictly barred from initiating money movements (HTTP 403 `ACCESS_DENIED`). Auditors hold read-only access. Only `ACCOUNT_HOLDER` principals can move funds.

---

## 3. Technology Stack

- **Backend**: Java 21 LTS, Spring Boot 3.3.5, Spring Data JPA, Spring Security (Stateless JWT), Flyway, PostgreSQL JDBC Driver, Bean Validation, springdoc-openapi 2.6.0 (Swagger UI).
- **Frontend**: React 18, Vite 5, React Router 6, Plain Vanilla CSS (Custom dark-mode financial design system, 360px mobile responsive).
- **Database**: PostgreSQL 16.
- **Testing**: JUnit 5, AssertJ, Spring MockMvc, Multi-threaded Concurrency Harness (Latches only, zero sleeps).

---

## 4. How to Run Locally

### Option A: Docker Compose (Recommended)

```cmd
:: 1. Copy environment template
copy .env.example .env

:: 2. Start PostgreSQL, Backend, and Frontend containers
docker compose up -d --build

:: 3. Access the services:
:: Frontend:  http://localhost:3000
:: Backend:   http://localhost:8080/api/health
:: Swagger:   http://localhost:8080/swagger-ui.html
```

---

### Option B: Native Windows CMD

#### Prerequisites
- Java 21 LTS (`java -version` returns Java 21)
- Node.js 20+ and npm
- PostgreSQL 16 running on port 5432

#### 1. Setup Database
Open Windows CMD and create the application database and isolated test database:
```cmd
psql -U postgres -c "CREATE USER ledgerline_app WITH PASSWORD 'your_secure_db_password';"
psql -U postgres -c "CREATE DATABASE ledgerline_db OWNER ledgerline_app;"
psql -U postgres -c "CREATE DATABASE ledgerline_test_db OWNER ledgerline_app;"
psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE ledgerline_db TO ledgerline_app;"
psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE ledgerline_test_db TO ledgerline_app;"
```

#### 2. Configure Environment Variables
Set the environment variables in your CMD session (never commit real passwords):
```cmd
set DB_URL=jdbc:postgresql://localhost:5432/ledgerline_db
set DB_USER=ledgerline_app
set DB_PASSWORD=your_secure_db_password
set JWT_SECRET=v9y$B&E)H@MbQeThWmZq4t7w!z%C*F-JaNdRfUjXn2r5u8x/A?D(G+KbPeShVkYp
set JWT_EXPIRATION_MINUTES=30
set PORT=8080
set CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:3000
set DEMO_SEED=true
set MAX_TRANSACTION_AMOUNT=10000000
```

#### 3. Run Backend
```cmd
cd backend\ledgerline
mvnw.cmd spring-boot:run
```
*Flyway will automatically execute migrations `V1__init_schema.sql` and `V2__block_truncate.sql` and seed default demo accounts.*

#### 4. Run Frontend
In a new CMD window:
```cmd
cd frontend
npm install
npm run dev
```
Open [http://localhost:5173](http://localhost:5173) in your browser.

---

## 5. Environment Variables Reference

| Variable Name | Description | Default / Example | Required Outside Dev |
| :--- | :--- | :--- | :--- |
| `DB_URL` | JDBC URL for PostgreSQL with SSL support | `jdbc:postgresql://localhost:5432/ledgerline_db` | Yes |
| `DB_USER` | PostgreSQL user | `ledgerline_app` | Yes |
| `DB_PASSWORD` | PostgreSQL password | *Read from env (no default in repo)* | **Yes (Fails if missing)** |
| `JWT_SECRET` | Secret key for signing HMAC-SHA256 tokens | *Min 32 bytes (256 bits)* | **Yes (Fails if missing/short)** |
| `JWT_EXPIRATION_MINUTES` | Token validity in minutes | `30` | No |
| `PORT` | Backend HTTP listening port | `8080` | No |
| `CORS_ALLOWED_ORIGINS` | Allowed origins (no wildcards) | `http://localhost:5173,http://localhost:3000` | Yes in prod |
| `DEMO_SEED` | Seed demo accounts on startup | `true` (Enable only for demo) | No |
| `MAX_TRANSACTION_AMOUNT` | Server-side cap per transaction in paise | `10000000` (₹1,00,000.00) | No |
| `VITE_API_URL` | Frontend API backend origin | `http://localhost:8080` | Yes on Vercel |

---

## 6. Demo Accounts (**DEMO-ONLY — DO NOT USE IN PRODUCTION**)

> [!CAUTION]
> The credentials below are strictly for local testing, evaluation, and live demonstration. They are generated only when `DEMO_SEED=true` is set. Never deploy demo credentials to live production environments!

| Persona | Username | Password | Role | Pre-Funded Balance | Permissions / Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **System Admin** | `admin` | `AdminPass123!` | `ADMIN` | N/A | Lifecycle management: freeze, unfreeze, close accounts; view reconciliation |
| **Auditor** | `auditor` | `AuditorPass123!` | `AUDITOR` | N/A | Read-only compliance access to all statements & audit logs |
| **Customer Alice** | `alice` | `AlicePass123!` | `ACCOUNT_HOLDER` | **₹1,00,000.00** (`ACC-ALICE-01`) | Core customer for deposit, withdrawal, and transfer demos |
| **Customer Bob** | `bob` | `BobPass123!` | `ACCOUNT_HOLDER` | **₹50,000.00** (`ACC-BOB-01`) | Recipient customer for peer-to-peer transfers |

*Quick-login buttons on the web interface autofill these credentials with a single click.*

---

## 7. Complete API Specification

Interactive Swagger UI is available at **`/swagger-ui.html`**. OpenAPI JSON is at **`/v3/api-docs`**.

| Method | Endpoint Path | Role Required | `Idempotency-Key` | Description |
| :--- | :--- | :--- | :---: | :--- |
| `GET` | `/api/health` | **Public** | No | Health check: returns `{"status":"UP"}` |
| `POST` | `/api/auth/login` | **Public** | No | Authenticate user; returns JWT token (IP rate limited) |
| `GET` | `/api/auth/me` | Authenticated | No | Get authenticated profile & permissions |
| `GET` | `/api/accounts` | Authenticated | No | List accounts (Holders see own; Admin/Auditor see all) |
| `GET` | `/api/accounts/{id}` | Authenticated | No | Account details (Returns 404 if accessed by unowned user) |
| `POST` | `/api/accounts/{id}/deposit` | `ACCOUNT_HOLDER` | **Required** | Deposit funds (Clears via `SYS_CASH_CLEARING`) |
| `POST` | `/api/accounts/{id}/withdraw` | `ACCOUNT_HOLDER` | **Required** | Withdraw funds (Guards balance $\ge 0$) |
| `POST` | `/api/transfers` | `ACCOUNT_HOLDER` | **Required** | Atomic double-entry transfer (Ascending lock order) |
| `GET` | `/api/accounts/{id}/statement` | Authenticated | No | Paginated ledger history with running `balance_after` |
| `GET` | `/api/accounts/{id}/statement/export` | Authenticated | No | Download statement in RFC 4180 CSV format |
| `GET` | `/api/audit` | `ADMIN`, `AUDITOR`| No | Filterable immutable audit log |
| `POST` | `/api/admin/users` | `ADMIN` | No | Create user persona |
| `POST` | `/api/admin/accounts` | `ADMIN` | No | Open bank account for user |
| `PATCH`| `/api/admin/accounts/{id}/status` | `ADMIN` | No | Change status (`ACTIVE`, `FROZEN`, `CLOSED`) |
| `GET` | `/api/admin/reconciliation` | `ADMIN` | No | Reconcile global books ($\sum = 0$) & account balances |

---

## 8. Automated Test Suite (57/57 Tests Passing)

Run the full automated test suite using the isolated test database:
```bash
cd backend/ledgerline
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./mvnw test
```

### Complete Test Invariants Summary:
1. **Double-Entry Conservation**: 50 random concurrent operations across accounts; asserts $\sum \text{ledger entries} = 0$ and $\text{balance} = \sum \text{entries}$ for all accounts.
2. **Concurrency Overdraft Protection**: 50 parallel withdrawals against limited funds; asserts balance never goes below zero and exactly the affordable number succeed.
3. **Deadlock Elimination**: 40 parallel opposite transfers ($A \to B$ and $B \to A$) execute simultaneously without PostgreSQL deadlocks.
4. **Database-Backed Idempotency**: 20 simultaneous threads submitting the same key execute exactly once; payload hash mismatch returns HTTP 422; business failures release key for retry.
5. **Mid-Transfer Atomicity & Rollback**: Forced crash mid-transfer fully rolls back both accounts; no ledger rows or balance mutations occur.
6. **Authorization Matrix & Ownership (404 vs 403)**: Non-owned accounts return 404 (not 403) across read, deposit, withdraw, transfer, statement, and CSV. Admin/Auditor barred from moving money (403).
7. **Security & Tampering**: Expired, tampered, wrong HMAC signature, and `alg=none` tokens return 401. Per-IP brute-force lockout with sliding window and safe leftmost `X-Forwarded-For` parsing.
8. **Append-Only Database Triggers**: Raw SQL `UPDATE`, `DELETE`, and `TRUNCATE` on `ledger_entries` or `audit_log` fail at PostgreSQL storage engine level.
9. **Accounting Reconciliation**: Corrupting a balance directly via SQL flags `status: FAILED` with exact discrepancy; restoring balance flags `status: OK`.

*See [docs/TESTING.md](file:///Users/harsha/Desktop/kalpana/docs/TESTING.md) for full Windows CMD execution steps and test class details.*

---

## 9. Known Limitations & Production Roadmap

In the interest of engineering transparency and realistic evaluation:

1. **Single-Instance In-Memory Barrier vs Multi-Node Database Constraint**: The in-memory `CompletableFuture` barrier is an optimization that operates on **one server instance only** to avoid spinning. Across horizontal clusters, the true concurrency guarantee is PostgreSQL's `UNIQUE(user_id, key)` constraint with `ON CONFLICT DO NOTHING`.
2. **In-Memory Login Rate Limiter**: The login limiter tracks IP addresses using an in-memory `ConcurrentHashMap`. In a multi-node horizontal deployment, an external distributed cache (such as Redis with Bucket4j) is required.
3. **Per-IP Lockout Behind Shared Corporate NAT**: Locking out by client IP can inadvertently affect other users sharing the same public NAT gateway (such as office WiFi). A dual-key approach (IP + Username fingerprinting) would be implemented in enterprise production.
4. **No MFA or Refresh Tokens**: The current implementation uses stateless 30-minute JWT bearer tokens stored in browser `sessionStorage`. Production banking standards require Multi-Factor Authentication (TOTP/SMS), short-lived access tokens (5 min), and rotating HTTP-Only refresh tokens.
5. **Token Storage in sessionStorage**: `sessionStorage` avoids credential persistence across browser sessions, but does **not** protect against Cross-Site Scripting (XSS). Any origin XSS can read `sessionStorage`.
6. **Fixed Single Currency ("INR")**: The system strictly supports INR minor units. Multi-currency exchange rate conversions and foreign exchange (FX) spread calculations are not implemented.
7. **Free-Tier Cloud Cold Starts**: Render's free tier spins containers down after 15 minutes of inactivity. Initial wake-up requests take 30–60 seconds.
