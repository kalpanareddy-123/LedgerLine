# LedgerLine - Deployment Guide

> **Note for Evaluators & Candidates**:  
> In accordance with challenge rules, no automated external pushes or deployments have been executed by the agent. Follow the exact commands below to deploy locally or to cloud environments.

---

## 1. Quick Local Deployment (Docker Compose)

The easiest way to run the entire stack (PostgreSQL 16, Spring Boot 3 Backend, React Frontend) locally:

### Prerequisites:
- Docker Desktop or Docker Engine + Compose installed.

### Steps:
```bash
# 1. Clone repository and navigate to root
cd kalpana

# 2. Copy environment template
cp .env.example .env

# 3. Start all services in background
docker compose up -d --build

# 4. View startup logs
docker compose logs -f
```

### Accessing Local Services:
- **Frontend Web Dashboard**: [http://localhost:3000](http://localhost:3000)
- **Backend API & Health**: [http://localhost:8080/api/health](http://localhost:8080/api/health)
- **Interactive Swagger UI**: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
- **PostgreSQL Database**: `localhost:5432` (`ledgerline_db`, user `ledgerline_app`)

---

## 2. Managed Database Setup (Neon / Supabase / Render Postgres)

LedgerLine requires a standard PostgreSQL instance with full DDL permissions for Flyway migrations and trigger creation.

### Option A: Neon Postgres (Recommended for Serverless)
1. Sign up at [neon.tech](https://neon.tech) and click **Create Project**.
2. Select PostgreSQL 16 and your closest region.
3. Save the connection string:
   ```text
   postgres://[user]:[password]@[host]/[database]?sslmode=require
   ```
4. Note your credentials for backend deployment:
   - `DB_URL`: `jdbc:postgresql://[host]:5432/[database]?sslmode=require`
   - `DB_USER`: `[user]`
   - `DB_PASSWORD`: `[password]`

### Option B: Supabase
1. Create a project at [supabase.com](https://supabase.com).
2. Go to **Settings > Database > Connection Pooling / Direct Connection**.
3. Use the JDBC connection format provided in your dashboard.

### Option C: Render PostgreSQL
1. On [render.com](https://render.com), click **New > PostgreSQL**.
2. Name it `ledgerline-db`, set User to `ledgerline_app`, database to `ledgerline_db`.
3. Copy the **Internal Database URL** (for Render backend) or **External Database URL**.

---

## 3. Cloud Backend Deployment (Render or Railway)

### Deploying to Render
1. In Render Dashboard, click **New > Web Service**.
2. Connect your Git repository.
3. Configure settings:
   - **Name**: `ledgerline-backend`
   - **Root Directory**: `backend/ledgerline`
   - **Environment**: `Docker`
   - **Dockerfile Path**: `Dockerfile`
4. Set Environment Variables under **Environment**:
   - `DB_URL`: `jdbc:postgresql://[db-host]:5432/[database]?sslmode=require`
   - `DB_USER`: `[db-user]`
   - `DB_PASSWORD`: `[db-password]`
   - `JWT_SECRET`: `[generate a random 64-character string]`
   - `CORS_ALLOWED_ORIGINS`: `https://[your-frontend-domain].vercel.app,http://localhost:3000`
5. Click **Deploy Web Service**. Flyway migrations will run automatically on startup and seed initial demo accounts.

### Deploying to Railway
1. In Railway, click **New Project > Deploy from GitHub repo**.
2. Set Root Directory to `/backend/ledgerline`.
3. Railway automatically detects the multi-stage Dockerfile.
4. Add the same environment variables under the **Variables** tab.

---

## 4. Cloud Frontend Deployment (Vercel or Netlify)

### Deploying to Vercel
1. Install Vercel CLI or import repository at [vercel.com](https://vercel.com).
2. Configure Project Settings:
   - **Framework Preset**: `Vite`
   - **Root Directory**: `frontend`
   - **Build Command**: `npm run build`
   - **Output Directory**: `dist`
3. Add Environment Variable:
   - `VITE_API_BASE_URL`: `https://ledgerline-backend.onrender.com` (your backend URL without trailing slash)
4. Click **Deploy**.

### Deploying to Netlify
1. Connect repo at [netlify.com](https://netlify.com).
2. Set base directory to `frontend`, build command to `npm run build`, and publish directory to `frontend/dist`.
3. Add build environment variable `VITE_API_BASE_URL`.
4. Ensure `_redirects` file exists in `frontend/public/` with content: `/* /index.html 200` to support React Router single-page navigation.

---

## 5. Verifying Deployment Integrity

Once deployed, verify the system invariants:

1. **Health Check**:
   ```bash
   curl -i https://[your-backend-domain]/api/health
   # Expected: HTTP 200 {"status":"UP"}
   ```

2. **Swagger Documentation**:
   Visit `https://[your-backend-domain]/swagger-ui.html`.

3. **Reconciliation Audit**:
   Log in as `admin` and call `GET /api/admin/reconciliation` to confirm:
   - `status: "OK"`
   - `totalLedgerSum: 0`
   - `mismatches: []`
