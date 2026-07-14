# ADR 운영 규칙

## 상태 표기

ADR은 `Proposed`, `Accepted`, `Superseded`, `Rejected` 상태를 사용한다. 각 문서에 상태와 결정일을 기록한다.

## 검증 표기

실제로 실행해 확인한 내용과 이후 구현에서 검증할 계획을 구분한다. 계획된 검증을 현재 동작으로 기록하지 않는다.

## 목록

| ADR | 결정 |
| --- | --- |
| [ADR-001](ADR-001-point-charge-user-lock.md) | 포인트 충전 사용자 행 비관적 락 |
| [ADR-002](ADR-002-db-pessimistic-lock.md) | 주문 포인트 차감에 DB 비관적 락 사용 |
| [ADR-003](ADR-003-order-outbox.md) | 주문 완료 이벤트에 Transactional Outbox 사용 |
| [ADR-004](ADR-004-outbox-publisher-retry.md) | Outbox 발행 상태 전이와 재시도 |
| [ADR-005](ADR-005-kafka-consumer-redis-idempotency.md) | 이벤트 ID 기준 Redis 랭킹 중복 방지 |
| [ADR-006](ADR-006-seven-day-ranking-read.md) | 최근 7일 Redis 랭킹 합산과 동점 정렬 |
