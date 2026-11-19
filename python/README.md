# msa-toolguard Python MCP/A2A Harness SDK (Phase 2)

Java `toolguard-core` / `contracts/*.schema.json` 계약을 공유하는 순수 Python 표준 라이브러리 기반 AI 에이전트 하네스 SDK입니다.

## 주요 기능

- **Deterministic Policy & Session Engine (`ToolPolicy`, `ToolSession`)**: `READ_ONLY`/`MUTATING` 등급, 호출 예산, 선행 도구(`requires`), 호스트 고정 파라미터(`bound_parameters`), `DenyRule`, `requestId` 멱등성·충돌 감지, `UncertainExecutionError` 시 `PENDING` 유지
- **Evidence Ledger & Auto-Repair (`EvidenceLedger`, `OutputVerifier`, `VerifiedAgentRunner`)**: `contracts/evidence-record-v1.schema.json` 호환 불변 근거 발급 및 결정론적 자가 수정 피드백 루프
- **Audit Log Formatter (`AuditLogFormatter`, `AuditTrailListener`)**: `contracts/audit-event-v1.schema.json` 호환 JSONL/Markdown 감사 로그 및 시크릿 자동 마스킹
- **Loopback MCP Harness (`LoopbackMcpServer`)**: `127.0.0.1` 전용 JSON-RPC 2.0 MCP 서버 (Bearer 상수시간 검증, `Origin` 헤더 차단, 크기 제한)
- **Loopback A2A 1.0 Harness (`LoopbackA2AClient`, `LoopbackA2AServer`)**: Agent Card 검증, `/message:send`, `/tasks/{id}` 폴링, `messageId` 멱등성 및 409/429 제어

## 테스트 실행

```bash
PYTHONPATH=src python3 -m unittest discover -s tests -v
```
