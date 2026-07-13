# Project Foundation Plan

## Summary

README의 설계 결정을 기능별 정본 문서로 분리하고, Context Router와 AGENTS를 통해 AI 작업 순서를 고정한다. MySQL, Redis, Kafka의 로컬 실행 환경과 `local` 프로필을 함께 제공한다.

## Technical Context

- Language: Java 17
- Framework: Spring Boot 4.1.0
- Storage: MySQL, Redis, Kafka
- Test: JUnit 5, Spring Test, Testcontainers, k6
- Project type: REST API service

## Constitution Check

- 포인트와 주문 정합성 우선: 통과
- 원본 데이터와 Projection 분리: 통과
- 테스트 가능한 변경: 문서 링크와 경로 검증으로 통과
- 기술 결정 근거 기록: ADR 템플릿 사용으로 통과

## Structure Decision

```text
docs/
  ai/ product/ domain/ architecture/ api/ db/ testing/ adr/
specs/001-project-foundation/
src/main/java/com/example/coffeeordersystem/
  global/ user/ menu/ point/ order/ outbox/ ranking/
```

도메인 기능은 해당 패키지 안에서 API, application, domain, infrastructure 책임으로 확장한다. 이번 작업은 디렉터리 경계만 만든다.

로컬 인프라는 `docker-compose.yml`로 실행하고, 애플리케이션은 `application-local.yml`에서 `localhost` 인프라에 연결한다.
