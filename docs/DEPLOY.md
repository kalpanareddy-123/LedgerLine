# LedgerLine - Production Cloud Deployment Guide

> **CRITICAL DEADLINE & PRIVACY NOTICE**:
> - Challenge Deadline: **Thursday 12 Nov 2026, 6:00 PM IST**
> - **Keep your GitHub repository PRIVATE** throughout development and deployment. Make it public only at or immediately prior to the deadline as requested by evaluators.
> - **Free-Tier Cold Start Warning**: Free instances on Render spin down into sleep mode after 15 minutes of inactivity. The first wake-up request takes **30–60 seconds**. Always "warm up" your backend by hitting `/api/health` 10 minutes before presenting or submitting!

---

## Architecture Overview

```
[ Customer Browser / Mobile ]
              │
              ▼ HTTPS
[ Vercel / Netlify Frontend (React SPA) ]
              │
              ▼ REST API (JWT + Idempotency-Key)
[ Render / Railway Backend (Spring Boot 3 / Java 21) ]
              │
              ▼ JDBC with TLS (sslmode=require)
[ Neon / Supabase Serverless PostgreSQL 16 ]
```

---

## Step 1: Create a Private GitHub Repository & Push

1. Open your terminal in the project directory:
   ```bash
   cd /path/to/kalpana
   ```
2. Verify git status is clean and all commits are conventional:
   ```bash
   git status
   git log --oneline -n 5
   ```
