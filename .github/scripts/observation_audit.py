#!/usr/bin/env python3
"""Weekly dev-hygiene check: confirm the nightly E2E's confirm->purge cleanup is
working, i.e. orphaned price observations (leftovers from deleted accounts) aren't
accumulating. Logs in as the E2E admin, reads /admin/observations/orphaned-count,
writes the number to the job summary, and fails the run if it exceeds ORPHAN_THRESHOLD.
"""
import json
import os
import sys
import urllib.request

BASE = os.environ["BASE_URL"].rstrip("/")
EMAIL = os.environ.get("E2E_ADMIN_EMAIL", "")
PASSWORD = os.environ.get("E2E_ADMIN_PASSWORD", "")
THRESHOLD = int(os.environ.get("ORPHAN_THRESHOLD", "50"))


def summary(md):
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as handle:
            handle.write(md + "\n")


def post(path, body):
    request = urllib.request.Request(
        BASE + path, data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.loads(response.read())


def get(path, token):
    request = urllib.request.Request(BASE + path, headers={"Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.loads(response.read())


def main():
    if not (EMAIL and PASSWORD):
        summary("observation-audit: no admin creds — skipped")
        return 0
    try:
        token = post("/api/v1/auth/login", {"email": EMAIL, "password": PASSWORD})["token"]
        count = get("/api/v1/admin/observations/orphaned-count", token)["orphaned"]
    except Exception as error:
        summary(f"observation-audit: error — {error}")
        print(error)
        return 1
    ok = count <= THRESHOLD
    summary(f"### Orphaned observations: **{count}** "
            f"({'✅ within' if ok else '⚠️ OVER'} threshold {THRESHOLD})\n\n"
            "Leftovers from deleted accounts — should stay near zero if the nightly "
            "E2E purge is working. A climb means the purge is silently failing.")
    print(f"orphaned={count} threshold={THRESHOLD} ok={ok}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
