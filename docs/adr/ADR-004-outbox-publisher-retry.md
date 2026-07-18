# ADR-004 Outbox 발행은 상태 전이와 재시도로 처리

## 상태

Implemented. 결정일: 2026-07-14, 구현일: 2026-07-15.

## 맥락

Issue #6은 주문 트랜잭션에서 저장된 `order_events`를 `order-paid` Kafka 토픽으로 발행해야 한다. 주문 요청 안에서 Kafka를 직접 호출하면 브로커 장애가 결제를 롤백시키고, 여러 애플리케이션 인스턴스가 같은 `PENDING` 이벤트를 동시에 발행하면 중복 전송될 수 있다.

## 결정

별도 Outbox Publisher가 주기적으로 발행 대상을 조회한다. 후보 조회 뒤 `WHERE id = ? AND status = PENDING` 조건부 DB 갱신으로 `PENDING -> PROCESSING` 선점을 원자적으로 저장한 뒤 Kafka 발행을 수행한다. 선점 토큰을 저장해 같은 실행이 선점한 이벤트만 완료 처리한다. 배치를 순차 발행할 때 활성 Kafka 전송이 처리 제한 시간을 넘으면, 같은 선점 토큰의 `PROCESSING` 배치 전체 lease를 갱신한다.

발행 성공 시 `SENT`로, 실패 시 실패 횟수를 증가시킨다. 초기 발행 실패 뒤 최대 재시도 횟수를 초과하면 `FAILED`, 남아 있으면 `PENDING`으로 되돌리고 다음 시도 시각을 늦춘다. 기본 최대 재시도 횟수는 3회이므로 총 네 번째 실패에서 `FAILED`다. 따라서 Issue #6 구현에는 `PROCESSING`, 선점 시각·토큰, 재시도 시각, 실패 사유를 저장할 스키마 확장이 포함된다.

## 근거

- 주문 결제 트랜잭션은 Kafka 상태와 분리되어 결제 성공 여부가 브로커 가용성에 좌우되지 않는다.
- 선점 상태를 저장하면 여러 Publisher 인스턴스가 같은 이벤트를 동시에 보내는 일을 줄인다.
- 실패 횟수와 사유를 남겨 무한 재시도와 조용한 이벤트 유실을 피한다.

## 대안 비교

| 선택지 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| 상태 선점 후 비동기 발행 | 주문과 발행을 분리하고 다중 인스턴스 경합을 관리한다. | 상태·재시도 컬럼과 복구 작업이 필요하다. | 채택 |
| 주문 트랜잭션 안에서 Kafka 발행 | 구현 흐름이 짧다. | Kafka 장애가 결제를 실패시키고 DB/Kafka 원자성을 보장하지 못한다. | 제외 |
| `PENDING`을 잠근 채 Kafka 발행 | 별도 상태가 없어 보인다. | 외부 호출 동안 DB 락을 오래 잡고 장애 복구가 불명확하다. | 제외 |

## 결과 (트레이드오프)

`PROCESSING` 상태에서 프로세스가 중단될 수 있으므로 Publisher는 lease 갱신이 멈춘 이벤트를 다시 `PENDING`으로 복구하는 정책을 가져야 한다. Kafka는 at-least-once 전송이므로 Consumer는 중복을 처리해야 하며, 이 결정은 ADR-005와 함께 적용한다. Kafka 발행이 `processingTimeout`보다 오래 걸릴 수 있으므로, 발행을 진행 중인 유효한 선점은 같은 토큰 조건으로 배치 전체 lease 시각을 갱신한다. Publisher 또는 갱신이 멈추면 그 토큰의 이벤트 모두 기존 stale recovery cutoff를 지나 회복 대상이 된다.

### 구현 내용

- `OutboxEventClaimService`는 짧은 `REQUIRES_NEW` 트랜잭션에서 오래된 `PROCESSING`을 복구하고 조건부 갱신으로 선점한다.
- `OutboxPublisherService`는 트랜잭션 밖에서 `KafkaTemplate.send()` 호출을 별도 Future로 실행하고 메시지 키로 `orderId`를 사용한다. 호출과 완료를 기다리는 전 기간 `processingTimeout`의 1/3 주기로 같은 선점 토큰의 모든 `PROCESSING` 배치 이벤트 lease 시각을 조건부 갱신하며, 갱신 또는 완료 시점 토큰 검증이 거절되면 상태를 변경하지 않는다. `processingTimeout`은 lease 갱신 여유를 위해 최소 1초다. `OrderPaidEvent`에는 연결된 주문의 실제 `orderedAt`도 담아 Consumer가 지연 소비 시에도 주문일 랭킹 키를 선택할 수 있게 한다.
- Kafka send는 대기열 없는 단일 Spring 관리 executor에서 실행한다. executor 거절 또는 처리 제한 시간 안에 시작되지 않은 작업은 취소하고 기존 실패·backoff 경로로 전환하므로, 시작되지 않은 작업이 lease만 무기한 갱신하는 zombie claim을 만들지 않는다.
- `OutboxEventCompletionService`는 짧은 비관적 잠금 트랜잭션에서 선점 토큰을 재확인한 후 `SENT` 또는 Kafka 발행 실패 상태를 기록한다. Kafka 발행이 성공한 뒤 `SENT` 기록 트랜잭션만 실패하면 Publisher는 이를 Kafka 발행 실패로 취급하지 않아 retry count·backoff·`FAILED`를 변경하지 않는다. 이때 이벤트는 기존 `PROCESSING`·토큰으로 남고 lease 갱신이 멈춘 뒤 stale recovery가 `PENDING`으로 회복해 재발행할 수 있다. 이는 DB/Kafka 원자성을 만들지 않는 대신, Consumer `eventId` 멱등성을 전제로 at-least-once 전달을 보존한다.
- `outbox.publisher.*` 설정으로 토픽, 주기, 배치 크기, 최대 실패 횟수, backoff, 처리 제한 시간을 조정한다.

## 검증 계획

- Kafka 발행이 처리 제한 시간을 넘는 동안에도 여러 Publisher 실행 시 현재 이벤트와 같은 배치의 대기 이벤트 lease·선점·상태 전이가 일관적인지 확인한다.
- lease 갱신이 멈춘 `PROCESSING` 이벤트를 다음 Publisher가 회복하는지 확인한다.
- Kafka 발행 실패 후 재시도·최종 실패 상태와 시도 횟수를 확인한다.
- Kafka 발행 성공 뒤 `SENT` DB 기록 실패가 Kafka 실패 상태로 덮어써지지 않고, stale recovery 뒤 재발행되는지 확인한다.
- stale recovery로 토큰이 바뀐 뒤 이전 Publisher의 완료·실패 처리가 현재 선점 상태를 변경하지 않는지 확인한다.
- 주문 성공 후 Kafka 장애가 발생해도 주문·포인트·Outbox 레코드가 유지되는지 확인한다.