3. Create a **Private** repository on GitHub named `ledgerline`:
   - Visit [github.com/new](https://github.com/new)
   - Repository Name: `ledgerline`
   - Visibility: **Private** (Do NOT initialize with README/license)
   - Click **Create repository**
4. Push your existing local repository:
   ```bash
   git remote add origin https://github.com/[YOUR_GITHUB_USERNAME]/ledgerline.git
   git branch -M main
   git push -u origin main
   ```

---

## Step 2: Provision Cloud PostgreSQL (Neon.tech Recommended)

1. Sign up or log in at [neon.tech](https://neon.tech).
2. Click **Create a project**:
   - Name: `ledgerline-db`
   - Postgres version: `PostgreSQL 16`
   - Region: Select closest region (e.g., `AWS Asia Pacific (Mumbai)` or `AWS Europe / US`).
3. Under the **Dashboard** > **Connection Details**:
   - Choose **Parameters only** or **Connection string**.
   - Note the connection host, database name (`neondb`), username (`neondb_owner`), and password.
4. Construct your Spring Boot JDBC URL with SSL:
   ```text
   jdbc:postgresql://ep-xyz-pooler.ap-southeast-1.aws.neon.tech:5432/neondb?sslmode=require
   ```

*(Alternatively, Supabase or Render Postgres can be used by obtaining their JDBC connection strings with SSL enabled).*

---

## Step 3: Deploy Backend on Render (or Railway)

### Option A: Render (Recommended)
1. Log in to [render.com](https://render.com).
2. Click **New +** > **Web Service**.
3. Select **Build and deploy from a Git repository** and connect your private `ledgerline` repo.
4. Fill in the Service Details:
   - **Name**: `ledgerline-backend`
   - **Region**: Same region as your database.
   - **Branch**: `main`
   - **Root Directory**: `backend/ledgerline`  <-- *CRITICAL*
   - **Deployment Mode**: Choose either **Native Java** (Recommended, zero Docker needed) or **Docker**:
     - **Option 1: Native Java (No Docker needed)**:
       - Environment: `Java`
       - Build Command: `./mvnw clean package -DskipTests`
       - Start Command: `java -XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -Dserver.port=$PORT -jar target/ledgerline-0.0.1-SNAPSHOT.jar`
     - **Option 2: Docker**:
       - Environment: `Docker`
       - Dockerfile Path: `Dockerfile`
   - **Instance Type**: `Free`
5. Click **Advanced** > **Add Environment Variable**:

| Variable | Value / Description | Example |
| :--- | :--- | :--- |
| `PORT` | `8080` | `8080` |
| `DB_URL` | Neon JDBC URL with sslmode=require | `jdbc:postgresql://ep-xyz.neon.tech/neondb?sslmode=require` |
| `DB_USER` | Neon Database user | `neondb_owner` |
| `DB_PASSWORD` | Neon Database password | `[YOUR_NEON_PASSWORD]` |
| `JWT_SECRET` | Generate fresh via `openssl rand -base64 48` | `48+ random base64 characters (NEVER reuse local dev keys!)` |
| `JWT_EXPIRATION_MINUTES`| Token validity | `30` |
| `CORS_ALLOWED_ORIGINS` | Temporary until frontend deployed | `http://localhost:3000,http://localhost:5173` |
| `DEMO_SEED` | Seed demo accounts on startup | `true` (Required so evaluators can log in!) |
| `MAX_TRANSACTION_AMOUNT`| Server-side upper limit | `10000000` |

> **JWT Secret Generation Command**:
> Run in your local terminal:
> ```bash
> openssl rand -base64 48
> ```
> Copy the output directly into the `JWT_SECRET` field. Never use default or local test secrets in cloud deployments.

6. Click **Create Web Service**.
7. Render will pull the repo, build the application, run Flyway migrations (`V1__init_schema.sql` and `V2__block_truncate.sql`), seed the initial demo data, and start the application.
8. When deployment finishes, copy your Render URL:
   `https://ledgerline-backend-xxxx.onrender.com`
9. Verify health check:
   ```bash
   curl -i https://ledgerline-backend-xxxx.onrender.com/api/health
   # Returns: HTTP 200 {"status":"UP","service":"LedgerLine Core Engine"}
   ```

---

## Step 4: Deploy Frontend on Vercel

1. Log in to [vercel.com](https://vercel.com).
2. Click **Add New...** > **Project**.
3. Import your GitHub repository `ledgerline`.
4. Configure Project Settings:
   - **Framework Preset**: `Vite`
   - **Root Directory**: Click Edit, select `frontend` <-- *CRITICAL*
   - **Build Command**: `npm run build`
   - **Output Directory**: `dist`
5. Expand **Environment Variables**:
   - Name: `VITE_API_URL`
   - Value: `https://ledgerline-backend-xxxx.onrender.com` (Your Render backend URL with NO trailing slash)
6. Click **Deploy**.
7. Vercel will build the React SPA and provide a live URL, e.g.:
   `https://ledgerline-frontend-xxxx.vercel.app`
8. Single Page Application (SPA) routing is handled automatically by the included `vercel.json` and `public/_redirects`.

---

## Step 5: Complete the 2-Way CORS Handshake

> [!WARNING]
> **#1 MOST COMMON DEPLOYMENT FAILURE: Browser CORS Rejection**  
> During Step 3, the backend was configured with localhost origins. **Your live Vercel frontend will fail on login** (`Network Error` / `CORS header missing`) until you update this setting.
> You MUST perform this step immediately after Vercel gives you your frontend domain!

To enable the frontend to communicate with the backend:
1. Return to [render.com](https://render.com) > your backend service > **Environment**.
2. Update `CORS_ALLOWED_ORIGINS` to include your exact live Vercel URL (with NO trailing slash):
   ```text
   https://ledgerline-frontend-xxxx.vercel.app,http://localhost:3000,http://localhost:5173
   ```
3. Click **Save Changes**. Render will automatically trigger a zero-downtime redeploy.
4. Verify by opening the Vercel site, inspecting DevTools > Network tab, and confirming the `POST /api/auth/login` preflight OPTIONS request returns HTTP 204 with `Access-Control-Allow-Origin: https://ledgerline-frontend-xxxx.vercel.app`.

---

## Step 6: Verify with Automated Smoke Test

Verify the entire cloud deployment end-to-end using the automated smoke test script:

### On Linux / macOS:
```bash
chmod +x scripts/smoke-test.sh
./scripts/smoke-test.sh https://ledgerline-backend-xxxx.onrender.com
```

### On Windows CMD:
```cmd
scripts\smoke-test.bat https://ledgerline-backend-xxxx.onrender.com
```

### The smoke test performs:
1. `GET /api/health` -> Expects HTTP 200 `status: UP`
2. `POST /api/auth/login` -> Logs in with demo user, extracts JWT token
3. `POST /api/accounts/{id}/deposit` (Attempt 1) -> Submits with unique `Idempotency-Key`
4. `POST /api/accounts/{id}/deposit` (Attempt 2) -> Replays exact same key; expects `Idempotent-Replay: true` header
5. `POST /api/transfers` -> Executes transfer to counterparty
6. `GET /api/admin/reconciliation` -> Confirms total book conservation $\sum = 0$ and `status: OK`

---

## Step 7: Final Demo Check Before Evaluation

1. Open your live Vercel frontend in an **Incognito / Private Window**.
2. Log in with demo accounts (**DEMO-ONLY**):
   - Customer Alice: `alice` / `AlicePass123!`
   - Customer Bob: `bob` / `BobPass123!`
   - System Admin: `admin` / `AdminPass123!`
   - Compliance Auditor: `auditor` / `AuditorPass123!`
   - Auditor: `auditor` / `AuditorPassword123!`
3. Execute a live deposit and transfer. Confirm that:
   - Account balances update immediately.
   - Idempotency key indicator is visible.
   - Admin reconciliation panel shows 100% OK and books balanced.
