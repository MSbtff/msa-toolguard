"""Loopback MCP server, A2A 1.0 harness, and OpenCode helper unit tests."""

from __future__ import annotations

import http.client
import json
from threading import Thread
import unittest
from uuid import uuid4

from msa_toolguard import (
    A2A_CONTENT_TYPE,
    A2A_VERSION,
    AGENT_CARD_PATH,
    A2AError,
    A2ARuntimeBusyError,
    A2ATaskConflictError,
    AccessLevel,
    LoopbackA2AClient,
    LoopbackA2AServer,
    LoopbackMcpServer,
    OpenCodeRunnerError,
    ToolExecutionOutcome,
    ToolPolicy,
    ToolSession,
    build_agent_card,
    build_scoped_permissions,
    parse_opencode_jsonl,
    sanitize_agent_env,
)


class McpAndA2AHarnessTest(unittest.TestCase):
    def test_loopback_mcp_server_enforces_auth_origin_and_session_guardrails(self) -> None:
        session = ToolSession(max_access_level=AccessLevel.READ_ONLY, bound_parameters={"disposalId": "disp-7"})
        session.register_tool(
            ToolPolicy(name="get_disposal", max_calls=2, bound_parameters=("disposalId",)),
            lambda args: ToolExecutionOutcome.completed({"disposalId": args["disposalId"], "status": "PENDING"}),
        )
        session.register_tool(
            ToolPolicy(name="retry_disposal", access_level=AccessLevel.MUTATING, max_calls=1),
            lambda args: ToolExecutionOutcome.completed({"status": "COMPLETED"}),
        )
        tools = [
            {"name": "get_disposal", "description": "Read disposal", "inputSchema": {"type": "object"}},
            {"name": "retry_disposal", "description": "Retry disposal", "inputSchema": {"type": "object"}},
        ]

        with LoopbackMcpServer.for_session(session, tools) as server:
            def post(body: dict, *, auth: str | None = None, origin: str | None = None, path: str = "/mcp"):
                conn = http.client.HTTPConnection("127.0.0.1", server.port, timeout=3)
                headers = {"Content-Type": "application/json"}
                headers["Authorization"] = f"Bearer {server.secret}" if auth is None else auth
                if origin is not None:
                    headers["Origin"] = origin
                try:
                    conn.request("POST", path, json.dumps(body).encode("utf-8"), headers)
                    resp = conn.getresponse()
                    raw = resp.read()
                    return resp.status, (json.loads(raw) if raw else None)
                finally:
                    conn.close()

            # Auth and Origin protections
            self.assertEqual(403, post({"jsonrpc": "2.0", "id": 1, "method": "ping"}, auth="Bearer wrong")[0])
            self.assertEqual(403, post({"jsonrpc": "2.0", "id": 1, "method": "ping"}, origin="http://evil.local")[0])
            self.assertEqual(202, post({"jsonrpc": "2.0", "method": "notifications/initialized"})[0])

            # Standard MCP methods
            status, init = post({"jsonrpc": "2.0", "id": 1, "method": "initialize"})
            self.assertEqual(200, status)
            self.assertEqual("2025-03-26", init["result"]["protocolVersion"])

            status, listed = post({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
            self.assertEqual(200, status)
            self.assertEqual(2, len(listed["result"]["tools"]))

            # Allowed read call returns evidenceId
            status, call_ok = post(
                {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "get_disposal", "arguments": {}}}
            )
            self.assertEqual(200, status)
            self.assertFalse(call_ok["result"]["isError"])
            payload = json.loads(call_ok["result"]["content"][0]["text"])
            self.assertEqual("E1", payload["evidenceId"])
            self.assertEqual("disp-7", payload["disposalId"])

            # Mutating tool blocked by READ_ONLY session -> isError=True with ACCESS_DENIED
            status, call_blocked = post(
                {
                    "jsonrpc": "2.0",
                    "id": 4,
                    "method": "tools/call",
                    "params": {"name": "retry_disposal", "arguments": {"requestId": "req-1"}},
                }
            )
            self.assertEqual(200, status)
            self.assertTrue(call_blocked["result"]["isError"])
            blocked_payload = json.loads(call_blocked["result"]["content"][0]["text"])
            self.assertEqual("ACCESS_DENIED", blocked_payload["code"])

    def test_loopback_a2a_server_and_client_lifecycle_and_errors(self) -> None:
        token = "unit-test-a2a-shared-token-1234567890"
        tasks: dict[str, dict] = {}
        seen_messages: dict[str, tuple[str, dict]] = {}
        busy_flag = {"busy": False}

        def handle_message(payload: dict) -> dict:
            if busy_flag["busy"]:
                raise A2ARuntimeBusyError()
            msg = payload["message"]
            msg_id = msg["messageId"]
            data = msg["parts"][0]["data"]
            if msg_id in seen_messages:
                existing_id, existing_data = seen_messages[msg_id]
                if existing_data != data:
                    raise A2ATaskConflictError()
                return tasks[existing_id]
            task_id = str(uuid4())
            context_id = str(uuid4())
            snapshot = {
                "id": task_id,
                "contextId": context_id,
                "status": {"state": "TASK_STATE_COMPLETED"},
                "artifacts": [
                    {
                        "artifactId": str(uuid4()),
                        "name": "sample-skill",
                        "parts": [{"mediaType": "application/json", "data": {"echo": data}}],
                    }
                ],
            }
            tasks[task_id] = snapshot
            seen_messages[msg_id] = (task_id, data)
            return snapshot

        server = LoopbackA2AServer(
            0,
            token,
            card_supplier=lambda port: build_agent_card(
                name="Sample A2A Agent",
                description="Test A2A server",
                port=port,
                skill_id="sample-skill",
                skill_name="Sample Skill",
                tags=["test"],
            ),
            task_lookup=tasks.get,
            message_handler=handle_message,
        )
        thread = Thread(target=server.serve_forever, kwargs={"poll_interval": 0.02}, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(thread.join, 2)
        self.addCleanup(server.shutdown)

        client = LoopbackA2AClient(server.server_port, token)
        card = client.verify_agent_card("sample-skill")
        self.assertEqual("Sample A2A Agent", card["name"])

        msg_id = str(uuid4())
        msg = {"messageId": msg_id, "role": "ROLE_USER", "parts": [{"data": {"k": "v"}, "mediaType": "application/json"}]}
        task = client.send_message(msg)
        completed = client.poll_task(task["id"], task["contextId"], initial_task=task)
        self.assertEqual("TASK_STATE_COMPLETED", completed["status"]["state"])
        self.assertEqual({"echo": {"k": "v"}}, completed["artifacts"][0]["parts"][0]["data"])

        # Idempotent same messageId returns same taskId
        replay = client.send_message(msg)
        self.assertEqual(task["id"], replay["id"])

        # Conflicting payload with same messageId returns 409
        conflict_msg = {
            "messageId": msg_id,
            "role": "ROLE_USER",
            "parts": [{"data": {"k": "different"}, "mediaType": "application/json"}],
        }
        with self.assertRaises(A2AError) as ctx:
            client.send_message(conflict_msg)
        self.assertIn("HTTP 409", str(ctx.exception))

        # Busy returns 429
        busy_flag["busy"] = True
        with self.assertRaises(A2AError) as ctx:
            client.send_message({"messageId": str(uuid4()), "role": "ROLE_USER", "parts": [{"data": {}}]})
        self.assertIn("HTTP 429", str(ctx.exception))
        busy_flag["busy"] = False

        # Wrong token returns 401
        bad_client = LoopbackA2AClient(server.server_port, "wrong-token")
        with self.assertRaises(A2AError) as ctx:
            bad_client.request("GET", f"/tasks/{task['id']}")
        self.assertIn("HTTP 401", str(ctx.exception))

        # Agent card accessible without auth
        conn = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=3)
        try:
            conn.request("GET", AGENT_CARD_PATH, headers={"A2A-Version": A2A_VERSION, "Accept": A2A_CONTENT_TYPE})
            resp = conn.getresponse()
            self.assertEqual(200, resp.status)
        finally:
            conn.close()

    def test_opencode_helpers(self) -> None:
        perms = build_scoped_permissions("erp", [{"name": "get_disposal"}])
        self.assertEqual(
            [
                {"action": "*", "resource": "*", "effect": "deny"},
                {"action": "erp_get_disposal", "resource": "*", "effect": "allow"},
            ],
            perms,
        )
        env = sanitize_agent_env(
            {"ERPMSA_ACCESS_TOKEN": "secret", "OPENCODE_API_KEY": "keep", "OPENCODE_OTHER": "drop", "PATH": "/bin"},
            strip_keys=("ERPMSA_ACCESS_TOKEN",),
            mcp_token_env="ERPMSA_MCP_TOKEN",
            mcp_secret="ephemeral",
        )
        self.assertNotIn("ERPMSA_ACCESS_TOKEN", env)
        self.assertNotIn("OPENCODE_OTHER", env)
        self.assertEqual("keep", env["OPENCODE_API_KEY"])
        self.assertEqual("ephemeral", env["ERPMSA_MCP_TOKEN"])
        self.assertEqual("1", env["OPENCODE_PURE"])

        text = parse_opencode_jsonl('{"type":"text","part":{"text":"hello"}}\n', 0)
        self.assertEqual("hello", text)
        with self.assertRaises(OpenCodeRunnerError) as ctx:
            parse_opencode_jsonl('{"type":"error","error":{"type":"provider.quota"}}\n', 1)
        self.assertIn("한도", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
