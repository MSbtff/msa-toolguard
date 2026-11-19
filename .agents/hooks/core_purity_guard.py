#!/usr/bin/env python3
"""PreToolUse guardrail for msa-toolguard.

Policy inside /Users/jonghwan/Desktop/msa-toolguard:
- deny: secrets, toolguard-core runtime deps, out-of-repo file writes, destructive shell commands.
- allow: safe in-repo file operations and allowlisted local dev commands (Turbo feel inside this repo).
- ask: external reads, shell pipelines/substitutions, and any non-allowlisted command.
"""

import json
from pathlib import Path
import re
import shlex
import sys


REPO_ROOT = Path(__file__).resolve().parents[2].resolve()
FILE_READ_TOOLS = frozenset({"read_file", "view_file"})
FILE_WRITE_TOOLS = frozenset({
    "edit_file",
    "write_file",
    "write_to_file",
    "replace_file_content",
    "multi_replace_file_content",
})
FILE_TOOLS = FILE_READ_TOOLS | FILE_WRITE_TOOLS

SECRET_FILENAMES = frozenset({
    "application-local.yml",
    "application-local.yaml",
    "application-secret.yml",
    "application-secret.yaml",
})
SECRET_SUFFIXES = frozenset({".key", ".pem", ".p12", ".pfx", ".jks"})
CORE_RUNTIME_DEP_RE = re.compile(r"^\s*(?:implementation|api|runtimeOnly)\b", re.MULTILINE)

# Hard-deny shell patterns (never allowed even in Turbo-like mode)
DESTRUCTIVE_CMD_PATTERNS = (
    (
        re.compile(r"\brm\s+(?:-[a-zA-Z]*r[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*r|--recursive\b[^\n;]*--force\b)", re.IGNORECASE),
        "파괴적 명령 차단: `rm -rf` 강제 재귀 삭제는 금지됩니다.",
    ),
    (
        re.compile(r"\bgit\b[^\n;]*\bpush\b[^\n;]*(?:\s-f\b|\s--force(?:-with-lease)?\b|\s\+[^\s]+)", re.IGNORECASE),
        "파괴적 Git 명령 차단: force push는 금지됩니다.",
    ),
    (
        re.compile(r"\bgit\b[^\n;]*\b(?:reset\s+--hard|clean\s+-[a-zA-Z]*f)", re.IGNORECASE),
        "파괴적 Git 명령 차단: `git reset --hard` / `git clean -f`는 금지됩니다.",
    ),
    (
        re.compile(r"\bdocker(?:\s+compose|-compose)?\b[^\n;]*(?:\bdown\b[^\n;]*(?:-v\b|--volumes\b)|\bvolume\s+(?:rm|prune)\b|\bsystem\s+prune\b)", re.IGNORECASE),
        "데이터 보존 차단: Docker 볼륨/시스템 삭제 명령은 금지됩니다.",
    ),
    (
        re.compile(r"\b(?:drop\s+(?:database|schema)|truncate\s+table)\b", re.IGNORECASE),
        "데이터 보존 차단: DROP DATABASE/SCHEMA 또는 TRUNCATE TABLE은 금지됩니다.",
    ),
    (
        re.compile(r"\bsudo\b|\bchmod\s+-R\s+777\b", re.IGNORECASE),
        "보안 정책 차단: `sudo` 또는 `chmod -R 777`은 금지됩니다.",
    ),
    (
        re.compile(r"(?:^|[\s'\"=/])(?:secrets/|\.env(?!\.example\b)(?:\.[A-Za-z0-9_-]+)?(?=$|[\s'\"/:;])|application-(?:local|secret)\.ya?ml(?=$|[\s'\"/:;]))"),
        "보안 정책 차단: 셸 명령에서 실제 .env/secrets/로컬 시크릿 파일 접근은 금지됩니다.",
    ),
    (
        re.compile(r"(?:^|[\s'\"=/])[^\s'\"/]+\.(?:key|pem|p12|pfx|jks)(?=$|[\s'\"/:;])", re.IGNORECASE),
        "보안 정책 차단: 셸 명령에서 개인키/인증서 파일 접근은 금지됩니다.",
    ),
)

# Shell metacharacters that fall back to "ask" instead of auto-allow
OPAQUE_SHELL_RE = re.compile(r"[|`<>]|\$\(|\\\n")
ENV_ASSIGN_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*=.*$")
FORBIDDEN_ENV_KEYS = frozenset({"LD_PRELOAD", "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH"})

SAFE_GIT_SUBCOMMANDS = frozenset({
    "status", "diff", "log", "show", "branch", "tag",
    "add", "commit", "checkout", "switch", "stash",
    "fetch", "pull", "push", "remote", "rev-parse", "ls-files",
})
SAFE_GH_PREFIXES = (
    ("run", "list"), ("run", "view"), ("run", "watch"),
    ("release", "list"), ("release", "view"),
    ("repo", "view"), ("auth", "status"),
    ("pr", "list"), ("pr", "view"), ("pr", "status"),
)
SAFE_READ_BINS = frozenset({"ls", "find", "grep", "rg", "cat", "head", "tail", "wc", "sort", "pwd", "which", "echo"})
SAFE_DOCKER_SUBCOMMANDS = frozenset({"info", "ps", "version"})


