# msa-toolguard

Java 21 / Spring Boot 4 및 Python 3.10+ 환경에서 LLM·AI 에이전트의 도구(Tool) 호출을 결정론적으로 통제하는 **도구 실행 가드레일·승인 게이트·멱등성·근거 검증 미들웨어 라이브러리**입니다.

LLM은 확률적 추론에 기반하므로 시스템 프롬프트만으로는 권한 격리, 고위험 작업 승인, 호출 순서 보장, 중복 쓰기 방지, 타임아웃 격리, 출력 사실 검증을 신뢰 경계(Trust Boundary)로 강제할 수 없습니다.  
`msa-toolguard`는 **정책 엔진(Policy Engine), HITL 승인 게이트(Approval Gate), 가상 스레드 타임아웃 격리, 분산 멱등성 스토어(Idempotency Store), 불변 근거 원장(Evidence Ledger), 출력 검증기(Output Verifier)**를 코드 레벨 실행 레이어로 제공하여 에이전트와 백엔드 시스템 사이에 결정론적 안전 경계를 구축합니다.

> **설계 배경**: MSA 환경의 분산 폐기 멱등성(G4) 처리 및 MCP/A2A 기반 `PENDING` 상태 조사 에이전트 운영 과정에서 드러난 문제(테넌트 스코프 이탈, 재시도 중복 쓰기, 미승인 고위험 변경, 다운스트림 행(hang)에 의한 불확실 상태, 미검증 출력, 결정론적 회귀 테스트·감사 부재)를 범용 백엔드 라이브러리로 일반화했습니다.

---

## 실행 파이프라인

```text
[LLM / AI Agent (Tool Calling · MCP · A2A)]
       │  Tool Invocation / Structured Output
       ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                             msa-toolguard                               │
│  1. Pre-Execution  : SessionDeadline · AccessLevel · DAG · Scope ·      │
│                      DenyRules · ApprovalGate(payloadDigest) · Budget   │
│  2. Execution      : Idempotency Key · Distributed Lock / Lua Fencing · │
│                      Java 21 Virtual-Thread Timeout → PENDING           │
│  3. Post-Execution : EvidenceLedger(E1..En) · Replay Sync · Verifier ·  │
│                      AuditLogFormatter(JSONL / Markdown / Redaction)    │
└─────────────────────────────────────────────────────────────────────────┘
       │  Policy-Compliant, Approved & Verified Call
       ▼
[Backend Service / RDBMS / Redis / Downstream MSA]
```

---

## 핵심 기능

### 1. Pre-Execution: 결정론적 정책·스코프·승인·예산 통제 (`ToolPolicy`, `ApprovalGate`, `ToolSession`)

도구 실행 직전 호스트 측에서 세션 데드라인, 호출 권한, 선행 의존성, 파라미터 스코프, 인자 규칙, Human-in-the-Loop 승인, 호출 예산을 검증하고 위반 시 즉시 `GuardrailException`(`ViolationCode`)으로 차단합니다. 차단된 호출(`IDEMPOTENCY_CONFLICT` 포함)은 호출 예산을 소모하지 않습니다.

- **세션 데드라인 (`sessionTimeout`)**: 세션 전체 실행 시한을 초과하면 즉시 `SESSION_TIMEOUT`으로 차단하고, 실행 시점 잔여 세션 시간을 개별 도구 타임아웃의 상한으로 함께 적용
- **접근 등급 격리 (`AccessLevel`)**: `READ_ONLY` 세션에서 `MUTATING` 도구 호출 차단 (`ACCESS_DENIED`), 미등록 도구 차단 (`UNREGISTERED_TOOL`)
- **호스트 스코프 바인딩 (`boundParameters`)**: `accountId`, `disposalId` 등 테넌트·리소스 식별자를 세션 컨텍스트에서 강제 주입하며, 모델 인자의 스코프 덮어쓰기 차단 (`SCOPE_VIOLATION`)
- **선행 의존성 강제 (`requires`)**: 도구 간 실행 순서를 DAG 조건으로 강제하여 선행 도구 성공 전 후속 도구 호출 차단 (`PREREQUISITE_NOT_MET`)
- **인자 규칙 (`DenyRules`)**: 대소문자 무시 금지 키(`forbidKeys`), 허용 키 화이트리스트(`allowedKeys`), 정수 범위(`integerRange`), 값 패턴 차단(`forbidValuePattern`) (`POLICY_DENIED`)
- **Human-in-the-Loop 승인 게이트 (`ApprovalGate`, `InMemoryApprovalGate`)**:
  - `requireApproval(true)` 고정 승인 및 `requireApprovalWhen((tool, args) -> ...)` 조건부 동적 승인 지원
  - 기본값은 fail-closed(`ApprovalGate.DENY_ALL`)이며, 미승인 시 `APPROVAL_REQUIRED`와 함께 `payloadDigest` 및 승인 티켓 ID 힌트를 반환
  - `InMemoryApprovalGate`는 `(scopeKey, toolName, payloadDigest)` SHA-256 바인딩·TTL 만료·철회(`revoke`)·1회 소비(동일 `requestId`+동일 인자 멱등 재시도만 허용)로 **승인 후 인자 변조 및 승인 재사용 차단**
