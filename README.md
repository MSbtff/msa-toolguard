# msa-toolguard

Java 21 / Spring Boot 4 백엔드와 Python MCP/A2A 에이전트에서 AI 도구의 호출을 **자연어 프롬프트가 아닌 결정론적 코드 경계(Hard Controls)**로 통제하는 가드레일·근거 검증·멱등 실행 라이브러리입니다.

## 왜 만들었는가

대규모 코딩/운영 에이전트 환경에서 자연어 지침("이 도구는 수정하지 마", "근거 없이 결론 내지 마")만으로는 안전 경계를 보장할 수 없습니다.  
`msa-toolguard`는 **ErpMSA**의 분산 폐기 멱등성(G4)과 MCP/A2A 기반 폐기 `PENDING` 조사 에이전트 실전 검증 경험을 범용 백엔드 라이브러리로 추상화했습니다.

- **Tool Guardrail & Policy Engine**: `READ_ONLY`/`MUTATING` 등급, 호출 예산(`maxCalls`), 선행 도구 순서(`requires`), 파라미터 스코프 고정(`boundParameters`), `DenyRule` 사전 차단
- **Evidence Ledger, Output Verifier & Auto-Repair (`VerifiedAgentRunner`)**: 도구 실행마다 불변 근거(`E1..En`)를 자동 기록하고, LLM 최종 구조화 출력을 실제 근거와 교차 검증하며 불일치 시 구조화된 수정 힌트를 되돌려 제한된 횟수 안에서 자가 수정을 수행
- **Durable & Idempotent Execution (`JdbcToolExecutionStore`, `RedisToolExecutionStore`)**: `MUTATING` 도구에 `requestId` 멱등성·페이로드 충돌 감지를 적용하고, 응답 불명확(`UncertainExecutionException`) 시 임의 성공 처리 대신 `PENDING` 상태 유지 및 재시작 후 안전 재개 지원
  - **JDBC**: `SELECT ... FOR UPDATE` 데이터베이스 행 잠금 + PostgreSQL/MariaDB/H2 호환 영속 복구
  - **Redis**: `SET NX PX` 분산 락 + Lua 원자적 해제 + TTL 기반 영속 상태 복구
- **Audit Log Formatter (`AuditLogFormatter`, `AuditTrailListener`, `Slf4jAuditToolExecutionListener`)**: `contracts/audit-event-v1.schema.json` 호환 JSONL / Markdown 감사 보고서 생성 및 민감 키(`authorization`, `token`, `password`, `api_key` 등) 재귀 마스킹(`[REDACTED]`)
- **Deterministic Test Kit (`toolguard-test-fixtures`)**: 실제 LLM 호출 없이 Record/Replay(VCR)와 정책·근거 위반 시나리오를 JUnit 5에서 검증
- **Python MCP/A2A Harness SDK (`python/`)**: `contracts/*.schema.json` 계약을 공유하는 순수 Python 표준 라이브러리 기반 정책 엔진 + 루프백 MCP 서버 + A2A 1.0 클라이언트/서버 하네스

## 모듈 구성

| 모듈 | 역할 | 의존성 |
|---|---|---|
| `toolguard-core` | 정책·예산·스코프·근거 원장·출력 검증·`VerifiedAgentRunner`·`AuditLogFormatter`·멱등 실행 코어 | 순수 Java 21 (외부 런타임 의존성 없음) |
| `toolguard-spring-boot-starter` | Spring Boot 4 AutoConfiguration, `@GuardedTool`, `JdbcToolExecutionStore`, `RedisToolExecutionStore`, SLF4J 감사 로그, Micrometer 메트릭 | Spring Boot 4.1+, JDBC/Redis/Micrometer (optional) |
| `toolguard-test-fixtures` | Record/Replay(VCR) 하네스 및 정책·근거 검증 JUnit 5 단언 유틸 | JUnit 5 |
| `python/` | Phase 2 Python MCP/A2A Harness SDK (`msa_toolguard`) | 순수 Python 3.10+ 표준 라이브러리 |
| `contracts/` | Java와 Python SDK가 공유하는 언어 중립 JSON Schema 계약 | 언어 중립 |

## 빠른 빌드 및 로컬 배포

```bash
./gradlew unitTest
./gradlew check                 # 단위 테스트 + PostgreSQL/Redis Testcontainers 통합 테스트
./gradlew publishToMavenLocal   # 0.1.0-SNAPSHOT 로컬 Maven 배포
PYTHONPATH=python/src python3 -m unittest discover -s python/tests -v
```

로컬 Maven 배포 후 Spring Boot 프로젝트에서 아래처럼 의존성을 추가합니다.

```groovy
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation 'io.github.msbtff:toolguard-spring-boot-starter:0.1.0-SNAPSHOT'
    testImplementation 'io.github.msbtff:toolguard-test-fixtures:0.1.0-SNAPSHOT'
}
```

## Spring Boot 영속 스토어 및 감사 로그 설정 예시

```yaml
toolguard:
  enabled: true
  default-access-level: READ_ONLY
  default-max-total-calls: 20
  store:
    type: jdbc # in-memory | jdbc | redis
    jdbc:
      table-name: toolguard_executions
      initialize-schema: true
    redis:
      key-prefix: "toolguard:exec:"
      lock-ttl: 30s
      lock-acquire-timeout: 10s
      state-ttl: 24h
  audit:
    enabled: true
```
