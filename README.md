# LedgerLine - Secure Double-Entry Financial Engine

> **Snovasys Fresher Hiring Challenge**  
> **Challenge Goal**: *"Move money so the books always balance and the same request never pays twice."*  
> **Target Evaluation Date**: Thursday, 12 Nov 2026, 6:00 PM IST  
> **Status**: Production-Ready, 38/38 Automated Tests Passing  

---

## 1. Overview & Architecture

**LedgerLine** is a double-entry financial accounting and money-movement engine built with Spring Boot 3, Java 21 LTS, and PostgreSQL, paired with a React single-page dashboard.

```
       [Client: React Web / Mobile / API]
                       │
             HTTPS + JWT Auth + Idempotency-Key
                       ▼
       ┌────────────────────────────────────────────────────────┐
       │                Spring Boot 3 Web Layer                 │
       │   - RequestIdFilter (Tracing)                          │
       │   - JwtAuthenticationFilter (Stateless Claim Auth)     │
       │   - GlobalExceptionHandler (Standardized JSON Errors)  │
       │   - LoginRateLimiter (Brute-Force Lockout)             │
       └────────────────────────┬───────────────────────────────┘
                                │
                                ▼
       ┌────────────────────────────────────────────────────────┐
       │               Idempotency Coordination                 │
       │   - SHA-256 Request Payload Hash Verification          │
       │   - Atomic PostgreSQL INSERT ... ON CONFLICT Claim     │
       │   - In-flight CompletableFuture Replay Barrier         │
       └────────────────────────┬───────────────────────────────┘
                                │
                                ▼
       ┌────────────────────────────────────────────────────────┐
       │               Double-Entry Ledger Engine               │
       │   - Minor Units (paise stored as BIGINT)               │
       │   - Lexicographic Lock Ordering (Deadlock-Free FOR UPDATE)│
       │   - Two-Layer Balance Protection (Service + DB CHECK)  │
       │   - Counterparty Clearing (SYS_CASH_CLEARING)          │
       └────────────────────────┬───────────────────────────────┘
                                │
                                ▼
       ┌────────────────────────────────────────────────────────┐
       │                 PostgreSQL 16 Database                 │
       │   - Append-Only Triggers (FORBID UPDATE/DELETE)        │
       │   - CHECK (is_system = true OR balance >= 0)           │
       │   - CHECK (currency = 'INR')                           │
       │   - Flyway Schema Migrations                           │
       └────────────────────────────────────────────────────────┘
```

---

## 2. Core Domain Rules

1. **Exact Integer Arithmetic (Paise)**: Money is strictly stored as minor units (`BIGINT` paise in PostgreSQL, `Long` in Java). Floats and doubles are forbidden. Currency is fixed to `"INR"`.
2. **Double-Entry Invariant**: Every deposit, withdrawal, and transfer produces balanced `DEBIT` and `CREDIT` entries where $\sum \text{entries} = 0$.
3. **Counterparty Clearing**: Deposits and withdrawals clear against the `SYS_CASH_CLEARING` system account. Non-system accounts are barred from negative balances at both service and database constraint levels.
4. **Append-Only Immutability**: PostgreSQL database triggers raise an uncatchable exception on any `UPDATE` or `DELETE` attempt on `ledger_entries` or `audit_log`. Corrections must be new reversing entries.
5. **Deadlock-Free Pessimistic Locking**: Concurrent multi-account transfers acquire row-level locks (`SELECT ... FOR UPDATE`) in deterministic ascending UUID order.
6. **Distributed Idempotency**: State-changing endpoints require an `Idempotency-Key` header (UUID). Replaying the identical payload returns the cached result with `Idempotent-Replay: true`. Sending a different payload with the same key returns HTTP 422.
7. **Zero-Leak Authorization**: Account holders receive `404 NOT_FOUND` (not 403) when requesting accounts they do not own, preventing account enumeration probing.

---