- **호출 예산 (`maxTotalCalls`, `maxCalls`)**: 세션 전체·도구별 호출 상한 초과 차단 (`CALL_BUDGET_EXCEEDED`)

### 2. Execution: 영속 멱등 실행, 가상 스레드 타임아웃 및 불확실 상태 보존 (`ToolExecutionStore`)

`MUTATING` 도구 호출 시 네트워크 타임아웃·응답 유실·중복 재시도로 인한 중복 쓰기와 상태 불일치를 방지합니다.

- **멱등성 키 강제 및 교차 언어 다이제스트**: `requireIdempotencyKey` 활성화 시 `requestId` 누락 차단 (`MISSING_IDEMPOTENCY_KEY`), `(scopeKey, toolName, requestId)`와 `CanonicalJson.stringifySorted` 기반 SHA-256 다이제스트로 실행 상태 추적 (`contracts/canonical-digest-vectors-v1.json`으로 Java↔Python 바이트 동등성 검증)
- **결정론적 재생, 리플레이 원장 동기화 및 충돌 감지**:
  - 완료된 `requestId` + 동일 인자 재호출 → 하위 도구 재실행 없이 저장 결과 반환 (`replayed = true`)하며, 재시작된 새 세션의 `EvidenceLedger`에도 근거를 자동 동기화해 후속 `requires`·`OutputVerifier` 정합성 유지
  - 동일 `requestId` + 상이한 인자 재호출 → 즉시 차단 (`IDEMPOTENCY_CONFLICT`, 예약 예산 롤백)
- **Java 21 가상 스레드 실행 타임아웃 (`executionTimeout` → `PENDING`) & 불확실 실패 격리 (`UncertainExecutionException`)**:
  - `executionTimeout` 초과 시 비관리 가상 스레드(`Thread.ofVirtual()`)에 인터럽트를 보내고 자동으로 `UncertainExecutionException` → `PENDING`으로 전환(핸들러가 인터럽트를 무시해도 호출자 블로킹 방지)
  - 응답 유실·타임아웃 등으로 원격 커밋 여부를 판별할 수 없는 경우 임의 성공 처리하지 않고 `PENDING` 상태로 영속화하여 프로세스 재시작 이후에도 안전하게 재개
- **실행 스토어 구현체**:
  - `InMemoryToolExecutionStore`: 256-stripe 고정 락 + 충돌 없는 복합 키 + `maxEntries`/`entryTtl` 기반 LRU/TTL 축출 (기본 `10,000`건 / `24h`)
  - `JdbcToolExecutionStore`: `SELECT ... FOR UPDATE` 행 잠금, `lockTimeout`(`setQueryTimeout` + 타임아웃 시 `PENDING` 반환), `purgeTerminalBefore(Instant)` 정리 메서드 및 `(status, updated_at_ms)` 인덱스 지원 (PostgreSQL / MariaDB / H2)
  - `RedisToolExecutionStore`: 충돌 없는 SHA-256 키, `SET NX PX` 분산 락, **Lua 원자적 펜싱 쓰기(`FENCED_SET_SCRIPT` — 만료된 락 보유자의 지연 덮어쓰기 차단)** 및 Lua 원자적 락 해제

### 3. Post-Execution: 근거 원장·출력 검증·자가수정·감사 (`EvidenceLedger`, `OutputVerifier`, `VerifiedAgentRunner`, `AuditLogFormatter`)

도구 실행 결과를 불변 근거로 기록하고, 모델의 최종 구조화 출력을 결정론적으로 교차 검증합니다.

