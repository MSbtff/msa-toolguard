#!/usr/bin/env python3
"""Stop hook: verify toolguard-core purity and shared contracts/ schema alignment on changes."""

import json
import os
from pathlib import Path
import re
import subprocess
import sys


REPO_ROOT = Path(__file__).resolve().parents[2]
CORE_RUNTIME_DEP_RE = re.compile(r"^\s*(?:implementation|api|runtimeOnly)\b", re.MULTILINE)
WATCHED_PREFIXES = ("contracts/", "python/", "toolguard-core/build.gradle")


def emit(decision, reason=None):
    out = {"decision": decision}
    if reason:
        out["reason"] = reason
    sys.stdout.write(json.dumps(out, ensure_ascii=False) + "\n")


def changed_files(repo_root):
    proc = subprocess.run(
        ["git", "status", "--porcelain", "--untracked-files=normal"],
        cwd=repo_root,
        capture_output=True,
        text=True,
        timeout=5,
    )
    if proc.returncode != 0:
        return []
    files = []
    for line in proc.stdout.splitlines():
        if len(line) < 4:
            continue
        entry = line[3:].strip()
        if " -> " in entry:
            entry = entry.split(" -> ", 1)[1].strip()
        if entry.startswith('"') and entry.endswith('"'):
            entry = entry[1:-1]
        files.append(entry)
    return files


def needs_verification(paths):
    return any(
        path == "toolguard-core/build.gradle" or path.startswith(("contracts/", "python/"))
        for path in paths
    )


def verify_core_purity(repo_root):
    build_gradle = repo_root / "toolguard-core/build.gradle"
    if not build_gradle.exists():
        return None
    text = build_gradle.read_text(encoding="utf-8")
    if CORE_RUNTIME_DEP_RE.search(text):
        return "toolguard-core/build.gradle에 외부 런타임 의존성(implementation/api/runtimeOnly)이 포함되어 있습니다."
    return None


def run_contract_tests(repo_root):
    env = dict(os.environ)
    env["PYTHONPATH"] = str(repo_root / "python/src")
    return subprocess.run(
        [sys.executable, "-m", "unittest", "discover", "-s", "python/tests", "-p", "test_core_contracts.py", "-v"],
        cwd=repo_root,
        env=env,
        capture_output=True,
        text=True,
        timeout=15,
    )


def evaluate(payload, repo_root=REPO_ROOT):
    if payload.get("terminationReason") != "NO_TOOL_CALL":
        return "stop", None
    if payload.get("continuationActive"):
        return "stop", None

    try:
        paths = changed_files(repo_root)
    except Exception:
        return "stop", None

    if not needs_verification(paths):
        return "stop", None

    purity_error = verify_core_purity(repo_root)
    if purity_error:
        return "continue", purity_error

    try:
        result = run_contract_tests(repo_root)
    except (FileNotFoundError, subprocess.TimeoutExpired):
        return "stop", None

    if result.returncode == 0:
        return "stop", None

    output = (result.stderr or result.stdout or "").strip().splitlines()
    tail = "\n".join(output[-12:]) if output else f"exit code {result.returncode}"
    return (
        "continue",
        "contracts/ 또는 python/ 변경 후 스키마 계약 검증(`test_core_contracts.py`)이 실패했습니다. 수정 후 다시 확인하세요:\n"
        + tail,
    )


def main():
    try:
        payload = json.load(sys.stdin)
    except Exception:
        emit("stop")
        return
    decision, reason = evaluate(payload if isinstance(payload, dict) else {})
    emit(decision, reason)


if __name__ == "__main__":
    main()