## 3. Technology Stack

- **Backend**: Java 21 LTS, Spring Boot 3.3.5, Spring Data JPA, Spring Security (Stateless JWT), Flyway, PostgreSQL Driver, Bean Validation, springdoc-openapi (Swagger UI).
- **Frontend**: React 18, Vite, React Router 6, Plain Vanilla CSS (Custom dark-mode financial design system).
- **Database**: PostgreSQL 16.
- **Testing**: JUnit 5, AssertJ, Spring MockMvc, Multi-threaded Concurrency Harness.

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
Open Windows CMD and create the application database and user:
```cmd
psql -U postgres -c "CREATE USER ledgerline_app WITH PASSWORD 'your_secure_db_password';"
psql -U postgres -c "CREATE DATABASE ledgerline_db OWNER ledgerline_app;"
psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE ledgerline_db TO ledgerline_app;"
```

#### 2. Configure Environment Variables
Set the environment variables in your CMD session (never commit real passwords):
```cmd
set DB_URL=jdbc:postgresql://localhost:5432/ledgerline_db
set DB_USER=ledgerline_app
set DB_PASSWORD=your_secure_db_password
set JWT_SECRET=your_super_secret_key_minimum_256_bits_length_1234567890
set PORT=8080
set CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:3000
```

#### 3. Run Backend
```cmd
cd backend\ledgerline
mvnw.cmd spring-boot:run
```
*Flyway will automatically execute migrations and seed default demo accounts.*

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

| Variable Name | Description | Default / Example |
| :--- | :--- | :--- |
| `DB_URL` | JDBC URL for PostgreSQL | `jdbc:postgresql://localhost:5432/ledgerline_db` |
| `DB_USER` | PostgreSQL user | `ledgerline_app` |
| `DB_PASSWORD` | PostgreSQL password | *Read from env (never hardcode)* |
| `JWT_SECRET` | Secret key for signing HMAC-SHA256 tokens | *Minimum 256 bits string* |
| `JWT_EXPIRATION_MS` | JWT validity in milliseconds | `86400000` (24 hours) |
| `PORT` | Backend HTTP listening port | `8080` |
| `CORS_ALLOWED_ORIGINS` | Allowed origins for web frontend | `http://localhost:5173,http://localhost:3000` |
| `VITE_API_BASE_URL` | Frontend API backend origin | Empty string (or `http://localhost:8080`) |

---

## 6. Demo Accounts (For Live Interview Evaluation)

The startup seed runner (`DemoDataSeeder`) automatically creates the following accounts with pre-funded balances on initial launch:

| Persona | Username | Password | Role | Pre-Funded Balance |
| :--- | :--- | :--- | :--- | :--- |
| **System Admin** | `admin` | `AdminPass123!` | `ADMIN` | N/A (Can create users/accounts, view reconciliation) |
| **Auditor** | `auditor` | `AuditorPass123!` | `AUDITOR` | N/A (Read-only access to all statements & audit logs) |
| **Customer Alice** | `alice` | `AlicePass123!` | `ACCOUNT_HOLDER` | **₹1,00,000.00** (`ACC-ALICE-01`) |
| **Customer Bob** | `bob` | `BobPass123!` | `ACCOUNT_HOLDER` | **₹50,000.00** (`ACC-BOB-01`) |

*Demo buttons on the login screen automatically fill credentials with a single click.*

---

## 7. API Specification

Interactive Swagger UI is available at **`/swagger-ui.html`**.

### Authentication
- `POST /api/auth/login` -> Returns JWT token. (Brute-force lockout after 5 consecutive failures).
- `GET /api/auth/me` -> Returns current authenticated user profile.

### Accounts
- `GET /api/accounts` -> Lists accounts (own accounts for holders; all accounts for Admin/Auditor).
- `GET /api/accounts/{id}` -> Account details (Returns 404 if accessed by unauthorized holder).

