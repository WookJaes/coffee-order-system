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
- Kafka와 Redis는 at-least-once 전달을 전제로 멱등 Consumer로 처리한다.
- Redis가 유실되면 `orders`의 최근 7일 `PAID` 주문으로 랭킹을 재구성한다.
