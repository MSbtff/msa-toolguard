# msa-toolguard

AI 에이전트(LLM)에게 백엔드 API·DB 호출 권한을 줄 때, AI가 사고를 치지 못하도록 호출 앞뒤를 감싸는 **안전장치 미들웨어 라이브러리**입니다. (Java 21 / Spring Boot 4 & Python 3.10+)

LLM은 확률적으로 동작하기 때문에 프롬프트에 *"조회만 해"*, *"중복 실행하지 마"*, *"없는 말 지어내지 마"*라고 적어두어도 예외 상황이나 프롬프트 인젝션에서 규칙을 어길 수 있습니다.  
`msa-toolguard`는 **말로 부탁하는 대신, 항상 똑같이 동작하는 코드(`if`문·DB/Redis 락·근거 대조)**로 AI와 백엔드 사이에 안전 경계를 세웁니다.

> **탄생 배경**: **MSA** 환경에서 분산 폐기 멱등성(G4)과 MCP/A2A 기반 폐기 `PENDING` 조사 에이전트를 실전 검증하며 부딪힌 문제(권한 이탈, 중복 쓰기, 환각 보고, 테스트·감사 불가)를 범용 백엔드 라이브러리로 추출했습니다.

---

## 한눈에 보는 구조

```text
[AI 에이전트 / LLM]
       │  "이 도구 호출할게!" / "이게 내 최종 결론이야!"
       ▼
┌──────────────────────────────────────────────────────────┐
│                      msa-toolguard                       │
│  1. 호출 직전 : 권한 · 순서 · 횟수 · 파라미터 변조 차단     │
│  2. 실행 중   : 중복 쓰기 방지 & 응답 유실 시 PENDING 보존  │
│  3. 응답 직후 : 실행 영수증(E1..En) 대조 & 환각 자동 재시도 │
└──────────────────────────────────────────────────────────┘
       │  검증을 통과한 안전한 호출만 전달
       ▼
[실제 백엔드 서비스 / DB / MSA API]
```

## 없으면 생기는 사고 vs `msa-toolguard`가 막는 방법

### 1. 호출 직전 (가드레일) — AI가 선을 넘기 전에 차단
* **없으면**: "7번 유저의 폐기 건(`disp-001`)만 조회해"라고 시켰는데, AI가 헷갈려서 **8번 유저 데이터를 조회**하거나 조회도 안 해보고 **수정/삭제 API를 무한 호출**합니다.
* **적용 후** (`ToolPolicy`, `ToolSession`):
  * **접근 등급 강제**: `READ_ONLY` 세션에서는 `MUTATING` 도구 호출 자체를 차단 (`ACCESS_DENIED`)
  * **호스트 파라미터 고정 (`boundParameters`)**: `accountId`, `disposalId`처럼 서버가 정한 스코프 값은 세션이 자동 주입하고, AI가 다른 값으로 덮어쓰면 즉시 차단 (`SCOPE_VIOLATION`)
  * **선행 도구 순서 강제 (`requires`)**: 예) `get_disposal`이 성공하기 전에는 `retry_disposal` 호출 불가 (`PREREQUISITE_NOT_MET`)
  * **호출 예산 & 인자 규칙**: 세션 전체(`maxTotalCalls`)·도구별(`maxCalls`) 호출 횟수 상한과 `DenyRule`(범위 제한, 금지 키 차단 등) 적용

### 2. 실행 중 (멱등 실행) — 네트워크 장애 시 중복 쓰기·데이터 꼬임 방지
* **없으면**: AI가 재고 차감·폐기 재처리 같은 **쓰기 API**를 호출했는데 타임아웃으로 응답이 끊깁니다. AI가 실패한 줄 알고 **같은 요청을 다시 보내면 중복 차감**이 발생하고, 반대로 임의 성공 처리하면 데이터가 꼬입니다.
* **적용 후** (`ToolExecutionStore` — In-Memory / JDBC / Redis):
  * `MUTATING` 도구는 `requestId`를 필수로 요구하고 `(scopeKey, toolName, requestId)` + 인자 SHA-256 해시로 실행 상태를 기록
  * **이미 완료된 `requestId` 재호출** → 실제 도구를 다시 실행하지 않고 저장된 결과를 재반환 (`replayed = true`)
  * **같은 `requestId` + 다른 인자** → 충돌로 즉시 차단 (`IDEMPOTENCY_CONFLICT`)
  * **응답 유실 등 불확실한 오류 (`UncertainExecutionException`)** → 임의로 성공 처리하지 않고 **`PENDING` 상태를 유지**, 프로세스 재시작 이후에도 안전하게 재개
  * **영속 스토어 지원**:
    * `JdbcToolExecutionStore`: `SELECT ... FOR UPDATE` 행 잠금 (PostgreSQL / MariaDB / H2)
    * `RedisToolExecutionStore`: `SET NX PX` 분산 락 + Lua 원자적 락 해제 + TTL 상태 복구

