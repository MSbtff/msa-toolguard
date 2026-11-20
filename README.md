# msa-toolguard

Java 21 / Spring Boot 4 및 Python 3.10+ 환경에서 LLM·AI 에이전트의 도구(Tool) 호출을 결정론적으로 통제하는 **도구 실행 가드레일·멱등성·근거 검증 미들웨어 라이브러리**입니다.

LLM은 확률적 추론에 기반하므로 시스템 프롬프트만으로는 권한 격리, 호출 순서 보장, 중복 쓰기 방지, 출력 사실 검증을 신뢰 경계(Trust Boundary)로 강제할 수 없습니다.  
`msa-toolguard`는 **정책 엔진(Policy Engine), 분산 멱등성 스토어(Idempotency Store), 불변 근거 원장(Evidence Ledger), 출력 검증기(Output Verifier)**를 코드 레벨 실행 레이어로 제공하여 에이전트와 백엔드 시스템 사이에 결정론적 안전 경계를 구축합니다.

> **설계 배경**: MSA 환경의 분산 폐기 멱등성(G4) 처리 및 MCP/A2A 기반 `PENDING` 상태 조사 에이전트 운영 과정에서 드러난 문제(테넌트 스코프 이탈, 재시도 중복 쓰기, 미검증 출력, 결정론적 회귀 테스트·감사 부재)를 범용 백엔드 라이브러리로 일반화했습니다.

---

## 실행 파이프라인

```text
[LLM / AI Agent (Tool Calling · MCP · A2A)]
       │  Tool Invocation / Structured Output
       ▼
┌───────────────────────────────────────────────────────────────────┐
│                           msa-toolguard                           │
│  1. Pre-Execution  : Policy · AccessLevel · Scope · Budget · DAG  │
│  2. Execution      : Idempotency Key · Distributed Lock · PENDING │
│  3. Post-Execution : EvidenceLedger(E1..En) · Verifier · Audit    │
└───────────────────────────────────────────────────────────────────┘
       │  Policy-Compliant & Verified Call
       ▼
[Backend Service / RDBMS / Redis / Downstream MSA]
```

---

## 핵심 기능

### 1. Pre-Execution: 결정론적 정책·스코프·예산 통제 (`ToolPolicy`, `ToolSession`)

도구 실행 직전 호스트 측에서 호출 권한, 파라미터 스코프, 호출 순서, 실행 예산을 검증하고 위반 시 즉시 `GuardrailException`(`ViolationCode`)으로 차단합니다.

- **접근 등급 격리 (`AccessLevel`)**: `READ_ONLY` 세션에서 `MUTATING` 도구 호출 차단 (`ACCESS_DENIED`), 미등록 도구 차단 (`UNREGISTERED_TOOL`)
- **호스트 스코프 바인딩 (`boundParameters`)**: `accountId`, `disposalId` 등 테넌트·리소스 식별자를 세션 컨텍스트에서 강제 주입하며, 모델 인자의 스코프 덮어쓰기 차단 (`SCOPE_VIOLATION`)
- **선행 의존성 강제 (`requires`)**: 도구 간 실행 순서를 DAG 조건으로 강제하여 선행 도구 성공 전 후속 도구 호출 차단 (`PREREQUISITE_NOT_MET`)
- **호출 예산 및 인자 규칙 (`maxTotalCalls`, `maxCalls`, `DenyRule`)**: 세션 전체·도구별 호출 상한 초과 차단 (`CALL_BUDGET_EXCEEDED`) 및 범위 제한·금지 키 검증 (`POLICY_DENIED`)

### 2. Execution: 영속 멱등 실행 및 불확실 상태 보존 (`ToolExecutionStore`)

`MUTATING` 도구 호출 시 네트워크 타임아웃이나 재시도로 인한 중복 쓰기와 상태 불일치를 방지합니다.

