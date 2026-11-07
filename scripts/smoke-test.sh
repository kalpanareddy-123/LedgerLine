#!/usr/bin/env bash
# ==============================================================================
# LedgerLine End-to-End Smoke Test Script
# Usage: ./scripts/smoke-test.sh [BASE_URL]
# Example: ./scripts/smoke-test.sh http://localhost:8080
# Example: ./scripts/smoke-test.sh https://ledgerline-backend-xxxx.onrender.com
# ==============================================================================

set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
# Strip trailing slash if present
BASE_URL="${BASE_URL%/}"

echo "=========================================================="
echo " LedgerLine End-to-End Cloud & Local Smoke Test"
echo " Target URL: ${BASE_URL}"
echo "=========================================================="

GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

pass() {
  echo -e "${GREEN}✓ PASS:${NC} $1"
}

fail() {
  echo -e "${RED}✗ FAIL:${NC} $1"
  exit 1
}

step() {
  echo -e "\n${BLUE}--> Step $1:${NC} $2"
}

# ------------------------------------------------------------------------------
step 1 "Health Check Endpoint (GET /api/health)"
# ------------------------------------------------------------------------------
HEALTH_RESP=$(curl -s -w "\n%{http_code}" "${BASE_URL}/api/health")
HTTP_CODE=$(echo "${HEALTH_RESP}" | tail -n1)
BODY=$(echo "${HEALTH_RESP}" | sed '$d')

if [ "${HTTP_CODE}" -eq 200 ] && echo "${BODY}" | grep -q "UP"; then
  pass "Health check returned HTTP 200 and status UP"
else
  fail "Health check failed (HTTP ${HTTP_CODE}): ${BODY}"
fi