### 3. 최종 응답 직후 (근거 검증 · 자가수정 · 감사 로그) — AI의 거짓 보고 반려
* **없으면**: DB 조회 결과는 `재고 이동 있음(matched = true)`이었는데, AI가 최종 답변에서 환각으로 **"이동 기록 없음"**이라고 보고하거나 호출한 적 없는 가짜 근거 번호를 지어냅니다.
* **적용 후** (`EvidenceLedger`, `OutputVerifier`, `VerifiedAgentRunner`, `AuditLogFormatter`):
  * **자동 영수증 발급**: 도구가 실행될 때마다 불변 근거 레코드(`E1`, `E2`, ... + SHA-256 다이제스트)를 세션 장부에 자동 기록 (AI가 조작 불가)
  * **결정론적 교차 검증 & 자동 재시도**: `OutputVerifier`가 필수 도구 실행 여부·근거 ID 일치·도메인 불변식을 대조하고, 틀리면 `VerifiedAgentRunner`가 구조화된 수정 힌트를 AI에 되돌려보내 제한된 횟수(`maxAttempts`) 안에서 자가수정을 수행
  * **감사 로그 & 민감정보 마스킹**: 모든 허용·차단 이력을 `contracts/audit-event-v1.schema.json` 호환 JSONL / Markdown 보고서로 남기고 `authorization`, `token`, `password`, `api_key` 등은 재귀적으로 `[REDACTED]` 처리

---

## 어떻게 사용하는가?

### 1. 의존성 추가 (Gradle)

로컬 개발 중에는 `mavenLocal()`을, Docker/CI 환경에서는 `v*` 태그로 자동 배포되는 **GitHub Packages**의 불변 버전을 참조합니다.

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

### 2. Spring Boot 설정 (`application.yml`)

```yaml
toolguard:
  enabled: true
  default-access-level: READ_ONLY   # 기본 세션은 읽기 전용으로 안전하게 시작
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

### 3. Spring Boot에서 도구 감싸고 실행하기

백엔드 도구 메서드에 `@GuardedTool`을 붙이고, AI 요청마다 `ToolSessionFactory`로 세션을 만들어 `session.execute(...)`를 통해 호출합니다.

```java
@Component
public class DisposalTools {

    @GuardedTool(
        name = "get_disposal",
        accessLevel = AccessLevel.READ_ONLY,
        maxCalls = 2,
        boundParameters = {"accountId", "disposalId"} // 서버가 강제 주입·고정할 파라미터
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
        requires = {"get_disposal"},                  // get_disposal 완료 후에만 호출 가능
        boundParameters = {"accountId", "disposalId"},
        requireIdempotencyKey = true                  // requestId 필수
    )
    public ToolExecutionOutcome retryDisposal(Map<String, Object> args) {
        // 응답 유실 등 불확실한 실패 시 UncertainExecutionException을 던지면 PENDING 유지
        return ToolExecutionOutcome.completed(Map.of("status", "COMPLETED"));
    }
}
```

```java
// Spring Boot가 자동 구성한 ToolSessionFactory 주입 후, 에이전트 요청 단위로 세션 생성
ToolSession.Builder builder = toolSessionFactory.newSession("account-" + accountId)
    .maxAccessLevel(AccessLevel.MUTATING) // 쓰기가 승인된 흐름에서만 상향
    .bindParameter("accountId", accountId)
    .bindParameter("disposalId", disposalId);

AnnotatedToolBinder.registerAll(builder, disposalTools);
ToolSession session = builder.build();

// LLM Tool Call 콜백에서 직접 메서드를 부르지 않고 반드시 session.execute(...)를 경유
ToolExecutionOutcome detail = session.execute("get_disposal", Map.of());
ToolExecutionOutcome retried = session.execute("retry_disposal", Map.of("mode", "safe"), "req-uuid-1");
```

SLF4J 감사 로그와 Micrometer 메트릭(`toolguard.tool.calls`, `toolguard.tool.violations`)이 자동으로 함께 기록됩니다.

### 4. 최종 출력 근거 검증 & 자가수정 루프 (`VerifiedAgentRunner`)

LLM이 내놓은 최종 결론이 실제 도구 실행 영수증(`EvidenceLedger`)과 맞지 않으면, 검증 실패 사유를 구조화된 피드백으로 되돌려 최대 `maxAttempts` 내에서 자동 교정합니다.  
LLM 없이 바로 실행해볼 수 있는 전체 예제는 [`samples/.../DisposalGuardSample.java`](samples/src/main/java/io/github/msbtff/toolguard/samples/DisposalGuardSample.java)에 있습니다.

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

### 5. Python MCP / A2A 에이전트에서 사용하기

`python/`(`msa_toolguard`)은 Java 코어와 동일한 정책·근거·감사 계약을 순수 Python 표준 라이브러리로 구현하며, 로컬(`127.0.0.1`) 전용 MCP 서버와 A2A 1.0 클라이언트/서버 하네스를 제공합니다.

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

# 127.0.0.1 전용 JSON-RPC 2.0 MCP 서버 (Bearer 상수시간 검증 + Origin 헤더 차단 자동 적용)
with LoopbackMcpServer.for_session(session, tools) as server:
    ...
```

### 6. LLM 없이 결정론적으로 테스트하기 (`toolguard-test-fixtures`)

`ToolTranscriptRecorder`로 도구 실행을 녹화(Record)·재생(Replay)하고 `GuardrailAssertions`로 차단 코드·근거 검증 실패·멱등 재응답을 JUnit 5에서 외부 LLM/네트워크 없이 검증할 수 있습니다. ([`ReplayHarnessTest.java`](toolguard-test-fixtures/src/test/java/io/github/msbtff/toolguard/testing/ReplayHarnessTest.java))

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
