# msa-toolguard Python MCP/A2A Harness SDK (Phase 2)

Java `toolguard-core` 및 `contracts/*.schema.json` · `contracts/canonical-digest-vectors-v1.json` 계약을 공유하는 순수 Python 3.10+ 표준 라이브러리 기반 AI 에이전트 가드레일·하네스 SDK입니다.

## 주요 기능

- **Deterministic Policy & Session Engine (`ToolPolicy`, `ToolSession`)**:
  - `READ_ONLY`/`MUTATING` 등급, 호출 예산(차단 시 예산 롤백), 선행 도구(`requires`), 호스트 고정 파라미터(`bound_parameters`), 대소문자 무시 `forbid_keys_rule` / `allowed_keys_rule` / `integer_range_rule`
  - `requestId` 멱등성·충돌 감지, 재시작/새 세션 리플레이 시 `EvidenceLedger` 자동 동기화, `UncertainExecutionError` 시 `PENDING` 유지
  - **Bounded `InMemoryToolExecutionStore`**: 256-stripe 락 + 충돌 없는 튜플 키 + `max_entries`/`entry_ttl_seconds` 기반 LRU/TTL 축출
  - **Durable `SQLiteToolExecutionStore` (표준 라이브러리 `sqlite3`)**: WAL 모드 + `BEGIN IMMEDIATE` 락 + 재시작 후 `PENDING`/`COMPLETED`/`REJECTED` 복구 + 락 타임아웃 시 `PENDING` 반환 + `purge_terminal_before()` 정리 지원
- **Human-in-the-Loop Approval Gate (`ApprovalGate`, `InMemoryApprovalGate`)**:
  - `require_approval=True` 고정 승인 및 `approval_predicate` 조건부 동적 승인, fail-closed 기본값(`DENY_ALL_APPROVAL_GATE`)
  - `(scope_key, tool_name, payload_digest)` 바인딩·TTL·철회(`revoke`)·1회 소비(동일 `request_id` 멱등 재시도 허용)로 승인 후 인자 변조 차단
- **Execution & Session Timeouts**:
  - `ToolPolicy(execution_timeout_ms=...)`: 시간 초과 시 자동으로 `UncertainExecutionError` → `PENDING` 전환
  - `ToolSession(session_timeout_seconds=...)`: 세션 데드라인 초과 시 `ViolationCode.SESSION_TIMEOUT` 차단
- **Evidence Ledger & Auto-Repair (`EvidenceLedger`, `OutputVerifier`, `VerifiedAgentRunner`)**: `contracts/evidence-record-v1.schema.json` 호환 불변 근거 발급 및 결정론적 자가 수정 피드백 루프
- **Audit Log Formatter (`AuditLogFormatter`, `AuditTrailListener`)**: `contracts/audit-event-v1.schema.json` 호환 JSONL/Markdown 감사 로그 및 시크릿 자동 마스킹
- **Loopback MCP Harness (`LoopbackMcpServer`)**: `127.0.0.1` 전용 멀티스레드(`ThreadingHTTPServer`) JSON-RPC 2.0 MCP 서버 (상수시간 Bearer 검증, `Origin` 헤더 차단, `requestId` 인자 분리, `executionStatus` 보존 및 `-32603` 장애 응답)
- **Loopback A2A 1.0 Harness (`LoopbackA2AClient`, `LoopbackA2AServer`)**: Agent Card 검증, `/message:send`, `/tasks/{id}` 폴링, `messageId` 멱등성 및 409/429 제어

## 테스트 실행

```bash
PYTHONPATH=src python3 -m unittest discover -s tests -v
```