def emit(decision, reason=None):
    out = {"decision": decision}
    if reason:
        out["reason"] = reason
    sys.stdout.write(json.dumps(out, ensure_ascii=False) + "\n")


def is_secret_rel(rel: Path) -> bool:
    fname = rel.name
    return (
        fname == ".env"
        or (fname.startswith(".env.") and fname != ".env.example")
        or fname in SECRET_FILENAMES
        or "secrets" in rel.parts
        or rel.suffix.lower() in SECRET_SUFFIXES
    )


def resolve_path(raw_path: str):
    path = Path(raw_path).expanduser()
    if not path.is_absolute():
        path = REPO_ROOT / path
    resolved = path.resolve()
    try:
        rel = resolved.relative_to(REPO_ROOT)
    except ValueError:
        rel = None
    return resolved, rel


def evaluate_file_tool(name: str, args: dict):
    raw_path = args.get("Path") or args.get("path") or args.get("TargetFile") or args.get("AbsolutePath")
    if not isinstance(raw_path, str) or not raw_path.strip():
        return "ask", None

    _, rel = resolve_path(raw_path)
    if rel is None:
        return "ask", None

    if is_secret_rel(rel):
        return "deny", f"보안 정책 차단: '{rel.as_posix()}' 시크릿/환경 파일 접근은 금지됩니다."

    if name in FILE_WRITE_TOOLS and rel.as_posix() == "toolguard-core/build.gradle":
        content = str(args.get("Content") or args.get("Replacement") or "")
        if CORE_RUNTIME_DEP_RE.search(content):
            return (
                "deny",
                "아키텍처 경계 차단: toolguard-core는 순수 Java 21 모듈이므로 "
                "implementation/api/runtimeOnly 외부 의존성을 추가할 수 없습니다.",
            )

    return "allow", None


def token_escapes_repo(token: str) -> bool:
    if token in ("/dev/null",):
        return False
    if ".." in Path(token).parts:
        return True
    if token.startswith(("/", "~")):
        # Ignore URLs or flags like --format=... handled before this check
        _, rel = resolve_path(token)
        return rel is None
    return False


def is_safe_segment(segment: str) -> bool:
    segment = segment.strip()
    if not segment:
        return True
    try:
        tokens = shlex.split(segment)
    except ValueError:
        return False
    if not tokens:
        return True

    idx = 0
    while idx < len(tokens) and ENV_ASSIGN_RE.match(tokens[idx]):
        key = tokens[idx].split("=", 1)[0]
        if key in FORBIDDEN_ENV_KEYS:
            return False
        idx += 1
    if idx >= len(tokens):
        return False

    cmd = tokens[idx]
    rest = tokens[idx + 1:]

    # Block any path argument escaping REPO_ROOT (except URLs like https://...)
    for arg in rest:
        if arg.startswith(("http://", "https://")):
            continue
        candidate = arg.split("=", 1)[1] if arg.startswith("-") and "=" in arg else arg
        if candidate.startswith(("/", "~")) or ".." in Path(candidate).parts:
            if token_escapes_repo(candidate):
                return False

    if cmd in ("./gradlew", str(REPO_ROOT / "gradlew")):
        return True

    if cmd == "git":
        if not rest:
            return False
        # Disallow -C pointing outside repo (already caught above) and check subcommand
        sub_idx = 0
        while sub_idx < len(rest) and rest[sub_idx].startswith("-"):
            if rest[sub_idx] in ("-C", "-c", "--git-dir", "--work-tree"):
                sub_idx += 2
            else:
                sub_idx += 1
        if sub_idx >= len(rest):
            return False
        return rest[sub_idx] in SAFE_GIT_SUBCOMMANDS

    if cmd in ("python3", "python", "pytest"):
        # Disallow inline arbitrary code execution for auto-allow; allow unittest/module/test files
        if "-c" in rest:
            return False
        return True

    if cmd == "gh":
        if len(rest) >= 2 and (rest[0], rest[1]) in SAFE_GH_PREFIXES:
            return True
        return False

    if cmd == "docker":
        return bool(rest) and rest[0] in SAFE_DOCKER_SUBCOMMANDS

    if cmd == "chmod":
        return len(rest) >= 2 and rest[0] == "+x"

    if cmd in SAFE_READ_BINS:
        return True

    return False


def evaluate_run_command(args: dict):
    cmd = str(args.get("CommandLine") or args.get("command") or "").strip()
    if not cmd:
        return "ask", None

    for pattern, reason in DESTRUCTIVE_CMD_PATTERNS:
        if pattern.search(cmd):
            return "deny", reason

    if OPAQUE_SHELL_RE.search(cmd):
        return "ask", None

    # Split simple command chains (&&, ;) while respecting quotes
    segments = re.split(r"\s*(?:&&|;)\s*", cmd)
    if all(is_safe_segment(seg) for seg in segments):
        return "allow", None

    return "ask", None


def evaluate(payload):
    tool_call = payload.get("toolCall")
    if not isinstance(tool_call, dict):
        return "ask", None
    name = str(tool_call.get("name") or "")
    args = tool_call.get("args")
    if not isinstance(args, dict):
        return "ask", None

    if name in FILE_TOOLS:
        return evaluate_file_tool(name, args)
    if name == "run_command":
        return evaluate_run_command(args)
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
