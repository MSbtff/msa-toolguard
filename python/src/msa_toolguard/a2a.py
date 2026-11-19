"""Loopback A2A 1.0 HTTP+JSON client and server harness primitives."""

from __future__ import annotations

import hmac
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import re
import time
from typing import Any, Callable

A2A_VERSION = "1.0"
A2A_CONTENT_TYPE = "application/a2a+json"
AGENT_CARD_PATH = "/.well-known/agent-card.json"
UUID_RE = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
A2A_TOKEN_RE = re.compile(r"[A-Za-z0-9_-]{32,128}")


class A2AError(Exception):
    """Base error for A2A protocol and validation failures."""


class A2ATaskConflictError(A2AError):
    """Raised when the same messageId is re-submitted with conflicting payload or context."""


class A2ARuntimeBusyError(A2AError):
    """Raised when the A2A runtime already has an active in-flight task."""


def validate_a2a_token(token: str | None, env_name: str = "TOOLGUARD_A2A_TOKEN") -> str:
    if not token or not A2A_TOKEN_RE.fullmatch(token):
        raise A2AError(f"{env_name}에 32자 이상의 URL-safe 공유 토큰을 설정하세요.")
    return token


def load_a2a_token(env_name: str = "TOOLGUARD_A2A_TOKEN") -> str:
    return validate_a2a_token(os.environ.get(env_name, ""), env_name=env_name)


def loopback_interface(port: int, *, version: str = A2A_VERSION) -> dict[str, str]:
    return {
        "url": f"http://127.0.0.1:{port}",
        "protocolBinding": "HTTP+JSON",
        "protocolVersion": version,
    }


def build_agent_card(
    *,
    name: str,
    description: str,
    port: int,
    skill_id: str,
    skill_name: str,
    tags: list[str] | tuple[str, ...] = (),
    version: str = "1.0.0",
) -> dict[str, Any]:
    return {
        "name": name,
        "description": description,
        "version": version,
        "supportedInterfaces": [loopback_interface(port)],
        "capabilities": {"streaming": False, "pushNotifications": False},
        "securitySchemes": {"localBearer": {"httpAuthSecurityScheme": {"scheme": "Bearer"}}},
        "securityRequirements": [{"schemes": {"localBearer": {"list": []}}}],
        "defaultInputModes": ["application/json"],
        "defaultOutputModes": ["application/json"],
        "skills": [
            {
                "id": skill_id,
                "name": skill_name,
                "description": description,
                "tags": list(tags),
            }
        ],
    }


class LoopbackA2AClient:
    """Strict 127.0.0.1 HTTP+JSON A2A 1.0 client with bounded response reading."""

    def __init__(
        self,
        port: int,
        token: str,
        *,
        version: str = A2A_VERSION,
        timeout: float = 3.0,
        max_body_bytes: int = 65536,
    ) -> None:
        self.port = port
        self.token = token
        self.version = version
        self.timeout = timeout
        self.max_body_bytes = max_body_bytes

    def request(self, method: str, path: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
        headers = {"A2A-Version": self.version, "Accept": A2A_CONTENT_TYPE}
        if path != AGENT_CARD_PATH:
            headers["Authorization"] = f"Bearer {self.token}"
        body = None
        if payload is not None:
            headers["Content-Type"] = A2A_CONTENT_TYPE
            body = json.dumps(payload).encode("utf-8")
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=self.timeout)
        try:
            connection.request(method, path, body, headers)
            with connection.getresponse() as response:
                if response.status != 200:
                    raise A2AError(f"A2A 요청 실패 (HTTP {response.status}).")
                raw = response.read(self.max_body_bytes + 1)
        except (OSError, http.client.HTTPException) as error:
            raise A2AError("A2A 런타임 연결 실패 또는 시간 초과.") from error
        finally:
            connection.close()
        try:
            data = json.loads(raw) if len(raw) <= self.max_body_bytes else None
        except (ValueError, RecursionError) as error:
            raise A2AError("A2A 응답을 해석할 수 없습니다.") from error
        if not isinstance(data, dict):
            raise A2AError("A2A 응답 형식이 예상과 다릅니다.")
        return data

    def verify_agent_card(self, skill_id: str) -> dict[str, Any]:
        card = self.request("GET", AGENT_CARD_PATH)
        interfaces = card.get("supportedInterfaces")
        skills = card.get("skills")
        expected_interface = loopback_interface(self.port, version=self.version)
        if (
            not isinstance(interfaces, list)
            or expected_interface not in interfaces
            or not isinstance(skills, list)
            or not any(isinstance(skill, dict) and skill.get("id") == skill_id for skill in skills)
        ):
            raise A2AError(f"Agent Card에 지정한 A2A 인터페이스와 스킬('{skill_id}')이 없습니다.")
        return card

    def send_message(
        self,
        message: dict[str, Any],
        *,
        return_immediately: bool = True,
        accepted_output_modes: list[str] | tuple[str, ...] = ("application/json",),
    ) -> dict[str, Any]:
        response = self.request(
            "POST",
            "/message:send",
            {
                "message": message,
                "configuration": {
                    "returnImmediately": return_immediately,
                    "acceptedOutputModes": list(accepted_output_modes),
                },
            },
        )
        task = response.get("task")
        if not isinstance(task, dict):
            raise A2AError("A2A 응답에서 task 객체를 확인하지 못했습니다.")
        return task

    def poll_task(
        self,
        task_id: str,
        context_id: str,
        *,
        initial_task: dict[str, Any] | None = None,
        timeout_seconds: float = 145.0,
        poll_interval: float = 0.5,
    ) -> dict[str, Any]:
        task = initial_task if initial_task is not None else self.request("GET", f"/tasks/{task_id}")
        deadline = time.monotonic() + timeout_seconds
        while True:
            status = task.get("status")
            state = status.get("state") if isinstance(status, dict) else None
            if state == "TASK_STATE_COMPLETED":
                return task
            if state not in ("TASK_STATE_SUBMITTED", "TASK_STATE_WORKING"):
                raise A2AError("A2A 태스크가 완료 상태에 도달하지 못했습니다.")
            if time.monotonic() >= deadline:
                raise A2AError("A2A 태스크 대기 시간이 초과됐습니다.")
            time.sleep(poll_interval)
            task = self.request("GET", f"/tasks/{task_id}")
            if task.get("id") != task_id or task.get("contextId") != context_id:
                raise A2AError("A2A 태스크 응답이 위임한 태스크와 일치하지 않습니다.")


