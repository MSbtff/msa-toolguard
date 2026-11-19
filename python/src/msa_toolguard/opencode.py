"""Least-privilege OpenCode configuration and output parsing helpers."""

from __future__ import annotations

import json
from typing import Any, Iterable, Mapping


class OpenCodeRunnerError(Exception):
    """Raised when an OpenCode agent invocation fails with a structured provider or protocol error."""


def build_scoped_permissions(server_name: str, tools: Iterable[dict[str, Any]]) -> list[dict[str, str]]:
    """Builds deny-by-default OpenCode permissions allowing only declared MCP tools."""
    permissions = [{"action": "*", "resource": "*", "effect": "deny"}]
    for tool in tools:
        permissions.append({"action": f"{server_name}_{tool['name']}", "resource": "*", "effect": "allow"})
    return permissions


def sanitize_agent_env(
    base_env: Mapping[str, str],
    *,
    strip_keys: Iterable[str] = (),
    mcp_token_env: str | None = None,
    mcp_secret: str | None = None,
    xdg_config_home: str | None = None,
) -> dict[str, str]:
    """Strips host secrets and external OpenCode configs for isolated agent execution."""
    stripped = set(strip_keys) | {"PWD"}
    env = {
        key: value
        for key, value in base_env.items()
        if key not in stripped and (not key.startswith("OPENCODE_") or key == "OPENCODE_API_KEY")
    }
    env.update(
        {
            "OPENCODE_PURE": "1",
            "OPENCODE_DISABLE_PROJECT_CONFIG": "1",
            "OPENCODE_DISABLE_CLAUDE_CODE": "1",
            "OPENCODE_DISABLE_EXTERNAL_SKILLS": "1",
        }
    )
    if xdg_config_home is not None:
        env["XDG_CONFIG_HOME"] = xdg_config_home
    if mcp_token_env and mcp_secret is not None:
        env[mcp_token_env] = mcp_secret
    return env


def build_opencode_config(
    *,
    agent: str,
    description: str,
    instructions: str,
    permissions: list[dict[str, str]],
    steps: int,
    server_name: str,
    mcp_config: dict[str, Any],
) -> dict[str, Any]:
    return {
        "permissions": permissions,
        "snapshots": False,
        "update": "disable",
        "agents": {
            agent: {
                "description": description,
                "mode": "primary",
                "permissions": permissions,
                "steps": steps,
                "system": instructions,
            }
        },
        "mcp": {"servers": {server_name: mcp_config}},
    }


def parse_opencode_jsonl(stdout: str, returncode: int) -> str:
    parts: list[str] = []
    try:
        for line in stdout.splitlines():
            event = json.loads(line)
            if event["type"] == "error":
                error = event.get("error")
                if isinstance(error, dict) and error.get("type") == "provider.auth":
                    raise OpenCodeRunnerError("OpenCode 공급자 인증이 거절됐습니다. 공급자에 다시 로그인하세요.")
                if isinstance(error, dict) and error.get("type") == "provider.quota":
                    raise OpenCodeRunnerError(
                        "OpenCode 모델 사용량 또는 잔액 한도에 도달했습니다. 계정 한도와 --model 설정을 확인하세요."
                    )
                raise OpenCodeRunnerError("OpenCode가 조사 오류를 반환했습니다.")
            if event["type"] == "text":
                parts.append(event["part"]["text"])
        if returncode:
            raise OpenCodeRunnerError("OpenCode 조사가 비정상 종료됐습니다.")
        return "".join(parts)
    except (ValueError, KeyError, TypeError, RecursionError) as error:
        raise OpenCodeRunnerError("OpenCode 응답 형식이 예상과 다릅니다.") from error