- **멱등성 키 강제 및 페이로드 검증**: `requireIdempotencyKey` 활성화 시 `requestId` 누락 차단 (`MISSING_IDEMPOTENCY_KEY`), `(scopeKey, toolName, requestId)`와 정규화(Canonical JSON) 인자의 SHA-256 다이제스트로 실행 상태 추적
- **결정론적 재생 및 충돌 감지**:
  - 완료된 `requestId` + 동일 인자 재호출 → 하위 도구 재실행 없이 저장 결과 반환 (`replayed = true`)
  - 동일 `requestId` + 상이한 인자 재호출 → 즉시 차단 (`IDEMPOTENCY_CONFLICT`)
- **불확실 실패 격리 (`UncertainExecutionException`)**: 응답 유실·타임아웃 등으로 원격 커밋 여부를 판별할 수 없는 경우 임의 성공 처리하지 않고 `PENDING` 상태로 영속화하여 프로세스 재시작 이후에도 안전하게 상태 재검증·재개
- **실행 스토어 구현체**:
  - `InMemoryToolExecutionStore`: 단일 JVM 및 단위 테스트용 인메모리 스토어
  - `JdbcToolExecutionStore`: `SELECT ... FOR UPDATE` 행 잠금 기반 영속 스토어 (PostgreSQL / MariaDB / H2)
  - `RedisToolExecutionStore`: `SET NX PX` 분산 락, Lua 원자적 락 해제 및 TTL 기반 상태 복구

### 3. Post-Execution: 근거 원장·출력 검증·자가수정·감사 (`EvidenceLedger`, `OutputVerifier`, `VerifiedAgentRunner`, `AuditLogFormatter`)

도구 실행 결과를 불변 근거로 기록하고, 모델의 최종 구조화 출력을 결정론적으로 교차 검증합니다.

- **불변 근거 원장 (`EvidenceLedger`)**: 모든 도구 실행 결과를 순번(`E1`, `E2`, ...)과 Canonical SHA-256 다이제스트로 호스트 측 원장에 자동 기록하여 모델의 근거 위조 차단
- **출력 검증 및 제한적 자가수정 루프 (`OutputVerifier`, `VerifiedAgentRunner`)**: 필수 도구 실행 여부, 근거 ID 시퀀스 일치, 도메인 불변식(Invariant)을 검증하며 불일치 시 구조화된 수정 피드백을 반환해 `maxAttempts` 상한 내에서 재시도 (초과 시 `VerificationExhaustedException`)
- **구조화 감사 로깅 및 민감정보 마스킹 (`AuditLogFormatter`)**: 모든 허용·차단 이벤트를 `contracts/audit-event-v1.schema.json` 호환 JSONL / Markdown 형식으로 직렬화하고 `authorization`, `token`, `password`, `api_key` 등 민감 키를 재귀적으로 `[REDACTED]` 처리

---

## 빠른 시작 (Quick Start)

### 1. Gradle 의존성 설정

로컬 개발 환경에서는 `mavenLocal()`을, CI·컨테이너 빌드에서는 `v*` 태그로 배포되는 **GitHub Packages**의 불변 버전을 참조합니다.

```groovy
repositories {
    mavenLocal()
    maven {
        url = uri('https://maven.pkg.github.com/MSbtff/msa-toolguard')
        credentials {
            username = System.getenv('GITHUB_ACTOR') ?: findProperty('gpr.user')
            password = System.getenv('GITHUB_TOKEN') ?: findProperty('gpr.key')
        }
    }
    mavenCentral()
}

dependencies {
    implementation 'io.github.msbtff:toolguard-spring-boot-starter:0.1.2'
    testImplementation 'io.github.msbtff:toolguard-test-fixtures:0.1.2'
}
```

### 2. Spring Boot 프로퍼티 (`application.yml`)

```yaml
toolguard:
  enabled: true
  default-access-level: READ_ONLY   # 기본 세션은 읽기 전용으로 제한
  default-max-total-calls: 20
  store:
    type: jdbc                      # in-memory | jdbc | redis
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

### 3. `@GuardedTool` 등록 및 세션 실행

도구 메서드에 `@GuardedTool`을 선언하고, 요청 단위로 `ToolSessionFactory`에서 세션을 생성해 모든 도구 호출이 `session.execute(...)`를 경유하도록 구성합니다.

```java
@Component
public class DisposalTools {

