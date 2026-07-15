# 아키텍처 개요

```text
Client
  -> Spring Boot API
      -> Menu / Point / Order services
      -> MySQL (source of truth)
      -> order_events Outbox
  -> Outbox publisher
      -> Kafka topic: order-paid
          -> ranking consumer group
              -> Redis daily ZSET
          -> data-platform consumer group
              -> Mock data platform
          -> repeated failure
              -> DLT
```

## 책임 경계

- `menu`: 메뉴 조회와 판매 상태 검증
- `point`: 포인트 잔액과 이력, 사용자별 DB 비관적 락
- `order`: 주문, 멱등성, 결제 트랜잭션
- `outbox`: 이벤트 저장, 상태 전이, Kafka 발행
- `ranking`: Kafka Consumer, Redis ZSET 갱신, 인기 메뉴 조회
- `global`: 공통 응답, 예외 처리, 설정

## 일관성 기준

- 포인트 잔액과 주문 원장은 MySQL 트랜잭션으로 강하게 일관성을 보장한다.
- Outbox Publisher는 `PENDING -> PROCESSING` 조건부 DB 갱신으로 선점한 뒤 트랜잭션 밖에서 Kafka에 발행한다. 성공은 `SENT`, 실패는 backoff 후 `PENDING` 또는 한도 초과 시 `FAILED`로 기록한다.
- 장시간 `PROCESSING` 이벤트는 다음 Publisher 실행에서 회복한다. 처리 중 프로세스 중단 뒤에는 Kafka가 중복 전송될 수 있으므로 Consumer는 이벤트 ID 기준 멱등 처리한다.
- Kafka와 Redis는 at-least-once 전달을 전제로 멱등 Consumer로 처리한다.
- Redis가 유실되면 `orders`의 최근 7일 `PAID` 주문으로 랭킹을 재구성한다.
