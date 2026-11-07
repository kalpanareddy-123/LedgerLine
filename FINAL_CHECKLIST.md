# LedgerLine - Final Submission Checklist

Use this interactive checklist to verify every step before submitting for the Snovasys Fresher Hiring Challenge.

**Submission Deadline**: **Thursday, 12 Nov 2026, 6:00 PM IST** (No extensions permitted).

---

## 1. Candidate Identity & Git Configuration
- [ ] **Git Name & Email Set**: Verified that `git config user.name` and `git config user.email` reflect my real name and email.
- [ ] **GitHub Profile Complete**: My GitHub account has a professional profile picture, bio, and correct name.
- [ ] **Default Passwords Changed**: Local database password for development is changed from defaults.
- [ ] **Git History Clean**: Verified `git log --oneline` shows clear, conventional commits (`feat:`, `fix:`, `test:`, `docs:`, `chore:`) without fabricated or rewritten history.

---

## 2. Secrets & Security Audit
- [ ] **No Secrets in Tracked Files**: Ran secrets scan (`grep` for passwords, private keys, live JWT secrets). Confirmed tracked files contain only placeholders in `.env.example`.
- [ ] **Isolated Test Database**: Verified that test suites run against `ledgerline_test_db` and never modify `ledgerline_db`.
- [ ] **Hardened JWT Secret**: Ensured production deployment uses a freshly generated 48-byte secret (`openssl rand -base64 48`).
- [ ] **Private GitHub Repository**: Confirmed repository is set to **Private** on GitHub and pushed to `main` branch.

---

## 3. Local Verification
- [ ] **Backend Test Suite Green**: Ran `./mvnw test` (or `mvnw.cmd test` on Windows); verified all **57 tests pass with 0 failures and 0 errors**.
- [ ] **Frontend Build & Lint**: Ran `npm run lint` (0 errors) and `npm run build` (build succeeded in `frontend/dist`).
- [ ] **Smoke Test Script Verified**: Ran `./scripts/smoke-test.sh http://localhost:8080` (all 6 stages pass).
- [ ] **Docker Compose Config Valid**: Verified `docker-compose.yml` uses clean variable interpolation without hardcoded credentials.

---

## 4. Cloud Deployment (Render / Vercel / Neon)
- [ ] **Neon PostgreSQL Provisioned**: Database instance created with SSL enabled (`?sslmode=require`).
- [ ] **Backend Deployed on Render/Railway**: Web service running with `Root Directory: backend/ledgerline`. Health endpoint `GET /api/health` returns HTTP 200 `{"status":"UP"}`.
- [ ] **Frontend Deployed on Vercel/Netlify**: Single-Page App running with `Root Directory: frontend`. `VITE_API_URL` points to the Render backend URL.
- [ ] **CORS Handshake Complete**: Render `CORS_ALLOWED_ORIGINS` updated with the live Vercel domain, and redeploy completed.
- [ ] **End-to-End Cloud Smoke Test**: Ran `./scripts/smoke-test.sh [BACKEND_URL]` against the live cloud instance; all stages pass.
- [ ] **Tested in Private / Incognito Window on Another Device**: Opened the live Vercel URL on a smartphone or another computer; verified login, deposit, transfer, and reconciliation without errors.
- [ ] **Pre-Evaluation Cold Start Warm-Up**: Bookmarked a reminder to hit `GET /api/health` 10 minutes before the interview/deadline to wake up Render's free container from sleep mode.

---

## 5. Video & Form Submission
- [ ] **Demo Video Recorded (2–3 Minutes)**: Walkthrough covering customer Alice deposit with idempotency replay, peer-to-peer transfer, admin reconciliation panel, and explanation of append-only database triggers.
- [ ] **Video Uploaded**: Video uploaded to YouTube (Unlisted) or Google Drive (Share settings set to "Anyone with the link can view").
- [ ] **SUBMISSION.md Links Filled**: Replaced `[REPO_URL]`, `[FRONTEND_URL]`, `[BACKEND_URL]` with real live URLs.
- [ ] **Submission Form Completed**: All form fields filled in first person, video link attached, repository link provided.
- [ ] **Final Box Ticked**: Submitted successfully before **Thursday 12 Nov 2026, 6:00 PM IST**.
