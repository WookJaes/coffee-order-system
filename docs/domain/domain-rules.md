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
- 주문 포인트 차감은 `points` row를 `PESSIMISTIC_WRITE`로 조회한 뒤 수행한다.

## 주문

- 주문은 메뉴 한 개를 대상으로 한다.
- 주문 수량은 1 이상의 정수이며 `orders.quantity`에 저장한다.
- 결제 금액은 주문 시점 메뉴 가격과 수량의 곱을 `order_price`에 저장한다.
- 주문 결제는 `Idempotency-Key`가 필수다.
- 같은 `user_id + idempotency_key`의 메뉴와 수량이 같은 요청은 기존 주문 결과를 반환한다.
- 같은 키로 메뉴 또는 수량이 다른 요청은 `IDEMPOTENCY_KEY_CONFLICT`로 실패한다.
- 주문, 포인트 차감, 포인트 이력, Outbox 이벤트 저장은 하나의 트랜잭션이다.
- 정상 완료 주문 상태는 `PAID`다.
- 포인트 사용 이력은 `USE` 타입, 주문 금액, 차감 후 잔액과 주문 연관관계를 저장한다. 충전 이력의 주문 연관관계는 없다.
- 주문 1건에는 `order_id` 유니크 제약으로 Outbox 이벤트 1건만 연결한다.
- Outbox 이벤트는 이번 범위에서 `PENDING`으로만 저장하며 Kafka 발행·상태 전환은 후속 범위다.

## 이벤트와 랭킹

- `orders`는 주문과 인기 메뉴의 원본 데이터다.
- `order_events`는 Kafka 발행 대상 Outbox다.
- Redis ZSET은 조회 최적화를 위한 파생 데이터다.
- Consumer는 이벤트 ID 기준으로 중복 처리하지 않는다.
- 최근 7일 랭킹의 동점은 메뉴 ID 오름차순으로 결정한다.
