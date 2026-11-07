@echo off
setlocal enabledelayedexpansion

:: ==============================================================================
:: LedgerLine End-to-End Smoke Test Script (Windows CMD)
:: Usage: scripts\smoke-test.bat [BASE_URL]
:: Example: scripts\smoke-test.bat http://localhost:8080
:: Example: scripts\smoke-test.bat https://ledgerline-backend-xxxx.onrender.com
:: ==============================================================================

set "BASE_URL=%~1"
if "%BASE_URL%"=="" set "BASE_URL=http://localhost:8080"

:: Remove trailing slash if present
if "%BASE_URL:~-1%"=="/" set "BASE_URL=%BASE_URL:~0,-1%"

echo ==========================================================
echo  LedgerLine End-to-End Smoke Test (Windows)
echo  Target URL: %BASE_URL%
echo ==========================================================

:: ------------------------------------------------------------------------------
echo [Step 1] Health Check Endpoint (GET /api/health)
:: ------------------------------------------------------------------------------
curl -s -f "%BASE_URL%/api/health" > "%TEMP%\health.json"
if errorlevel 1 (
    echo [FAIL] Health check failed or server unreachable.
    exit /b 1
)
type "%TEMP%\health.json" | findstr /i "UP" >nul
if errorlevel 1 (
    echo [FAIL] Health check did not report UP status.
    exit /b 1
)
echo [PASS] Health check returned 200 and status UP.

:: ------------------------------------------------------------------------------
echo [Step 2] Authenticate Demo Users (Alice & Admin)
:: ------------------------------------------------------------------------------
curl -s -X POST "%BASE_URL%/api/auth/login" -H "Content-Type: application/json" -d "{\"username\":\"alice\",\"password\":\"AlicePass123!\"}" > "%TEMP%\alice_auth.json"
type "%TEMP%\alice_auth.json" | findstr /i "token" >nul
if errorlevel 1 (
    echo [FAIL] Failed to authenticate Alice.
    type "%TEMP%\alice_auth.json"
    exit /b 1
)
echo [PASS] Authenticated Alice (ACCOUNT_HOLDER).

curl -s -X POST "%BASE_URL%/api/auth/login" -H "Content-Type: application/json" -d "{\"username\":\"admin\",\"password\":\"AdminPass123!\"}" > "%TEMP%\admin_auth.json"
type "%TEMP%\admin_auth.json" | findstr /i "token" >nul
if errorlevel 1 (
    echo [FAIL] Failed to authenticate Admin.
    type "%TEMP%\admin_auth.json"
    exit /b 1
)
echo [PASS] Authenticated Admin (ADMIN).

:: ------------------------------------------------------------------------------
echo [Step 3] Verification Complete
:: ------------------------------------------------------------------------------
echo [INFO] To run the full multi-stage bash test with regex JSON extraction, run scripts\smoke-test.sh using Git Bash or WSL.
echo ==========================================================
echo [SUCCESS] LedgerLine basic connectivity and login verified!
echo ==========================================================
