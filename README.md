# msa-toolguard

Java 21 / Spring Boot 4 백엔드에서 AI 에이전트의 도구(Tool) 호출을 **자연어 프롬프트가 아닌 결정론적 코드 경계(Hard Controls)**로 통제하는 가드레일·근거 검증·멱등 실행 라이브러리입니다.

## 왜 만들었는가

대규모 코딩/운영 에이전트 환경에서 자연어 지침("이 도구는 수정하지 마", "근거 없이 결론 내지 마")만으로는 안전 경계를 보장할 수 없습니다.  
`msa-toolguard`는 **ErpMSA**의 분산 폐기 멱등성(G4)과 MCP/A2A 기반 폐기 `PENDING` 조사 에이전트 실전 검증 경험을 범용 백엔드 라이브러리로 추상화했습니다.

- **Tool Guardrail & Policy Engine**: `READ_ONLY`/`MUTATING` 등급, 호출 예산(`maxCalls`), 선행 도구 순서(`requires`), 파라미터 스코프 고정(`boundParams`), `DenyRule` 사전 차단
- **Evidence Ledger & Output Verifier**: 도구 실행마다 불변 근거(`E1..En`)를 자동 기록하고, LLM 최종 구조화 출력을 실제 근거와 교차 검증(불일치 시 자가 수정용 구조화 피드백 반환)
- **Idempotent / Durable Execution**: `MUTATING` 도구에 `requestId` 멱등성·페이로드 충돌 감지를 적용하고, 응답 불명확 시 임의 성공 처리 대신 `PENDING` 상태 유지 및 안전 재개 지원
- **Deterministic Test Kit**: 실제 LLM 호출 없이 Record/Replay(VCR)와 정책·근거 위반 시나리오를 JUnit 5에서 검증

## 모듈 구성

| 모듈 | 역할 | 의존성 |
|---|---|---|
| `toolguard-core` | 정책·예산·스코프·근거 원장·출력 검증·멱등 실행 코어 엔진 | 순수 Java 21 (외부 런타임 의존성 없음) |
| `toolguard-spring-boot-starter` | Spring Boot 4 AutoConfiguration, `@GuardedTool`, YAML 바인딩, Micrometer 메트릭 | Spring Boot 4.1+, Micrometer (optional) |
| `toolguard-test-fixtures` | Record/Replay(VCR) 하네스 및 정책·근거 검증 JUnit 5 단언 유틸 | JUnit 5 |
| `contracts/` | Phase 2 Python MCP/A2A SDK와 공유할 JSON Schema 계약 | 언어 중립 |

## 빠른 빌드 및 로컬 배포

```bash
./gradlew unitTest
./gradlew check
./gradlew publishToMavenLocal
```

로컬 Maven 배포 후 ErpMSA 등 Spring Boot 프로젝트에서 아래처럼 의존성을 추가합니다.

```groovy
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation 'io.github.goldenpage:toolguard-spring-boot-starter:0.1.0-SNAPSHOT'
    testImplementation 'io.github.goldenpage:toolguard-test-fixtures:0.1.0-SNAPSHOT'
}
```