- **불변 근거 원장 (`EvidenceLedger`)**: 모든 도구 실행 결과를 순번(`E1`, `E2`, ...)과 Canonical SHA-256 다이제스트로 호스트 측 원장에 자동 기록하여 모델의 근거 위조 차단
- **출력 검증 및 제한적 자가수정 루프 (`OutputVerifier`, `VerifiedAgentRunner`)**: 필수 도구 실행 여부, 근거 ID 시퀀스 일치, 도메인 불변식(Invariant)을 검증하며 불일치 시 구조화된 수정 피드백을 반환해 `maxAttempts` 상한 내에서 재시도 (초과 시 `VerificationExhaustedException`)
- **구조화 감사 로깅 및 민감정보 마스킹 (`AuditLogFormatter`)**: 모든 허용·차단·핸들러 장애 이벤트를 `contracts/audit-event-v1.schema.json` 호환 JSONL / Markdown 형식으로 직렬화하고 `authorization`, `token`, `password`, `api_key` 등 민감 키를 재귀적으로 `[REDACTED]` 처리

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
            username = System.getenv('GITHUB_ACTOR') ?: findProperty('gpr.user') ?: ''
            password = System.getenv('GITHUB_TOKEN') ?: findProperty('gpr.key') ?: ''
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
  default-session-timeout: 60s      # 선택: 세션 전체 데드라인
  store:
    type: jdbc                      # in-memory | jdbc | redis
    in-memory:
      max-entries: 10000
      entry-ttl: 24h
    jdbc:
      table-name: toolguard_executions
      initialize-schema: true
      lock-timeout: 10s
    redis:
      key-prefix: "toolguard:exec:"
      lock-ttl: 30s
      lock-acquire-timeout: 10s
      state-ttl: 24h
  audit:
    enabled: true
```

### 3. `@GuardedTool` 등록, 승인 게이트 및 세션 실행

도구 메서드에 `@GuardedTool`을 선언하고, 필요 시 `@Bean ApprovalGate`를 등록해 요청 단위 `ToolSessionFactory` 세션에서 모든 도구 호출이 `session.execute(...)`를 경유하도록 구성합니다.

```java
@Component
public class DisposalTools {

    @GuardedTool(
        name = "get_disposal",
        accessLevel = AccessLevel.READ_ONLY,
        maxCalls = 2,
        boundParameters = {"accountId", "disposalId"}, // 호스트 컨텍스트에서 강제 바인딩
        forbidKeys = {"authorization", "api_key"}      // 대소문자 무시 금지 키
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
        allowedKeys = {"mode"},                       // 허용 키 외 인자 차단 (boundParameters 자동 포함)
        idempotency = IdempotencyMode.REQUIRED,       // requestId 필수 강제 (AUTO | REQUIRED | DISABLED)
        requireApproval = true,                       // ApprovalGate 승인 필수
        timeoutMs = 3000                              // 초과 시 가상 스레드 인터럽트 → PENDING
    )
    public ToolExecutionOutcome retryDisposal(Map<String, Object> args) {
        // 원격 커밋 여부가 불확실한 경우 UncertainExecutionException을 던져 PENDING 상태 보존
        return ToolExecutionOutcome.completed(Map.of("status", "COMPLETED"));
    }
}
```

```java
// 자동 구성된 ToolSessionFactory(등록된 ApprovalGate 빈·기본 세션 타임아웃 자동 연동)로 세션 생성
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

실행 결과, 정책 위반, 핸들러 장애 이벤트는 SLF4J 감사 로그(`WARN`/`INFO`)와 Micrometer 메트릭(`toolguard.tool.calls`, `toolguard.tool.violations`, `toolguard.tool.duration`)으로 자동 발행됩니다.

### 4. 근거 기반 출력 검증 및 자가수정 루프 (`VerifiedAgentRunner`)

모델의 최종 구조화 출력이 `EvidenceLedger` 및 도메인 불변식을 만족하지 않으면, 검증 실패 내역을 구조화된 피드백으로 전달해 `maxAttempts` 내에서 재실행합니다.  
외부 LLM·인프라 없이 단독 실행 가능한 전체 예제(자가수정 + `InMemoryApprovalGate` + 가상 스레드 `executionTimeout` → `PENDING` 복구 + 감사 마크다운)는 [`samples/.../DisposalGuardSample.java`](samples/src/main/java/io/github/msbtff/toolguard/samples/DisposalGuardSample.java)에서 확인할 수 있습니다.

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

`python/`(`msa_toolguard`)은 Java 코어와 동일한 정책·승인·타임아웃·근거·감사 JSON Schema 계약을 순수 Python 표준 라이브러리로 구현하며, 멀티스레드 루프백(`127.0.0.1`) MCP 서버(`ThreadingHTTPServer`)와 A2A 1.0 클라이언트·서버 하네스를 제공합니다.

