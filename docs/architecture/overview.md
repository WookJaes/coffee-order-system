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
- 랭킹 Consumer는 `ranking/consumer`에, Redis 키·Lua 집계 구현은 `ranking/redis`에 둔다. Consumer는 `product-ranking-group`으로 `order-paid`를 소비하고, `resources/scripts/ranking-process-once.lua`를 시작 시 `RedisScript<Long>` Bean으로 로드한다. 이 Lua가 `coffee:ranking:processed:{eventId}` 마커 등록과 `coffee:ranking:{yyyy-MM-dd}`의 `menuId` 점수 1 증가를 원자적으로 처리한다. 랭킹·상태 키의 날짜는 `OrderPaidEvent.orderedAt`을 Asia/Seoul 날짜로 변환한 값이며 Consumer 처리 시각은 사용하지 않는다.
- Consumer는 `RECORD` ack와 auto-commit 비활성화를 사용한다. 동시성은 `RANKING_CONSUMER_CONCURRENCY`로 설정하며, `.env.example`은 `order-paid`의 3개 파티션을 병렬 처리하는 값 3을 예시로 제공한다. Redis 처리 실패는 `RANKING_CONSUMER_RETRY_BACKOFF`, `RANKING_CONSUMER_MAX_RETRY_ATTEMPTS` 설정을 적용한 뒤 `RANKING_CONSUMER_DLT_TOPIC`으로 이동하며, DLT 성공 뒤에만 원본 offset을 커밋한다.
- Docker Compose Kafka는 Broker 1개, 기본 파티션 3개로 실행한다. 기존에 생성된 토픽의 파티션 수는 Compose 기본값 변경으로 바뀌지 않는다.
- Redis가 유실되면 `orders`의 최근 7일 `PAID` 주문으로 랭킹을 재구성한다.
