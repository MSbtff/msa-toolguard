# msa-toolguard 작업 기준

- 한국어로 작업한다.
- `toolguard-core`는 순수 Java 21만 사용하며 Spring이나 외부 런타임 의존성을 추가하지 않는다.
- 모든 테스트 클래스는 `@Tag("unit")` 또는 `@Tag("integration")` 중 정확히 하나를 명시한다.
- 정책·근거 스키마(`contracts/`) 변경 시 Java 구현과 계약을 함께 갱신한다.
- 응답이 불확실한 도구 실행(`UncertainExecutionException`)은 임의로 성공 처리하지 않고 `PENDING`으로 유지한다.
- GitHub에 변경 사항을 올릴 때는 `main`에 직접 푸시하지 않고 작업 브랜치에서 커밋·푸시한 뒤 `gh pr create`로 PR을 생성하고, `gh pr checks`로 GitHub Actions CI(`verify`) 통과를 확인한다.
