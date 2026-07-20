# Context Router

작업 시작 전 이 표에서 해당 기능의 정본 문서를 찾는다. 관련 없는 문서를 넓게 읽지 않는다.

| 작업 | 필수 문서 | 추가 확인 |
| --- | --- | --- |
| 프로젝트 기반 구성 | `specs/001-project-foundation/`, `docs/onboarding/local-runtime.md` | `docs/product/github-issues.md` |
| 메뉴 조회 | `docs/product/requirements.md`, `docs/domain/domain-rules.md`, `docs/api/api-spec.md` | `docs/db/erd.md` |
| 포인트 충전 | `docs/domain/domain-rules.md`, `docs/api/api-spec.md` | `docs/db/erd.md`, `docs/testing/test-strategy.md` |
| 주문 및 결제 | `docs/domain/domain-rules.md`, `docs/architecture/overview.md`, `docs/api/api-spec.md` | `docs/db/erd.md`, 관련 ADR |
| Kafka 이벤트와 Outbox | `docs/architecture/overview.md`, `docs/domain/domain-rules.md` | `docs/db/erd.md`, 관련 ADR |
| 인기 메뉴 랭킹 | `docs/architecture/overview.md`, `docs/api/api-spec.md` | `docs/db/erd.md` |
| DB 스키마 변경 | `docs/db/erd.md`, 관련 ADR | `docs/testing/test-strategy.md` |
| 테스트 또는 k6 | `docs/testing/test-strategy.md` | `docs/testing/verification-log.md` |

작업 중 요구사항, API 계약, 데이터 모델 또는 기술 결정이 바뀌면 구현과 같은 변경에서 해당 정본 문서를 갱신한다.
