#!/usr/bin/env python3
"""Unit tests for msa-toolguard lifecycle hooks."""

import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest import mock

HOOKS_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(HOOKS_DIR))

import core_purity_guard  # noqa: E402
import stop_contract_verify  # noqa: E402

REPO_ROOT = HOOKS_DIR.parents[1]


class ToolGuardHooksTest(unittest.TestCase):
    def test_hooks_json_is_valid(self):
        config = json.loads((HOOKS_DIR.parent / "hooks.json").read_text(encoding="utf-8"))
        self.assertIn("msa-toolguard-guards", config)
        self.assertIn("PreToolUse", config["msa-toolguard-guards"])
        self.assertIn("Stop", config["msa-toolguard-guards"])

    def test_blocks_secrets_and_core_runtime_dependencies(self):
        decision, _ = core_purity_guard.evaluate({
            "toolCall": {"name": "read_file", "args": {"Path": str(REPO_ROOT / ".env")}}
        })
        self.assertEqual("deny", decision)

        decision, _ = core_purity_guard.evaluate({
            "toolCall": {
                "name": "edit_file",
                "args": {
                    "Path": str(REPO_ROOT / "toolguard-core/build.gradle"),
                    "Replacement": "implementation 'org.springframework.boot:spring-boot-starter'",
                },
            }
        })
        self.assertEqual("deny", decision)

        decision, _ = core_purity_guard.evaluate({
            "toolCall": {
                "name": "edit_file",
                "args": {
                    "Path": str(REPO_ROOT / "toolguard-core/build.gradle"),
                    "Replacement": "testImplementation 'org.junit.jupiter:junit-jupiter'",
                },
            }
        })
        self.assertEqual("ask", decision)

    def test_stop_hook_skips_when_continuation_active_or_unrelated_changes(self):
        decision, _ = stop_contract_verify.evaluate({"terminationReason": "NO_TOOL_CALL", "continuationActive": True})
        self.assertEqual("stop", decision)

        with mock.patch.object(stop_contract_verify, "changed_files", return_value=["README.md"]):
            decision, _ = stop_contract_verify.evaluate({"terminationReason": "NO_TOOL_CALL", "continuationActive": False})
        self.assertEqual("stop", decision)

    def test_stop_hook_blocks_on_contract_test_failure_and_passes_on_ok(self):
        failed = subprocess.CompletedProcess([], 1, stdout="", stderr="AssertionError: schema mismatch")
        passed = subprocess.CompletedProcess([], 0, stdout="OK", stderr="")

        with mock.patch.object(stop_contract_verify, "changed_files", return_value=["contracts/audit-event-v1.schema.json"]), \
             mock.patch.object(stop_contract_verify, "verify_core_purity", return_value=None), \
             mock.patch.object(stop_contract_verify, "run_contract_tests", return_value=failed):
            decision, reason = stop_contract_verify.evaluate({"terminationReason": "NO_TOOL_CALL", "continuationActive": False})
        self.assertEqual("continue", decision)
        self.assertIn("schema mismatch", reason)

        with mock.patch.object(stop_contract_verify, "changed_files", return_value=["contracts/audit-event-v1.schema.json"]), \
             mock.patch.object(stop_contract_verify, "verify_core_purity", return_value=None), \
             mock.patch.object(stop_contract_verify, "run_contract_tests", return_value=passed):
            decision, _ = stop_contract_verify.evaluate({"terminationReason": "NO_TOOL_CALL", "continuationActive": False})
        self.assertEqual("stop", decision)


if __name__ == "__main__":
    unittest.main()
