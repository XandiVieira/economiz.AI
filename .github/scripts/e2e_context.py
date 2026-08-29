#!/usr/bin/env python3
"""Turn a failed newman E2E run into fixer context.

Reads newman.json (produced by run-e2e.sh), extracts the failing request(s) and
assertion(s), and enriches them with the server-side exception from the Render
logs during the run window. Emits GITHUB_OUTPUT: failed, signature, context.
Exits 1 when there was a failure (so the job goes red), 0 otherwise.
"""
import json
import os
import re
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

RENDER_KEY = os.environ.get("RENDER_API_KEY", "")
SERVICE = os.environ.get("SERVICE_ID", "")
OWNER = os.environ.get("OWNER_ID", "")
RELYON = re.compile(r"(ERROR|FATAL).*com\.relyon|Exception")


def emit(failed, signature="", context=""):
    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a", encoding="utf-8") as handle:
            handle.write(f"failed={'true' if failed else 'false'}\n")
            handle.write(f"signature={signature}\n")
            handle.write("context<<AUTOFIX_EOF\n" + context + "\nAUTOFIX_EOF\n")


def summary(md):
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as handle:
            handle.write(md + "\n")


def recent_server_errors():
    if not (RENDER_KEY and SERVICE and OWNER):
        return ""
    try:
        start = (datetime.now(timezone.utc) - timedelta(minutes=20)).strftime("%Y-%m-%dT%H:%M:%SZ")
        params = urllib.parse.urlencode({"ownerId": OWNER, "resource": SERVICE, "startTime": start, "limit": 100})
        request = urllib.request.Request(
            f"https://api.render.com/v1/logs?{params}",
            headers={"Authorization": f"Bearer {RENDER_KEY}", "Accept": "application/json"},
        )
        with urllib.request.urlopen(request, timeout=25) as response:
            logs = (json.loads(response.read().decode()).get("logs") or [])
        lines = [entry.get("message", "") for entry in logs if RELYON.search(entry.get("message", ""))]
        return "\n".join(lines[-15:])
    except Exception:
        return ""


def main():
    try:
        with open("newman.json", "r", encoding="utf-8") as handle:
            report = json.load(handle)
    except Exception as error:
        # newman didn't even produce a report — treat as an infra issue, not a code bug.
        summary(f"### E2E run\n\nnewman produced no report: {error}")
        emit(False)
        return 0

    run = report.get("run", {})
    failures = run.get("failures", []) or []
    stats = run.get("stats", {})
    total = stats.get("assertions", {}).get("total", "?")
    failed_n = stats.get("assertions", {}).get("failed", 0)

    if not failures:
        summary(f"### E2E run ✅\n\nAll assertions passed ({total}).")
        emit(False)
        return 0

    lines = []
    first_item = None
    for failure in failures[:8]:
        source = failure.get("source", {}) or {}
        name = source.get("name", "(unknown request)")
        first_item = first_item or name
        err = failure.get("error", {}) or {}
        lines.append(f"- {name}: {err.get('name', 'Error')}: {err.get('message', '')}")

    signature = f"E2E: {first_item}"
    server = recent_server_errors()
    context = (
        f"A daily E2E run against the live dev server FAILED ({failed_n}/{total} assertions).\n"
        f"Failing steps:\n" + "\n".join(lines)
    )
    if server:
        context += "\n\nServer-side errors during the run (the likely root cause):\n```\n" + server + "\n```"
    else:
        context += ("\n\nNo server exception was logged — this may be a contract mismatch "
                    "(wrong status/shape) or a test-config gap. If it's not a real code bug, reply REPRO_FAIL.")

    summary(f"### E2E run ❌\n\n{failed_n}/{total} assertions failed.\n\n" + "\n".join(lines))
    emit(True, signature, context)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
