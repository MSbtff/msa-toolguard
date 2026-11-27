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

import core_purity_guard
import stop_contract_verify

REPO_ROOT = HOOKS_DIR.parents[1].resolve()


class ToolGuardHooksTest(unittest.TestCase):
    def test_hooks_json_is_valid(self):
        config = json.loads((HOOKS_DIR.parent / "hooks.json").read_text(encoding="utf-8"))
        self.assertIn("msa-toolguard-guards", config)
        self.assertIn("PreToolUse", config["msa-toolguard-guards"])
        self.assertIn("Stop", config["msa-toolguard-guards"])

    def test_file_tools_allow_in_repo_and_deny_secrets_purity_and_external_writes(self):
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
        self.assertEqual("allow", decision)

        decision, _ = core_purity_guard.evaluate({
            "toolCall": {"name": "edit_file", "args": {"Path": str(REPO_ROOT / "README.md"), "Replacement": "ok"}}
        })
        self.assertEqual("allow", decision)

        external_path = str(REPO_ROOT.parent / "ErpMSA/README.md")
        decision, _ = core_purity_guard.evaluate({
            "toolCall": {"name": "edit_file", "args": {"Path": external_path, "Replacement": "x"}}
        })
        self.assertEqual("ask", decision)

        decision, _ = core_purity_guard.evaluate({
            "toolCall": {"name": "read_file", "args": {"Path": external_path}}
        })
        self.assertEqual("ask", decision)

    def test_shell_commands_allow_safe_local_dev_deny_destructive_and_ask_unknown(self):
        for safe_cmd in (
            "./gradlew check --stacktrace",
            "./gradlew publishToMavenLocal",
            "PYTHONPATH=python/src python3 -m unittest discover -s python/tests -v",
            "git status --short && git diff",
            "git add . && git commit -m 'test'",
            "git merge main",
            "git rebase origin/main",
            "git cherry-pick abc1234",
            "git revert abc1234",
            "git restore --staged README.md",
            "git reset --soft HEAD~1",
            "git reset --soft HEAD~1 && echo --hard",
            "git push origin main && tail -f README.md",
            "git reflog -n 10",
            "git mv README.md README2.md",
            "git rm --cached temp.txt",
            "git config --local user.name dev",
            "git describe --tags --always",
            "git blame README.md",
            "git worktree list",
            "gh run list -R MSbtff/msa-toolguard --limit 3",
            "gh pr create --base main --title 'docs: update' --body 'summary'",
            "gh pr checks 1 --watch",
            "gh pr diff 1",
            "gh pr edit 1 --title 'updated'",
            "gh pr comment 1 --body 'LGTM'",
            "gh pr merge 1 --squash --delete-branch",
            "gh issue list --limit 10",
            "gh issue view 1",
            "gh issue create --title 'bug' --body 'details'",
            "docker info --format '{{.ServerVersion}}'",
        ):
            with self.subTest(safe_cmd=safe_cmd):
                decision, _ = core_purity_guard.evaluate({
                    "toolCall": {"name": "run_command", "args": {"CommandLine": safe_cmd}}
                })
                self.assertEqual("allow", decision)

        for denied_cmd in (
            "rm -rf toolguard-core",
            "git rm -rf toolguard-core",
            "git push --force origin main",
            "git push -f origin main",
            "git reset --hard HEAD~1",
            "git reset -q --hard HEAD~1",
            "git clean -d -f",
            "git clean --force",
            "docker compose down -v",
            "docker volume prune -f",
            "cat .env",
            "cat secrets/jwt.pem",
            "sudo rm /tmp/x",
        ):
            with self.subTest(denied_cmd=denied_cmd):
                decision, _ = core_purity_guard.evaluate({
                    "toolCall": {"name": "run_command", "args": {"CommandLine": denied_cmd}}
                })
                self.assertEqual("deny", decision)

        for ask_cmd in (
            "curl https://example.com",
            "python3 -c 'print(1)'",
            "git -C ../ErpMSA status",
            "git config --global user.name dev",
            "git config --system core.editor vim",
            "git worktree add ../other-wt",
            "git mv README.md ../outside.md",
            "gh repo delete MSbtff/msa-toolguard",
            "gh release delete v0.1.2",
            "ls -la /Users/jonghwan/Desktop",
            "ls | grep foo",
        ):
            with self.subTest(ask_cmd=ask_cmd):
                decision, _ = core_purity_guard.evaluate({
                    "toolCall": {"name": "run_command", "args": {"CommandLine": ask_cmd}}
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
