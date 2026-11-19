#!/usr/bin/env python3
"""PreToolUse guardrail for msa-toolguard: protect secrets and keep toolguard-core dependency-free."""

import json
from pathlib import Path
import re
import sys


REPO_ROOT = Path(__file__).resolve().parents[2]
FILE_TOOLS = frozenset({
    "read_file", "view_file",
    "edit_file", "write_file", "write_to_file",
    "replace_file_content", "multi_replace_file_content",
})
WRITE_TOOLS = frozenset({
    "edit_file", "write_file", "write_to_file",
    "replace_file_content", "multi_replace_file_content",
})
SECRET_SUFFIXES = frozenset({".key", ".pem", ".p12", ".pfx", ".jks"})
CORE_RUNTIME_DEP_RE = re.compile(r"^\s*(?:implementation|api|runtimeOnly)\b", re.MULTILINE)


def emit(decision, reason=None):
    out = {"decision": decision}
    if reason:
        out["reason"] = reason
    sys.stdout.write(json.dumps(out, ensure_ascii=False) + "\n")


def evaluate(payload):
    tool_call = payload.get("toolCall")
    if not isinstance(tool_call, dict):
        return "ask", None
    name = str(tool_call.get("name") or "")
    args = tool_call.get("args")
    if not isinstance(args, dict):
        return "ask", None

    if name in FILE_TOOLS:
        raw_path = args.get("Path") or args.get("path") or args.get("TargetFile") or args.get("AbsolutePath")
        if isinstance(raw_path, str) and raw_path.strip():
            path = Path(raw_path).expanduser()
            if not path.is_absolute():
                path = REPO_ROOT / path
            try:
                rel = path.resolve().relative_to(REPO_ROOT)
            except ValueError:
                return "ask", None

            fname = rel.name
            if (
                fname == ".env"
                or (fname.startswith(".env.") and fname != ".env.example")
                or "secrets" in rel.parts
                or rel.suffix.lower() in SECRET_SUFFIXES
            ):
                return "deny", f"보안 정책 차단: '{rel.as_posix()}' 시크릿/환경 파일 접근은 금지됩니다."

            if name in WRITE_TOOLS and rel.as_posix() == "toolguard-core/build.gradle":
                content = str(args.get("Content") or args.get("Replacement") or "")
                if CORE_RUNTIME_DEP_RE.search(content):
                    return (
                        "deny",
                        "아키텍처 경계 차단: toolguard-core는 순수 Java 21 모듈이므로 "
                        "implementation/api/runtimeOnly 외부 의존성을 추가할 수 없습니다.",
                    )
    return "ask", None


def main():
    try:
        payload = json.load(sys.stdin)
    except Exception:
        emit("ask")
        return
    decision, reason = evaluate(payload if isinstance(payload, dict) else {})
    emit(decision, reason)


if __name__ == "__main__":
    main()
