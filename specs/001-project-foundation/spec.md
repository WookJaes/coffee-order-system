# Feature Specification: Project Foundation

**Feature Branch**: `001-project-foundation`

## Goal

커피 주문 시스템 구현 전에 문서 정본, 패키지 경계, 테스트 기준을 고정해 이후 기능 작업이 같은 규칙을 따른다.

## User Scenarios

### User Story 1 - 구현자 작업 준비 (Priority: P1)

구현자는 기능을 시작하기 전에 Context Router에서 필요한 요구사항, 도메인 규칙, API, DB, 테스트 문서를 찾을 수 있다.

**Acceptance Scenarios**:

1. Given 메뉴, 포인트, 주문, 랭킹 작업 중 하나가 주어졌을 때, When Context Router를 읽으면 Then 필수 문서와 추가 확인 문서를 알 수 있다.
2. Given 설계 변경이 필요할 때, When ADR을 작성하면 Then 상태, 맥락, 결정, 근거, 대안 비교, 결과를 기록할 수 있다.

### User Story 2 - 검증 가능한 완료 판단 (Priority: P2)

구현자는 기능 완료 전에 변경 위험에 맞는 테스트를 선택하고 실제 결과를 기록할 수 있다.

**Acceptance Scenarios**:

1. Given DB 락 또는 Kafka 변경이 있을 때, When 테스트 전략을 읽으면 Then DB 또는 Infra Integration 테스트가 필요함을 알 수 있다.
2. Given 검증을 실행했을 때, When Verification Log에 기록하면 Then 명령, 결과, 남은 위험을 추적할 수 있다.

## Requirements

- README의 설계와 모순되는 새 기술 결정을 추가하지 않는다.
- 문서 정본은 요구사항, 도메인 규칙, 아키텍처, API, DB, 테스트로 분리한다.
- 문서와 로컬 실행 구조만 만들며, 비즈니스 API와 엔티티 구현은 포함하지 않는다.
- 모든 문서 링크와 AGENTS 라우팅 경로는 저장소 안에서 유효해야 한다.

## Success Criteria

- 기능별 시작 문서를 `docs/ai/context-router.md`에서 1분 안에 찾을 수 있다.
- `specs/001-project-foundation/`에 명세, 계획, 작업 목록이 있다.
- AGENTS에서 선언한 문서 경로가 모두 존재한다.
