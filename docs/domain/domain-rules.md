# 도메인 규칙

## 메뉴

- 메뉴 가격은 양수다.
- 주문 가능한 메뉴 상태는 `ACTIVE`다.
- `SOLD_OUT` 메뉴는 주문할 수 없다.

## 포인트

- 충전 금액은 1 이상 100,000 이하다.
- 충전 후 잔액은 `Integer.MAX_VALUE`(2,147,483,647)를 초과할 수 없다. 초과 충전은 `POINT_BALANCE_OVERFLOW`로 거절하며 잔액과 `CHARGE` 이력을 변경하지 않는다.
- 현재 잔액은 음수가 될 수 없다.
- 충전 시 `points` row가 없으면 생성한다.
- 주문 시 `points` row가 없으면 `POINT_NOT_FOUND`로 실패한다.
- 충전과 사용은 `point_histories`에 잔액 변동 후 값을 남긴다.
- 충전과 주문 결제는 모두 같은 사용자의 `users` row를 `PESSIMISTIC_WRITE`로 먼저 잠근 뒤, 기존 `points` row를 `PESSIMISTIC_WRITE`로 조회·변경한다. 공통 잠금 순서는 `users -> points`다. 포인트가 없는 충전은 사용자 잠금 아래 새 row를 생성하고, 주문은 `POINT_NOT_FOUND`로 실패한다.

## 환경별 테스트 사용자 Seed

- 공통 Flyway migration은 모든 환경에 적용 가능한 스키마와 운영용 메뉴 기준 데이터만 포함한다.
- `users.id=1` 테스트 사용자는 `local` 프로필의 로컬 전용 migration에서만 생성·갱신한다. 로컬이 아닌 환경은 이 사용자를 새로 만들지 않는다.
- 기존에 V3가 적용된 로컬이 아닌 DB는 V3 하나만 missing legacy migration으로 검증·이관하며, 이 전환은 기존 테스트 사용자 row를 삭제하거나 정리하지 않는다. 다른 versioned migration 누락은 오류로 처리한다.

## 주문

- 주문은 메뉴 한 개를 대상으로 한다.
- 주문 수량은 1 이상의 정수이며 `orders.quantity`에 저장한다.
- 결제 금액은 주문 시점 메뉴 가격과 수량의 곱을 `order_price`에 저장한다.
- 주문 결제는 `Idempotency-Key`가 필수이며 공백이 아니고 최대 100자다. 100자 초과 키는 주문·포인트·사용 이력·Outbox 저장 전에 HTTP 400으로 거부한다.
- 같은 `user_id + idempotency_key`의 메뉴와 수량이 같은 요청은 기존 주문 결과를 반환한다. `remainingPoint`는 해당 주문의 `USE` 이력에 저장된 차감 후 잔액으로 고정한다.
- 같은 키로 메뉴 또는 수량이 다른 요청은 `IDEMPOTENCY_KEY_CONFLICT`로 실패한다.
- 주문, 포인트 차감, 포인트 이력, Outbox 이벤트 저장은 하나의 트랜잭션이다.
- 정상 완료 주문 상태는 `PAID`다.
- 포인트 사용 이력은 `USE` 타입, 주문 금액, 차감 후 잔액과 주문 연관관계를 저장한다. 충전 이력의 주문 연관관계는 없다.
- 주문 1건에는 `order_id` 유니크 제약으로 Outbox 이벤트 1건만 연결한다.
- Outbox 이벤트는 주문 트랜잭션에서 `PENDING`, `retry_count=0`, 즉시 발행 가능한 시각으로 저장한다.
- Publisher는 DB 조건부 갱신으로 `PENDING -> PROCESSING`을 선점하고, Kafka 발행 성공 뒤 같은 선점 토큰으로 `SENT` 완료를 기록한다. Kafka 발행 성공 뒤 `SENT` DB 기록이 실패하면 이를 Kafka 발행 실패로 전이하거나 실패 횟수에 반영하지 않고 기존 `PROCESSING`·토큰을 보존한다. lease 갱신이 멈춘 뒤 stale recovery가 이를 `PENDING`으로 되돌려 재발행할 수 있으므로 전달은 at-least-once다. Kafka `send()` 호출과 발행 완료를 기다리는 동안에는 같은 선점 토큰 조건으로 처리 lease 시각을 갱신해 유효한 Publisher가 단순 처리 제한 시간 경과로 선점을 잃지 않는다. 처리 제한 시간은 lease 갱신 여유를 위해 1초 이상이어야 한다.
- Kafka send는 대기열 없는 Spring 관리 executor에서 실행한다. 작업 거절 또는 처리 제한 시간 안에 시작되지 않은 작업은 lease를 연장하지 않고 기존 실패·backoff 경로로 전환한다.
- Kafka 발행 실패 시 실패 횟수를 증가시킨다. 초기 발행 실패 뒤 최대 재시도 횟수를 초과하면 `FAILED`, 아니면 backoff 뒤 `PENDING`으로 되돌린다.
- lease 갱신이 멈춘 `PROCESSING`이 설정된 처리 제한 시간을 넘기면 `PENDING`으로 회복한다. 따라서 Publisher 프로세스 중단 또는 lease 상실 뒤에도 다음 Publisher가 at-least-once 발행을 계속한다. Kafka는 at-least-once이므로 Consumer는 이벤트 ID 멱등 처리가 필요하다.

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