    @GuardedTool(
        name = "get_disposal",
        accessLevel = AccessLevel.READ_ONLY,
        maxCalls = 2,
        boundParameters = {"accountId", "disposalId"} // 호스트 컨텍스트에서 강제 바인딩
    )
    public ToolExecutionOutcome getDisposal(Map<String, Object> args) {
        return ToolExecutionOutcome.completed(Map.of(
            "disposalId", args.get("disposalId"),
            "status", "PENDING"
        ));
    }

    @GuardedTool(
        name = "retry_disposal",
        accessLevel = AccessLevel.MUTATING,
        requires = {"get_disposal"},                  // 선행 도구 실행 완료 필수
        boundParameters = {"accountId", "disposalId"},
        requireIdempotencyKey = true                  // requestId 필수 강제
    )
    public ToolExecutionOutcome retryDisposal(Map<String, Object> args) {
        // 원격 커밋 여부가 불확실한 경우 UncertainExecutionException을 던져 PENDING 상태 보존
        return ToolExecutionOutcome.completed(Map.of("status", "COMPLETED"));
    }
}
```

```java
// 자동 구성된 ToolSessionFactory를 통해 에이전트 요청 단위 세션 생성
ToolSession.Builder builder = toolSessionFactory.newSession("account-" + accountId)
    .maxAccessLevel(AccessLevel.MUTATING) // 쓰기 승인이 필요한 워크플로우에서만 상향
    .bindParameter("accountId", accountId)
    .bindParameter("disposalId", disposalId);

AnnotatedToolBinder.registerAll(builder, disposalTools);
ToolSession session = builder.build();

// LLM Tool Call 핸들러에서 직접 메서드를 호출하지 않고 session.execute(...) 경유
ToolExecutionOutcome detail = session.execute("get_disposal", Map.of());
ToolExecutionOutcome retried = session.execute("retry_disposal", Map.of("mode", "safe"), "req-uuid-1");
```

실행 결과와 정책 위반 이벤트는 SLF4J 감사 로그와 Micrometer 메트릭(`toolguard.tool.calls`, `toolguard.tool.violations`)으로 자동 발행됩니다.

### 4. 근거 기반 출력 검증 및 자가수정 루프 (`VerifiedAgentRunner`)

모델의 최종 구조화 출력이 `EvidenceLedger` 및 도메인 불변식을 만족하지 않으면, 검증 실패 내역을 구조화된 피드백으로 전달해 `maxAttempts` 내에서 재실행합니다.  
외부 LLM·인프라 없이 단독 실행 가능한 전체 예제는 [`samples/.../DisposalGuardSample.java`](samples/src/main/java/io/github/msbtff/toolguard/samples/DisposalGuardSample.java)에서 확인할 수 있습니다.

```java
OutputVerifier verifier = OutputVerifier.builder()
    .requireExactEvidenceOrder(true)
    .requireToolExecuted("get_disposal")
    .requireToolExecuted("get_stock_movements")
    .addInvariant(
        "MATCHED_CONCLUSION",
        (out, ledger) -> Boolean.TRUE.equals(ledger.find("E2").orElseThrow().facts().get("matched"))
            && "matched".equals(out.conclusion()),
        "결론이 재고 이동 근거와 일치해야 합니다.",
        "E2에서 이동이 확인되었으므로 conclusion을 'matched'로 수정하세요."
    )
    .build();

VerifiedAgentRunner runner = VerifiedAgentRunner.builder(verifier).maxAttempts(3).build();

