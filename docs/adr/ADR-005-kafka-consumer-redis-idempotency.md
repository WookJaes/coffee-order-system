# ADR-005 Kafka Consumer는 이벤트 ID 기준 Redis 원자 연산으로 랭킹을 중복 방지

## 상태

Implemented. 결정일: 2026-07-14, 구현일: 2026-07-15.

## 맥락

Issue #7의 `order-paid` Consumer는 Kafka의 at-least-once 전달 환경에서 날짜별 Redis ZSET 점수를 증가시킨다. 같은 메시지를 다시 소비하면 단순 `ZINCRBY`만으로는 메뉴 주문 수가 중복 증가한다. 처리 중 Redis 장애가 발생한 메시지는 재시도 및 DLT 경로가 필요하다.

## 결정

이벤트의 고유 식별자는 `order_events.id`를 사용한다. Consumer는 Redis Lua 스크립트로 이벤트 ID 마커 등록과 해당 날짜 ZSET의 `ZINCRBY`를 한 원자 연산으로 처리한다.

- 랭킹 키: `coffee:ranking:{yyyy-MM-dd}`
- 중복 마커 키: `coffee:ranking:processed:{orderEventId}`
- `OrderPaidEvent`는 `orders.ordered_at`의 Asia/Seoul 주문 시각을 포함하며, Consumer는 처리 시각이 아니라 이 시각의 날짜로 랭킹·완료 상태 키를 선택한다. `orderedAt`이 없는 이전 형식 메시지는 `orderId`로 주문 원장을 조회해 주문 시각을 보완한다.
- 이미 마커가 있으면 점수를 늘리지 않는다.
- 마커와 일자별 랭킹 키는 최근 7일 조회와 재처리 여유를 고려해 최소 8일 이상 보관한다.
- 일시적 실패는 Kafka 재시도 정책으로 처리하고, 정해진 횟수 내에 실패하면 DLT로 보낸다. DLT 재처리는 같은 이벤트 ID를 사용하므로 이미 반영된 점수를 다시 증가시키지 않는다.

## 근거

- 중복 확인과 점수 증가를 Redis 내부에서 원자적으로 수행해 두 명령 사이의 장애 창을 없앤다.
- 이벤트 ID를 기준으로 하므로 주문 단위와 Outbox 재발행 모두에 같은 중복 방지 기준을 적용할 수 있다.
- 날짜별 ZSET은 최근 7일 합산과 만료 관리에 직접 맞는다.

## 대안 비교

| 선택지 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| Lua 기반 이벤트 마커와 `ZINCRBY` | Redis 안에서 중복 확인과 집계를 원자적으로 처리한다. | 스크립트와 키 만료 정책을 운영해야 한다. | 채택 |
| 단순 `ZINCRBY` | 구현이 짧다. | Kafka 재전달마다 점수가 중복 증가한다. | 제외 |
| DB 처리 이력 테이블 | 보관 기간을 길게 가져갈 수 있다. | DB와 Redis 사이의 별도 정합성·상태 관리가 추가된다. | 제외 |

## 결과 (트레이드오프)

Redis 데이터를 유실하면 중복 마커도 함께 유실된다. Redis 랭킹은 파생 데이터이므로 `orders`의 `PAID` 원본으로 재구성하며, 재구성 시에는 해당 기간의 마커와 랭킹 키를 함께 초기화해야 한다.

## 검증 계획

- 같은 `orderEventId` 메시지를 여러 번 소비해도 점수가 한 번만 증가하는지 확인한다.
- 다른 날짜의 이벤트가 각각 해당 일자 ZSET에 반영되는지 확인한다.
- 자정 이후 지연 소비와 재소비도 주문일 ZSET만 한 번 증가시키는지 확인한다.
- Redis 오류 후 재시도와 DLT 재처리에서 중복 점수가 생기지 않는지 확인한다.

## 구현 내용

- `ranking.consumer.*` 환경 설정으로 활성화, topic, group, DLT, 재시도, backoff와 동시성을 분리하고, `ranking.redis.key-ttl`로 Redis 키 TTL을 분리한다. 모든 Ranking 운영 값은 `RANKING_CONSUMER_*`, `RANKING_REDIS_KEY_TTL` 환경 변수로 제공하며, `.env.example`의 재시도 2회는 최초 처리 1회를 포함해 총 3회 시도한 뒤 DLT로 이동하는 예시다. 같은 예시의 동시성 3은 `order-paid`의 3개 파티션을 병렬 처리한다. Kafka Consumer 설정은 `global/config/kafka`, Redis Lua·집계 Bean 설정은 `global/config/redis`에 둔다. 도메인 구현은 Kafka 수신을 `ranking/consumer`, Redis 키·집계를 `ranking/redis`로 분리한다.
- `src/main/resources/scripts/ranking-process-once.lua`의 Redis Lua는 재구성 잠금 키가 있으면 전용 재시도 예외를 반환한다. 잠금이 없을 때만 `SET marker NX EX` 성공 시 `ZINCRBY`, 일자 ZSET `EXPIRE`, 일자 `DATA` 완료 상태를 함께 기록한다. `ranking-release-lock.lua`는 UUID 토큰이 일치할 때만 재구성 잠금을 삭제하고, `ranking-renew-lock.lua`는 같은 토큰일 때만 TTL을 연장한다. 일반 Redis 실패는 `RANKING_CONSUMER_MAX_RETRY_ATTEMPTS`만큼 재시도한 뒤 DLT로 보내되, 재구성 잠금 예외는 같은 backoff로 잠금 해제까지 재시도하므로 lease가 유한 재시도 예산을 초과해도 DLT·offset commit으로 유실되지 않는다. 마커·ZSET·완료 상태 TTL은 `RANKING_REDIS_KEY_TTL`, 잠금 TTL은 `RANKING_REDIS_REBUILD_LOCK_TTL`, 연장 주기는 TTL보다 짧은 `RANKING_REDIS_REBUILD_LOCK_RENEW_INTERVAL`로 설정한다.
- 점수는 `OrderPaidEvent`에 수량 필드가 없고 ADR의 기준이 주문 횟수이므로 이벤트 한 건당 1 증가한다. 이벤트에는 `LocalDateTime orderedAt`을 포함하고, Kafka JSON 직렬화·역직렬화를 위해 Java Time Jackson 모듈을 사용한다.
- `RECORD` ack 및 auto-commit 비활성화로 정상 Redis 처리 반환 전 offset을 기록하지 않는다.
- Compose는 Broker 1개와 기본 파티션 3개를 사용한다. 이미 생성된 토픽은 Kafka의 토픽 설정이 유지되므로, 기존 `order-paid`를 3개로 바꾸려면 파티션 증가 명령을 별도로 실행해야 한다.