class LoopbackA2AServer(ThreadingHTTPServer):
    """Reusable threaded 127.0.0.1 A2A 1.0 HTTP+JSON server with Bearer auth and RPC error replies."""

    def __init__(
        self,
        port: int,
        token: str,
        *,
        card_supplier: Callable[[int], dict[str, Any]],
        task_lookup: Callable[[str], dict[str, Any] | None],
        message_handler: Callable[[dict[str, Any]], dict[str, Any]],
        max_body_bytes: int = 16384,
        version: str = A2A_VERSION,
        invalid_argument_message: str = "요청이 A2A 계약과 일치하지 않습니다.",
        conflict_message: str = "같은 메시지 ID의 위임 내용이 다릅니다.",
        busy_message: str = "이미 진행 중인 작업이 있습니다.",
    ) -> None:
        self.authorization = f"Bearer {token}".encode("utf-8")
        self.card_supplier = card_supplier
        self.task_lookup = task_lookup
        self.message_handler = message_handler
        self.max_body_bytes = max_body_bytes
        self.a2a_version = version
        self.invalid_argument_message = invalid_argument_message
        self.conflict_message = conflict_message
        self.busy_message = busy_message
        super().__init__(("127.0.0.1", port), _A2ARequestHandler)


class _A2ARequestHandler(BaseHTTPRequestHandler):
    server: LoopbackA2AServer

    def setup(self) -> None:
        super().setup()
        self.connection.settimeout(3)

    def log_message(self, *args: Any) -> None:
        pass

    def reply(self, status: int, payload: dict[str, Any], media_type: str = A2A_CONTENT_TYPE) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", media_type)
        if status == 401:
            self.send_header("WWW-Authenticate", "Bearer")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def problem(self, status: int, code: str, message: str, reason: str | None = None) -> None:
        error: dict[str, Any] = {"code": status, "status": code, "message": message}
        if reason:
            error["details"] = [
                {
                    "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                    "reason": reason,
                    "domain": "a2a-protocol.org",
                }
            ]
        self.reply(status, {"error": error})

    def authorized(self) -> bool:
        if self.headers.get("Origin") or not hmac.compare_digest(
            self.headers.get("Authorization", "").encode("utf-8"),
            self.server.authorization,
        ):
            self.problem(401, "UNAUTHENTICATED", "A2A 인증이 필요합니다.")
            return False
        if self.headers.get("A2A-Version") != self.server.a2a_version:
            self.problem(400, "FAILED_PRECONDITION", "A2A 1.0 인터페이스입니다.", "VERSION_NOT_SUPPORTED")
            return False
        return True

    def do_GET(self) -> None:
        if self.path == AGENT_CARD_PATH:
            self.reply(200, self.server.card_supplier(self.server.server_port), "application/json")
            return
        if not self.authorized():
            return
        task_id = self.path.removeprefix("/tasks/")
        if self.path.startswith("/tasks/") and UUID_RE.fullmatch(task_id):
            snapshot = self.server.task_lookup(task_id)
            if snapshot is not None:
                self.reply(200, snapshot)
                return
        self.problem(404, "NOT_FOUND", "A2A 태스크를 찾을 수 없습니다.", "TASK_NOT_FOUND")

    def do_POST(self) -> None:
        if not self.authorized():
            return
        if self.path != "/message:send":
            self.problem(400, "FAILED_PRECONDITION", "지원하는 위임 경로는 /message:send입니다.", "UNSUPPORTED_OPERATION")
            return
        if self.headers.get_content_type() not in (A2A_CONTENT_TYPE, "application/json"):
            self.problem(400, "INVALID_ARGUMENT", "JSON 요청 본문이 필요합니다.", "CONTENT_TYPE_NOT_SUPPORTED")
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= self.server.max_body_bytes:
                raise ValueError()
            payload = json.loads(self.rfile.read(length))
            snapshot = self.server.message_handler(payload)
        except A2ATaskConflictError:
            self.problem(409, "ALREADY_EXISTS", self.server.conflict_message)
            return
        except A2ARuntimeBusyError:
            self.problem(429, "RESOURCE_EXHAUSTED", self.server.busy_message)
            return
        except (ValueError, OverflowError, RecursionError, OSError, A2AError):
            self.problem(400, "INVALID_ARGUMENT", self.server.invalid_argument_message)
            return
        self.reply(200, {"task": snapshot})