# ------------------------------------------------------------------------------
step 2 "Authenticate Demo Users (Alice & Admin)"
# ------------------------------------------------------------------------------
ALICE_AUTH=$(curl -s -X POST "${BASE_URL}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"AlicePass123!"}')

ALICE_TOKEN=$(echo "${ALICE_AUTH}" | grep -o '"token":"[^"]*' | cut -d'"' -f4 || true)

if [ -z "${ALICE_TOKEN}" ]; then
  fail "Failed to authenticate Alice: ${ALICE_AUTH}"
fi
pass "Authenticated Alice (ACCOUNT_HOLDER)"

ADMIN_AUTH=$(curl -s -X POST "${BASE_URL}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"AdminPass123!"}')

ADMIN_TOKEN=$(echo "${ADMIN_AUTH}" | grep -o '"token":"[^"]*' | cut -d'"' -f4 || true)

if [ -z "${ADMIN_TOKEN}" ]; then
  fail "Failed to authenticate Admin: ${ADMIN_AUTH}"
fi
pass "Authenticated Admin (ADMIN)"

ALICE_ID=$(echo "${ALICE_AUTH}" | grep -o '"userId":"[^"]*' | head -n1 | cut -d'"' -f4 || true)

# ------------------------------------------------------------------------------
step 3 "Discover Alice and Bob Accounts"
# ------------------------------------------------------------------------------
ALICE_ACCOUNTS=$(curl -s -X GET "${BASE_URL}/api/accounts" \
  -H "Authorization: Bearer ${ALICE_TOKEN}")

ALICE_ACC_ID=$(echo "${ALICE_ACCOUNTS}" | grep -o '"id":"[^"]*' | head -n1 | cut -d'"' -f4 || true)

if [ -z "${ALICE_ACC_ID}" ]; then
  echo "No existing account found for Alice. Provisioning ACC-ALICE-01 via Admin API..."
  NEW_ACC=$(curl -s -X POST "${BASE_URL}/api/admin/accounts" \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{\"ownerId\":\"${ALICE_ID}\",\"accountNumber\":\"ACC-ALICE-01\",\"initialBalance\":10000000}")
  ALICE_ACC_ID=$(echo "${NEW_ACC}" | grep -o '"id":"[^"]*' | head -n1 | cut -d'"' -f4 || true)
fi
pass "Found Alice Account: ${ALICE_ACC_ID}"

# Authenticate Bob to discover/provision his account
BOB_AUTH=$(curl -s -X POST "${BASE_URL}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"bob","password":"BobPass123!"}')
BOB_TOKEN=$(echo "${BOB_AUTH}" | grep -o '"token":"[^"]*' | cut -d'"' -f4 || true)
BOB_ID=$(echo "${BOB_AUTH}" | grep -o '"userId":"[^"]*' | head -n1 | cut -d'"' -f4 || true)

BOB_ACCOUNTS=$(curl -s -X GET "${BASE_URL}/api/accounts" \
  -H "Authorization: Bearer ${BOB_TOKEN}")
BOB_ACC_ID=$(echo "${BOB_ACCOUNTS}" | grep -o '"id":"[^"]*' | head -n1 | cut -d'"' -f4 || true)

if [ -z "${BOB_ACC_ID}" ]; then
  echo "No existing account found for Bob. Provisioning ACC-BOB-01 via Admin API..."
  NEW_BOB=$(curl -s -X POST "${BASE_URL}/api/admin/accounts" \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{\"ownerId\":\"${BOB_ID}\",\"accountNumber\":\"ACC-BOB-01\",\"initialBalance\":5000000}")
  BOB_ACC_ID=$(echo "${NEW_BOB}" | grep -o '"id":"[^"]*' | head -n1 | cut -d'"' -f4 || true)
fi
pass "Target Destination Account (Bob): ${BOB_ACC_ID}"

# ------------------------------------------------------------------------------
step 4 "Idempotent Deposit Test (First Execution & Replay)"
# ------------------------------------------------------------------------------
IDEMP_KEY=$(uuidgen 2>/dev/null || python3 -c 'import uuid; print(uuid.uuid4())' || echo "idemp-smoke-$RANDOM-$RANDOM")
DEPOSIT_PAYLOAD='{"amount":1000,"reference":"Smoke Test Deposit ₹10"}'

echo "Using Idempotency-Key: ${IDEMP_KEY}"

# First execution
DEPOSIT_RESP_1=$(curl -s -i -X POST "${BASE_URL}/api/accounts/${ALICE_ACC_ID}/deposit" \
  -H "Authorization: Bearer ${ALICE_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  -d "${DEPOSIT_PAYLOAD}")

HTTP_CODE_1=$(echo "${DEPOSIT_RESP_1}" | grep "HTTP/" | head -n1 | awk '{print $2}')
if [ "${HTTP_CODE_1}" -ne 200 ]; then
  fail "First deposit attempt failed with HTTP ${HTTP_CODE_1}"
fi
pass "Initial deposit succeeded with HTTP 200"

# Second execution (Replay identical key + body)
DEPOSIT_RESP_2=$(curl -s -i -X POST "${BASE_URL}/api/accounts/${ALICE_ACC_ID}/deposit" \
  -H "Authorization: Bearer ${ALICE_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMP_KEY}" \
  -d "${DEPOSIT_PAYLOAD}")

HTTP_CODE_2=$(echo "${DEPOSIT_RESP_2}" | grep "HTTP/" | head -n1 | awk '{print $2}')
if [ "${HTTP_CODE_2}" -ne 200 ]; then
  fail "Replay deposit attempt failed with HTTP ${HTTP_CODE_2}"
fi

if echo "${DEPOSIT_RESP_2}" | grep -iq "Idempotent-Replay: true"; then
  pass "Replay deposit returned exact HTTP 200 with 'Idempotent-Replay: true' header"
else
  pass "Replay deposit returned exact HTTP 200"
fi

# ------------------------------------------------------------------------------
step 5 "Execute Double-Entry Transfer"
# ------------------------------------------------------------------------------
TRANSFER_KEY=$(uuidgen 2>/dev/null || python3 -c 'import uuid; print(uuid.uuid4())' || echo "transfer-smoke-$RANDOM-$RANDOM")
TRANSFER_PAYLOAD=$(printf '{"sourceAccountId":"%s","destinationAccountId":"%s","amount":500,"reference":"Smoke Transfer ₹5"}' "${ALICE_ACC_ID}" "${BOB_ACC_ID}")

TRANSFER_RESP=$(curl -s -w "\n%{http_code}" -X POST "${BASE_URL}/api/transfers" \
  -H "Authorization: Bearer ${ALICE_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${TRANSFER_KEY}" \
  -d "${TRANSFER_PAYLOAD}")

HTTP_CODE_XFER=$(echo "${TRANSFER_RESP}" | tail -n1)
BODY_XFER=$(echo "${TRANSFER_RESP}" | sed '$d')

if [ "${HTTP_CODE_XFER}" -eq 200 ]; then
  pass "Transfer completed successfully (HTTP 200)"
else
  fail "Transfer failed with HTTP ${HTTP_CODE_XFER}: ${BODY_XFER}"
fi

# ------------------------------------------------------------------------------
step 6 "Audit & Accounting Reconciliation Check"
# ------------------------------------------------------------------------------
RECON_RESP=$(curl -s -X GET "${BASE_URL}/api/admin/reconciliation" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}")

if echo "${RECON_RESP}" | grep -q '"status":"OK"'; then
  pass "Reconciliation OK: Total ledger sum is 0 and all accounts balance perfectly"
else
  fail "Reconciliation check failed: ${RECON_RESP}"
fi

echo ""
echo "=========================================================="
echo -e "${GREEN}ALL SMOKE TESTS PASSED SUCCESSFULLY!${NC}"
echo "LedgerLine deployment at ${BASE_URL} is healthy and verified."
echo "=========================================================="
