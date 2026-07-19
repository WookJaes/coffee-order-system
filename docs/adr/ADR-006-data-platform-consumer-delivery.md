# ADR-006 데이터 플랫폼 전달은 전용 Kafka Consumer와 HTTP 멱등 키를 사용한다

## 상태

Implemented. 결정일: 2026-07-19.

## 맥락

Outbox Publisher는 `order-paid` Kafka 발행까지 보장하지만 데이터 플랫폼 HTTP 전달은 주문 트랜잭션에 포함하면 외부 장애가 결제를 롤백할 수 있다. Kafka Consumer의 offset 기록 전 프로세스가 중단되면 이미 성공한 HTTP 요청도 재전달될 수 있다.

## 결정

`data-platform-group`은 랭킹과 별도로 `order-paid`를 소비한다. `RestClient`는 연결·응답 timeout을 사용해 `eventId`, `userId`, `menuId`, `paymentAmount`를 전송하고 `Idempotency-Key: order-paid:{eventId}`를 보낸다. HTTP 2xx만 성공이다.

- 연결 실패, timeout, 5xx는 Kafka `DefaultErrorHandler`의 설정된 fixed backoff로 재시도한다.
- 4xx는 복구 불가능한 오류로 분류해 재시도 없이 `order-paid.data-platform.DLT`로 보낸다.
- DLT send result 오류는 recoverer가 전파하며, DLT 발행 성공 뒤에만 recovered offset을 commit한다.
- `RECORD` ack와 auto-commit 비활성화로 HTTP 성공 반환 전 offset을 기록하지 않는다.

## 결과

주문·포인트·Outbox 저장 및 랭킹 처리는 데이터 플랫폼 장애와 분리된다. HTTP 성공 직후 offset commit 전 중단에서는 중복 POST가 남지만 수신 시스템은 같은 Idempotency-Key를 하나의 논리 이벤트로 수집한다. 이 결정은 exactly-once나 분산 트랜잭션을 도입하지 않는다.
