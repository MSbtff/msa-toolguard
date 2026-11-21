# msa-toolguard Contracts

Java(`toolguard-core`, `toolguard-spring-boot-starter`)와 Python MCP/A2A Harness SDK(`python/msa_toolguard`)가 공유하는 언어 중립적 JSON Schema 및 다이제스트 계약입니다.

- `tool-policy-v1.schema.json`: 도구별 접근 등급(`READ_ONLY` / `MUTATING`), 호출 예산, 선행 도구 조건, 스코프 고정 파라미터 계약
- `evidence-record-v1.schema.json`: 도구 실행 근거 레코드(`E1`, `E2`, ...)와 결정론적 SHA-256 다이제스트 계약
- `audit-event-v1.schema.json`: 도구 실행·차단 감사 로그(JSONL) 계약
- `canonical-digest-vectors-v1.json`: Java `CanonicalJson`/`CanonicalDigest`와 Python `canonical_json`/`canonical_sha256` 간 바이트 단위 해시 동등성을 교차 검증하는 골든 테스트 벡터