VerifiedRunResult result = runner.runOrThrow(session, (s, priorFailures, attempt) -> {
    String repairPrompt = priorFailures.isEmpty()
        ? ""
        : VerifiedAgentRunner.formatRepairFeedback(VerificationResult.fail(priorFailures), s.evidenceLedger());
    return callLlmAndReturnStructuredOutput(s, repairPrompt);
});
```

### 5. Python MCP / A2A 하네스 (`python/`)

`python/`(`msa_toolguard`)은 Java 코어와 동일한 정책·근거·감사 JSON Schema 계약을 순수 Python 표준 라이브러리로 구현하며, 루프백(`127.0.0.1`) 전용 MCP 서버와 A2A 1.0 클라이언트·서버 하네스를 제공합니다.

```python
from msa_toolguard import (
    AccessLevel,
    LoopbackMcpServer,
    ToolExecutionOutcome,
    ToolPolicy,
    ToolSession,
)

session = ToolSession(
    max_access_level=AccessLevel.READ_ONLY,
    bound_parameters={"disposalId": "disp-7"},
)
session.register_tool(
    ToolPolicy(name="get_disposal", max_calls=2, bound_parameters=("disposalId",)),
    lambda args: ToolExecutionOutcome.completed(
        {"disposalId": args["disposalId"], "status": "PENDING"}
    ),
)

tools = [{"name": "get_disposal", "description": "Read disposal", "inputSchema": {"type": "object"}}]

# 127.0.0.1 전용 JSON-RPC 2.0 MCP 서버 (상수시간 Bearer 토큰 검증 + Origin 헤더 차단 기본 적용)
with LoopbackMcpServer.for_session(session, tools) as server:
    ...
```

### 6. 결정론적 Record/Replay 테스트 (`toolguard-test-fixtures`)

`ToolTranscriptRecorder`로 도구 호출 트랜스크립트를 녹화·재생하고, `GuardrailAssertions`로 `ViolationCode`, 근거 검증 실패, 멱등 재생(`replayed`) 동작을 외부 LLM·네트워크 없이 JUnit 5에서 회귀 검증할 수 있습니다. ([`ReplayHarnessTest.java`](toolguard-test-fixtures/src/test/java/io/github/msbtff/toolguard/testing/ReplayHarnessTest.java))

---

## 모듈 구성

| 모듈 | 역할 | 의존성 |
|---|---|---|
| `toolguard-core` | 정책·예산·스코프·근거 원장·출력 검증·`VerifiedAgentRunner`·`AuditLogFormatter`·멱등 실행 코어 | 순수 Java 21 (외부 런타임 의존성 없음) |
| `toolguard-spring-boot-starter` | Spring Boot 4 AutoConfiguration, `@GuardedTool`, `JdbcToolExecutionStore`, `RedisToolExecutionStore`, SLF4J 감사 로그, Micrometer 메트릭 | Spring Boot 4.1+, JDBC/Redis/Micrometer (optional) |
| `toolguard-test-fixtures` | Record/Replay(VCR) 하네스 및 정책·근거 검증 JUnit 5 단언 유틸 | JUnit 5 |
| `samples` | 외부 MSA·LLM 없이 단독 실행 가능한 가드레일·자가수정·감사로그 데모 | `toolguard-core`, `toolguard-test-fixtures` |
| `python/` | Phase 2 Python MCP/A2A Harness SDK (`msa_toolguard`) | 순수 Python 3.10+ 표준 라이브러리 |
| `contracts/` | Java와 Python SDK가 공유하는 언어 중립 JSON Schema 계약 | 언어 중립 |
| `.agents/hooks/` | `toolguard-core` 순수성·시크릿 차단(`PreToolUse`) 및 `contracts/` 계약 검증(`Stop`) 훅 | 순수 Python 3 |

## 빌드 및 테스트

```bash
./gradlew unitTest              # 단위 테스트 (JUnit @Tag("unit"))
./gradlew check                 # 단위 테스트 + samples + PostgreSQL/Redis Testcontainers 통합 테스트
./gradlew publishToMavenLocal   # 0.1.0-SNAPSHOT 로컬 Maven 배포
PYTHONPATH=python/src python3 -m unittest discover -s python/tests -v
python3 -m unittest discover -s .agents/hooks -p 'test_*.py' -v
```
