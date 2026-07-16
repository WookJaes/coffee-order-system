# 도메인 규칙

## 메뉴

- 메뉴 가격은 양수다.
- 주문 가능한 메뉴 상태는 `ACTIVE`다.
- `SOLD_OUT` 메뉴는 주문할 수 없다.

## 포인트

- 충전 금액은 1 이상 100,000 이하다.
- 현재 잔액은 음수가 될 수 없다.
- 충전 시 `points` row가 없으면 생성한다.
- 주문 시 `points` row가 없으면 `POINT_NOT_FOUND`로 실패한다.
- 충전과 사용은 `point_histories`에 잔액 변동 후 값을 남긴다.
- 충전과 주문 결제는 모두 같은 사용자의 `users` row를 `PESSIMISTIC_WRITE`로 먼저 잠근 뒤, 기존 `points` row를 `PESSIMISTIC_WRITE`로 조회·변경한다. 공통 잠금 순서는 `users -> points`다. 포인트가 없는 충전은 사용자 잠금 아래 새 row를 생성하고, 주문은 `POINT_NOT_FOUND`로 실패한다.

## 주문

- 주문은 메뉴 한 개를 대상으로 한다.
- 주문 수량은 1 이상의 정수이며 `orders.quantity`에 저장한다.
- 결제 금액은 주문 시점 메뉴 가격과 수량의 곱을 `order_price`에 저장한다.
- 주문 결제는 `Idempotency-Key`가 필수다.
- 같은 `user_id + idempotency_key`의 메뉴와 수량이 같은 요청은 기존 주문 결과를 반환한다. `remainingPoint`는 해당 주문의 `USE` 이력에 저장된 차감 후 잔액으로 고정한다.
- 같은 키로 메뉴 또는 수량이 다른 요청은 `IDEMPOTENCY_KEY_CONFLICT`로 실패한다.
- 주문, 포인트 차감, 포인트 이력, Outbox 이벤트 저장은 하나의 트랜잭션이다.
- 정상 완료 주문 상태는 `PAID`다.
- 포인트 사용 이력은 `USE` 타입, 주문 금액, 차감 후 잔액과 주문 연관관계를 저장한다. 충전 이력의 주문 연관관계는 없다.
- 주문 1건에는 `order_id` 유니크 제약으로 Outbox 이벤트 1건만 연결한다.
- Outbox 이벤트는 주문 트랜잭션에서 `PENDING`, `retry_count=0`, 즉시 발행 가능한 시각으로 저장한다.
- Publisher는 DB 조건부 갱신으로 `PENDING -> PROCESSING`을 선점하고, Kafka 발행 성공 시 `SENT`로 전이한다.
- Kafka 발행 실패 시 실패 횟수를 증가시킨다. 초기 발행 실패 뒤 최대 재시도 횟수를 초과하면 `FAILED`, 아니면 backoff 뒤 `PENDING`으로 되돌린다.
- `PROCESSING`이 설정된 처리 제한 시간을 넘기면 `PENDING`으로 회복한다. Kafka는 at-least-once이므로 Consumer는 이벤트 ID 멱등 처리가 필요하다.

## 이벤트와 랭킹

- `orders`는 주문과 인기 메뉴의 원본 데이터다.
- `order_events`는 Kafka 발행 대상 Outbox다.
- `OrderPaidEvent`는 주문 원장(`orders.ordered_at`)의 Asia/Seoul 주문 시각을 포함하며, Consumer는 처리 시각과 관계없이 이 주문 시각의 날짜를 일별 Redis 랭킹·완료 상태 키에 사용한다.
- Redis ZSET은 조회 최적화를 위한 파생 데이터다.
- Consumer는 이벤트 ID 기준으로 중복 소비를 방지한다.
- Consumer는 Redis 원자 연산으로 중복 마커 등록과 날짜별 ZSET 주문 수 증가를 함께 처리한다.
- Redis 처리 실패는 Consumer 예외로 전파해 Kafka 재시도·DLT 정책을 적용한다.
- 최근 7일 랭킹의 동점은 메뉴 ID 오름차순으로 결정한다.
- 인기 메뉴 조회는 현재 `ACTIVE` 메뉴만 반환하며, 랭킹에 남은 `SOLD_OUT` 또는 삭제 메뉴는 건너뛰고 다음 메뉴로 최대 3건을 채운다.
- 7일 Redis 랭킹이 비면 `PAID` 주문 원장으로 일자별 랭킹을 재구성한다. 원장도 비면 주문이 없는 정상 상태로 빈 목록을 반환한다.