```python
from msa_toolguard import (
    AccessLevel,
    InMemoryApprovalGate,
    LoopbackMcpServer,
    ToolExecutionOutcome,
    ToolPolicy,
    ToolSession,
)

gate = InMemoryApprovalGate()
session = ToolSession(
    scope_key="account-7",
    max_access_level=AccessLevel.MUTATING,
    bound_parameters={"disposalId": "disp-7"},
    approval_gate=gate,
    session_timeout_seconds=60.0,
)
session.register_tool(
    ToolPolicy(name="get_disposal", max_calls=2, bound_parameters=("disposalId",)),
    lambda args: ToolExecutionOutcome.completed(
        {"disposalId": args["disposalId"], "status": "PENDING"}
    ),
)
session.register_tool(
    ToolPolicy(
        name="retry_disposal",
        access_level=AccessLevel.MUTATING,
        requires=("get_disposal",),
        bound_parameters=("disposalId",),
        require_approval=True,
        execution_timeout_ms=3000,
    ),
    lambda args: ToolExecutionOutcome.completed({"retried": True}),
)

tools = [
    {"name": "get_disposal", "description": "Read disposal", "inputSchema": {"type": "object"}},
    {"name": "retry_disposal", "description": "Retry disposal", "inputSchema": {"type": "object"}},
]

# 127.0.0.1 전용 JSON-RPC 2.0 MCP 서버 (상수시간 Bearer 토큰 검증 + Origin 헤더 차단 기본 적용)
with LoopbackMcpServer.for_session(session, tools) as server:
    ...
```

### 6. 결정론적 Record/Replay 테스트 (`toolguard-test-fixtures`)

`ToolTranscriptRecorder`로 도구 호출 트랜스크립트를 녹화·재생하고, `toJson()`/`fromJson()`/`saveTo()`/`loadFrom()`으로 민감 키(`[REDACTED]`)가 자동 마스킹된 버전드 JSON VCR 카세트를 저장·로드해 외부 LLM·네트워크 없이 JUnit 5 회귀 테스트를 실행할 수 있습니다. `GuardrailAssertions`로 `ViolationCode`, 근거 검증 실패, 멱등 재생(`replayed`) 동작을 단언합니다. ([`ReplayHarnessTest.java`](toolguard-test-fixtures/src/test/java/io/github/msbtff/toolguard/testing/ReplayHarnessTest.java))

---

## 모듈 구성

| 모듈 | 역할 | 의존성 |
|---|---|---|
| `toolguard-core` | 정책·예산·스코프·HITL `ApprovalGate`·가상 스레드 타임아웃·근거 원장·출력 검증·`VerifiedAgentRunner`·`AuditLogFormatter`·멱등 실행 코어 | 순수 Java 21 (외부 런타임 의존성 없음) |
| `toolguard-spring-boot-starter` | Spring Boot 4 AutoConfiguration, `@GuardedTool`, `JdbcToolExecutionStore`, `RedisToolExecutionStore`, SLF4J 감사 로그, Micrometer 메트릭 | Spring Boot 4.1+, JDBC/Redis/Micrometer (optional) |
| `toolguard-test-fixtures` | Record/Replay(VCR) 하네스 및 정책·근거 검증 JUnit 5 단언 유틸 | JUnit 5 |
| `samples` | 외부 MSA·LLM 없이 단독 실행 가능한 가드레일·승인·타임아웃·자가수정·감사로그 데모 | `toolguard-core`, `toolguard-test-fixtures` |
| `python/` | Phase 2 Python MCP/A2A Harness SDK (`msa_toolguard`) | 순수 Python 3.10+ 표준 라이브러리 |
| `contracts/` | Java와 Python SDK가 공유하는 언어 중립 JSON Schema 계약 및 SHA-256 교차 검증 골든 벡터(`canonical-digest-vectors-v1.json`) | 언어 중립 |
| `.agents/hooks/` | `toolguard-core` 순수성·시크릿 차단(`PreToolUse`) 및 `contracts/` 계약 검증(`Stop`) 훅 | 순수 Python 3 |

## 빌드 및 테스트

```bash
./gradlew unitTest                          # 단위 테스트 (JUnit @Tag("unit"), H2 32스레드 동시성 포함)
./gradlew jacocoTestCoverageVerification    # JaCoCo 최소 커버리지 게이트 검증 (core LINE 90% / BRANCH 75%)
./gradlew check                             # 단위·커버리지·samples + PostgreSQL/MariaDB/Redis Testcontainers 통합 테스트
./gradlew publishToMavenLocal               # 로컬 Maven 배포
PYTHONPATH=python/src python3 -m unittest discover -s python/tests -v
python3 -m unittest discover -s .agents/hooks -p 'test_*.py' -v
```