### Money Movements (Require `Idempotency-Key: <UUID>` header)
- `POST /api/accounts/{id}/deposit` -> Deposit funds (Clears with `SYS_CASH_CLEARING`).
- `POST /api/accounts/{id}/withdraw` -> Withdraw funds (Guards balance $\ge 0$).
- `POST /api/transfers` -> Transfer between accounts (Ascending lock order, no deadlocks).

### Statements & Audit
- `GET /api/accounts/{id}/statement?from=&to=&page=&size=` -> Paginated ledger history with running `balance_after`.
- `GET /api/accounts/{id}/statement/export` -> Downloads statement in RFC 4180 `text/csv` format.
- `GET /api/audit` -> Filterable audit log (Admin and Auditor only).

### Administration & Reconciliation
- `POST /api/admin/users` -> Create new user.
- `POST /api/admin/accounts` -> Open bank account for user.
- `PATCH /api/admin/accounts/{id}/status` -> Change status (`ACTIVE`, `FROZEN`, `CLOSED`).
- `GET /api/admin/reconciliation` -> Audits $\sum \text{entries} = 0$ and verifies each account's balance matches the sum of its ledger entries.

---

## 8. Automated Test Suite

Run the full automated test suite:
```bash
cd backend/ledgerline
./mvnw clean test
```

### Test Summary (38 Tests Passing):
1. **Double-Entry Conservation**: 50 random concurrent operations across accounts; asserts $\sum \text{ledger entries} = 0$ and $\text{balance} = \sum \text{entries}$ for all accounts.
2. **Concurrency Overdraft Protection**: 50 parallel withdrawals against limited funds; asserts balance never goes below zero and exactly the affordable number succeed.
3. **Deadlock Elimination**: 40 parallel opposite transfers ($A \to B$ and $B \to A$) execute simultaneously without PostgreSQL deadlocks.
4. **Distributed Idempotency**: 20 simultaneous threads submitting the same key execute exactly once; payload hash mismatch returns HTTP 422.
5. **Authorization & Security**: Ownership isolation (404), Auditor money-movement rejection (403), unauthenticated access (401), invalid/tampered JWT rejection.
6. **Input Validation**: Zero/negative amounts, self-transfer prohibition, frozen account lockouts.
7. **Database Append-Only Triggers**: Attempts to execute raw SQL `UPDATE` or `DELETE` on `ledger_entries` or `audit_log` fail at the PostgreSQL storage engine level.

---

## 9. What is NOT Finished / Known Limitations

In the interest of engineering honesty and transparent evaluation:

1. **Fixed Single Currency ("INR")**: The system strictly enforces INR. Multi-currency exchange rate conversions and foreign exchange (FX) spread calculations are not implemented.
2. **In-Memory Login Rate Limiter**: The brute-force login limiter tracks IP addresses using an in-memory `ConcurrentHashMap`. In a multi-node horizontal cluster, a distributed Redis-based rate limiter (such as Bucket4j with Redis) would be required to share lockout state across nodes.
3. **In-Flight Idempotency Coordination Scope**: In-flight concurrent requests on the exact same key use an in-memory `CompletableFuture` barrier combined with PostgreSQL `INSERT ... ON CONFLICT DO NOTHING`. In a multi-node deployment, requests reaching different server nodes simultaneously fall back to database unique constraint conflicts rather than the in-memory future.
4. **Manual vs Scheduled Reconciliation**: Reconciliation is executed on-demand via `GET /api/admin/reconciliation` and from the admin dashboard button. An automated cron job (e.g. `@Scheduled(cron = "0 0 * * * *")`) to run reconciliation hourly and alert via PagerDuty/Slack is not configured.
5. **Session Storage vs HTTP-Only Cookies**: Frontend stores the JWT in browser `sessionStorage`. While this prevents session lingering across browser sessions, production banking apps typically prefer HTTP-Only Secure Cookies with CSRF tokens to mitigate XSS exposure.
