# ADR-007 사용자 잠금 뒤 포인트·멱등성 이력 locking read

## 상태
Accepted. 결정일·구현일: 2026-07-16.

## 맥락

기존 정책은 `users` row를 `PESSIMISTIC_WRITE`로 잠근 뒤 `points`와 멱등성 주문·`USE` 이력을 일반 조회했다. MySQL `REPEATABLE-READ`에서 주문의 최초 멱등성 일반 조회가 일관 읽기 스냅샷을 만든 뒤 사용자 잠금을 기다리면, 대기 후에도 이전 `points.balance`와 이력을 읽을 수 있었다. 그 결과 서로 다른 키의 성공 주문 5건이 모두 95,500P를 기록해 18,000P가 유실됐고, 같은 키 동시 재시도는 기존 주문 또는 `USE` 이력을 보지 못했다.

## 결정

충전·주문 결제는 공통으로 `users`를 먼저 `PESSIMISTIC_WRITE`로 잠근다. 기존 포인트는 이어서 `PESSIMISTIC_WRITE` locking read로 읽어 최신 커밋 잔액을 변경한다. 주문의 사용자 잠금 뒤 멱등성 재확인과 기존 응답의 `USE` 이력 조회도 locking read로 수행한다.

포인트가 없는 충전은 사용자 잠금 아래 생성하고, 포인트가 없는 주문은 `POINT_NOT_FOUND`로 실패한다. 주문·포인트 차감·`USE` 이력·Outbox 저장은 기존과 같이 하나의 트랜잭션이며 API 계약은 변경하지 않는다.

## 근거와 트레이드오프

- `PESSIMISTIC_WRITE`는 MySQL에서 current read이므로 사용자 잠금 대기 뒤 최신 row를 읽는다.
- 모든 잔액 변경 경로는 `users -> points` 순서를 사용한다. 멱등성 재확인은 사용자 잠금 뒤에만 실행되어 교차 경로와 역순 잠금을 만들지 않는다.
- 같은 사용자 요청은 직렬화되어 응답 시간이 증가할 수 있다. 성능 관측과 부하 검증은 별도 이슈에서 다룬다.

## 검증

- Testcontainers MySQL 8.4에서 서로 다른 키 5건 주문의 잔액 77,500P, `USE`·주문·Outbox 각 5건을 검증한다.
- 같은 사용자 충전·주문 교차 실행의 `초기 + CHARGE - USE` 산식과 음수 방지를 검증한다.
- 같은 키 동시 재시도의 `orderId`·`remainingPoint` 고정 및 잔액 부족 무변경을 검증한다.
