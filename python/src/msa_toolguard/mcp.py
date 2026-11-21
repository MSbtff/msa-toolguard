"""Loopback JSON-RPC 2.0 MCP server harness for scoped agent tool execution."""

from __future__ import annotations

import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import secrets
from threading import Thread
from typing import Any, Callable

from .models import GuardrailError
from .session import ToolSession

MCP_PROTOCOL_VERSION = "2025-03-26"
DEFAULT_MCP_TOKEN_ENV = "TOOLGUARD_MCP_TOKEN"
MAX_MCP_BODY_BYTES = 65536

ToolDispatcher = Callable[[str | None, Any], dict[str, Any]]


def session_dispatcher(session: ToolSession) -> ToolDispatcher:
    """Adapts a deterministic ToolSession into a LoopbackMcpServer dispatcher."""

    def dispatch(name: str | None, arguments: Any) -> dict[str, Any]:
        if not isinstance(arguments, dict):
            return {
                "error": "Tool arguments must be a JSON object",
                "code": "POLICY_DENIED",
            }
        raw_request_id = arguments.get("requestId")
        request_id = raw_request_id if isinstance(raw_request_id, str) else None
        tool_args = {k: v for k, v in arguments.items() if k != "requestId"} if "requestId" in arguments else dict(arguments)
        try:
            outcome = session.execute(name or "", tool_args, request_id=request_id)
            facts = dict(outcome.facts)
            payload: dict[str, Any] = {
                **facts,
                "executionStatus": outcome.status.value,
                "replayed": outcome.replayed,
                "facts": facts,
            }
            payload.setdefault("status", outcome.status.value)
            if outcome.evidence_id is not None:
                payload["evidenceId"] = outcome.evidence_id
            if outcome.rejection_code is not None:
                payload["rejectionCode"] = outcome.rejection_code
            if outcome.message is not None:
                payload["message"] = outcome.message
            return payload
        except GuardrailError as blocked:
            return {
                "error": blocked.violation.message,
                "code": blocked.violation.code.value,
                "repairHint": blocked.violation.repair_hint,
            }

    return dispatch


class LoopbackMcpServer:
    """Ephemeral 127.0.0.1 JSON-RPC 2.0 MCP server with Bearer auth and Origin rejection."""

    def __init__(
        self,
        dispatcher: ToolDispatcher,
        tools: list[dict[str, Any]],
        *,
        server_name: str = "toolguard-mcp-server",
        server_version: str = "1.0.0",
        token_env: str = DEFAULT_MCP_TOKEN_ENV,
        secret: str | None = None,
        max_body_bytes: int = MAX_MCP_BODY_BYTES,
    ) -> None:
        self.dispatcher = dispatcher
        self.tools = list(tools)
        self.server_name = server_name
        self.server_version = server_version
        self.token_env = token_env
        self.secret = secret or secrets.token_urlsafe(32)
        self.max_body_bytes = max_body_bytes
        endpoint = self

        class Handler(BaseHTTPRequestHandler):
            def setup(self) -> None:
                super().setup()
                self.connection.settimeout(5)

            def log_message(self, *args: Any) -> None:
                pass

            def reply(self, status: int, body: dict[str, Any] | None = None) -> None:
                payload = b"" if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def do_GET(self) -> None:
                self.reply(405)

            def do_POST(self) -> None:
                authorization = self.headers.get("Authorization", "")
                if (
                    self.path != "/mcp"
                    or self.headers.get("Origin")
                    or not hmac.compare_digest(
                        authorization.encode("utf-8"),
                        f"Bearer {endpoint.secret}".encode("utf-8"),
                    )
                ):
                    self.reply(403)
                    return
                try:
                    size = int(self.headers.get("Content-Length", "0"))
                    if not 0 < size <= endpoint.max_body_bytes:
                        raise ValueError()
                    message = json.loads(self.rfile.read(size))
                    if not isinstance(message, dict) or message.get("jsonrpc") != "2.0":
                        raise ValueError()
                except (ValueError, RecursionError, OSError):
                    self.reply(400)
                    return
                if "id" not in message:
                    self.reply(202)
                    return
                result = endpoint.dispatch(message)
                self.reply(200, {"jsonrpc": "2.0", "id": message["id"], **result})

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.thread = Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.02}, daemon=True)

    @classmethod
    def for_session(
        cls,
        session: ToolSession,
        tools: list[dict[str, Any]],
        *,
        server_name: str = "toolguard-mcp-server",
        server_version: str = "1.0.0",
        token_env: str = DEFAULT_MCP_TOKEN_ENV,
    ) -> "LoopbackMcpServer":
        return cls(
            session_dispatcher(session),
            tools,
            server_name=server_name,
            server_version=server_version,
            token_env=token_env,
        )

    @property
    def port(self) -> int:
        return self.server.server_port

    def __enter__(self) -> "LoopbackMcpServer":
        self.thread.start()
        return self

    def __exit__(self, *args: Any) -> None:
        self.server.shutdown()
        self.thread.join()
        self.server.server_close()

    def config(self) -> dict[str, Any]:
        return {
            "type": "remote",
            "url": f"http://127.0.0.1:{self.server.server_port}/mcp",
            "oauth": False,
            "codemode": False,
            "headers": {"Authorization": f"Bearer {{env:{self.token_env}}}"},
        }

    def dispatch(self, message: dict[str, Any]) -> dict[str, Any]:
        method = message.get("method")
        if method == "initialize":
            return {
                "result": {
                    "protocolVersion": MCP_PROTOCOL_VERSION,
                    "capabilities": {"tools": {}},
                    "serverInfo": {"name": self.server_name, "version": self.server_version},
                }
            }
        if method == "ping":
            return {"result": {}}
        if method == "tools/list":
            return {"result": {"tools": self.tools}}
        if method == "tools/call":
            params = message.get("params")
            if not isinstance(params, dict):
                return {"error": {"code": -32602, "message": "Invalid parameters"}}
            try:
                result = self.dispatcher(params.get("name"), params.get("arguments", {}))
            except Exception:
                return {"error": {"code": -32603, "message": "Tool execution failed"}}
            is_error = "error" in result and "executionStatus" not in result
            return {
                "result": {
                    "content": [{"type": "text", "text": json.dumps(result, ensure_ascii=False)}],
                    "isError": is_error,
                }
            }
        return {"error": {"code": -32601, "message": "Method not allowed"}}
