# AGENTS.md

## 작업 흐름

Issue 읽기 -> 관련 문서 읽기 -> 구현 -> 검증 -> 로그 기록

## 문서 라우팅

| 역할 또는 상황 | 먼저 읽을 문서 |
| --- | --- |
| 구현 에이전트 | `docs/agent-workflow.md`, `docs/implementation-guide.md` |
| 리뷰 에이전트 | `docs/agent-workflow.md`, `docs/review-guide.md` |
| QA 에이전트 | `docs/agent-workflow.md`, `docs/qa-guide.md` |
| 작업 로그 기록 | `docs/logging-guide.md` |
| 아키텍처 결정 필요 | `docs/adr/` |
| 기능 명세와 작업 분해 | `docs/ai/context-router.md`, `specs/`, `.specify/memory/constitution.md` |
| 도메인 규칙, API, DB 확인 | `docs/domain/`, `docs/api/`, `docs/db/` |
| 테스트와 완료 판단 | `docs/testing/test-strategy.md`, `docs/testing/verification-log.md` |

## 금지 사항

- 범위 밖 리팩토링 금지
- 불명확하면 질문
