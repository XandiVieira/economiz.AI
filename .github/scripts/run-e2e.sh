#!/usr/bin/env bash
# Run the Postman "E2E Flow" against the live dev server, then hand any failure to
# the fixer via e2e_context.py. Never hard-fails on the newman step itself — the
# pass/fail verdict is computed from the JSON report so context is always built.
set -uo pipefail

BASE_URL="${BASE_URL:?BASE_URL required}"

newman run postman/economizai.postman_collection.json \
  --folder "E2E Flow" \
  --env-var "baseUrl=${BASE_URL}" \
  --env-var "adminEmail=${E2E_ADMIN_EMAIL:-}" \
  --env-var "adminPassword=${E2E_ADMIN_PASSWORD:-}" \
  --env-var "qrPayload=${E2E_QR_PAYLOAD:-}" \
  --env-var "testMailbox=developer@economizaai.app" \
  --reporters cli,json \
  --reporter-json-export newman.json \
  --timeout-request 30000 || true

python3 .github/scripts/e2e_context.py
