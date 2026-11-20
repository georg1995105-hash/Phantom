#!/usr/bin/env python3
"""Check a PR handoff record without contacting external services.

This is a consistency check, NOT proof that CI or device tests truly passed.
"""
import os
import re
import sys

MARKER = re.compile(r"<!--\s*agent-handoff:v1\s*\n(.*?)\n-->", re.DOTALL)
FIELDS = {"issue", "phase", "head", "ci", "device"}
PHASES = {"working", "blocked", "ready"}
DEVICE = {"not_tested", "passed", "failed", "not_applicable"}
SECTIONS = ("Выполнено", "Подтверждено", "Не проверено", "Следующий шаг", "Риски и секреты")


def validate(body: str, head_sha: str, repo: str) -> tuple[bool, list[str]]:
    """Return (enrolled, errors). Existing PRs are opt-in, not retroactively blocked."""
    if "agent-handoff:v1" not in body:
        return False, []
    m = MARKER.search(body)
    if not m:
        return True, ["Missing/invalid agent-handoff:v1 HTML comment"]
    errors = []
    metadata = {}
    for line in m.group(1).splitlines():
        if not line.strip():
            continue
        if ":" not in line:
            errors.append("Malformed metadata line")
            continue
        key, value = (part.strip() for part in line.split(":", 1))
        if key not in FIELDS:
            errors.append("Unknown field: " + key)
        elif key in metadata:
            errors.append("Duplicate field: " + key)
        else:
            metadata[key] = value
    errors.extend("Missing field: " + key for key in sorted(FIELDS - metadata.keys()))
    if not re.fullmatch(r"#[1-9][0-9]*", metadata.get("issue", "")):
        errors.append("issue must reference a real issue number such as #41")
    phase = metadata.get("phase")
    if phase not in PHASES:
        errors.append("phase must be working, blocked, or ready")
    head = metadata.get("head", "")
    if head != "pending" and not re.fullmatch(r"[0-9a-fA-F]{40}", head):
        errors.append("head must be pending or the full 40-character commit SHA")
    device = metadata.get("device")
    if device not in DEVICE:
        errors.append("device must be not_tested, passed, failed, or not_applicable")
    ci = metadata.get("ci", "")
    ci_url = re.fullmatch(
        r"(passed|failed) (https://github[.]com/([^/\s]+/[^/\s]+)/actions/runs/[0-9]+)",
        ci,
    )
    if ci != "not_run" and not ci_url:
        errors.append("ci must be not_run or 'passed|failed https://github.com/owner/repo/actions/runs/123'")
    if ci_url and repo and ci_url.group(3).lower() != repo.lower():
        errors.append("CI run URL must belong to this repository")
    for heading in SECTIONS:
        section = re.search(
            r"(?ms)^## " + re.escape(heading) + r"\s*\n(.*?)(?=^## |\Z)", body
        )
        if not section or not section.group(1).strip():
            errors.append("Missing/empty section: " + heading)
    if phase == "ready":
        if not head_sha or head.lower() != head_sha.lower():
            errors.append("READY requires head equal to the current PR head SHA")
        if not ci_url or ci_url.group(1) != "passed":
            errors.append("READY requires a CI run link marked passed (check actual run separately)")
        if device not in {"passed", "not_applicable"}:
            errors.append("READY requires device=passed or not_applicable with justification")
        if "TODO" in body or "TBD" in body:
            errors.append("READY PR cannot contain TODO/TBD placeholders")
    return True, errors


def main() -> int:
    enrolled, errors = validate(
        os.environ.get("PR_BODY", ""),
        os.environ.get("PR_HEAD_SHA", ""),
        os.environ.get("GH_REPO", ""),
    )
    if not enrolled:
        print("Legacy PR: handoff contract is opt-in; no migration required.")
        return 0
    if errors:
        for error in errors:
            print("ERROR:", error)
        return 1
    print("Handoff metadata is structurally valid. Claims require independent verification.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
