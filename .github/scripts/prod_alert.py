#!/usr/bin/env python3
"""Open a deduplicated GitHub issue for a prod error signature.

Prod is ALERT-ONLY: the fixer never touches it. This script is the alert half —
given a signature + context (from log_sweep.py or e2e_context.py), it checks the
repo's `prod-error` issues (open AND closed) and opens a new one only for a
signature never seen before. Closing an issue therefore acks it permanently;
reopen it (or delete it) to be re-alerted on recurrence.
"""
import json
import os
import urllib.error
import urllib.request

TOKEN = os.environ["GITHUB_TOKEN"]
REPO = os.environ["GITHUB_REPOSITORY"]
SIGNATURE = os.environ.get("SIGNATURE", "").strip()
CONTEXT = os.environ.get("CONTEXT", "").strip()
SOURCE = os.environ.get("SOURCE", "prod-monitor")
LABEL = "prod-error"
API = f"https://api.github.com/repos/{REPO}"


def call(path, method="GET", body=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(
        f"{API}{path}",
        data=data,
        method=method,
        headers={
            "Authorization": f"Bearer {TOKEN}",
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
        },
    )
    with urllib.request.urlopen(request, timeout=25) as response:
        return json.loads(response.read().decode())


def ensure_label():
    try:
        call("/labels", method="POST", body={
            "name": LABEL,
            "color": "b60205",
            "description": "Error seen on the prod service (alert-only, no auto-fix)",
        })
    except urllib.error.HTTPError as error:
        if error.code != 422:  # 422 = already exists
            raise


def already_reported():
    issues = call(f"/issues?labels={LABEL}&state=all&per_page=100")
    for issue in issues:
        haystack = (issue.get("title") or "") + (issue.get("body") or "")
        if SIGNATURE in haystack:
            return issue.get("number")
    return None


def main():
    if not SIGNATURE:
        print("[prod-alert] empty signature, nothing to report")
        return 0

    existing = already_reported()
    if existing:
        print(f"[prod-alert] signature already tracked in issue #{existing}, skipping")
        return 0

    ensure_label()
    issue = call("/issues", method="POST", body={
        "title": f"[prod] {SIGNATURE[:120]}",
        "labels": [LABEL],
        "body": (
            f"**Source:** {SOURCE}\n"
            f"**Signature:** `{SIGNATURE}`\n\n"
            f"{CONTEXT}\n\n"
            "> Opened automatically by prod monitoring. Prod is **alert-only**: "
            "reproduce and fix on dev (the autofix loop can help), then ship to prod "
            "through the normal gated deploy. Closing this issue acks the signature — "
            "it will not be re-opened on recurrence."
        ),
    })
    print(f"[prod-alert] opened issue #{issue.get('number')}: {issue.get('html_url')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
