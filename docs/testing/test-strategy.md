# 테스트 전략

| 레벨 | 목적 | 예시 |
| --- | --- | --- |
| Unit | 도메인 규칙과 서비스 분기 | 충전 금액, 잔액 부족, 메뉴 상태 |
| API | 요청 검증과 응답 계약 | MockMvc로 HTTP 상태와 응답 메시지 |
| DB Integration | 트랜잭션, JPA, 락 | 동시 주문 후 잔액 음수 방지 |
| Infra Integration | Kafka, Redis, DLT | 중복 이벤트가 ZSET 점수를 중복 증가시키지 않음 |
| Manual HTTP | 실제 앱 흐름 | 메뉴 조회 -> 충전 -> 주문 -> 랭킹 |
| Load | 성능과 오류율 | k6 Load, Stress, Spike |

## 필수 시나리오

- 포인트 충전 성공, 0 이하 충전 실패, 동시 충전
- 포인트가 없는 사용자 동시 충전 시 `points` 한 건 생성과 `CHARGE` 이력 합계 정합성
- 메뉴 없음, 품절 메뉴, 포인트 없음, 잔액 부족, 1 미만 수량
- 수량별 총 결제금액과 포인트 사용 이력·Outbox 금액 정합성
- 같은 멱등성 키 재시도(이후 포인트 변동에도 최초 응답 유지), 같은 키의 다른 요청
- 100자 `Idempotency-Key` 주문 성공과 101자 키의 HTTP 400 공통 오류 응답·서비스 미호출(주문·포인트·사용 이력·Outbox 무변경)
- `POST /api/orders`의 지원하지 않는 HTTP 메서드(405), 지원하지 않는 Content-Type(415), 예상하지 못한 예외의 내부 상세 없는 500 공통 오류 응답 및 ERROR 로그
- 실제 MySQL 또는 Testcontainers MySQL에서 동일 사용자 동시 주문 시 `balance = 초기 잔액 + CHARGE 합계 - USE 합계`, `balance >= 0`, 성공 주문 수 = `USE` 이력 수 = Outbox 수, 각 `USE.balance_after`의 누적 차감 정합성
- 같은 사용자의 충전·주문 교차 동시성에서 `CHARGE - USE`와 최종 잔액 일치, 음수 잔액 방지, 성공 `USE` 이력·주문·Outbox 수 일치
- 교차 실행 중 잔액 부족 주문은 잔액의 충전분 외 주문·`USE` 이력·Outbox를 남기지 않음
- Outbox `PENDING` 발행 성공 후 `SENT`, Kafka 발행 성공 뒤 `SENT` DB 기록 실패 시 실패 backoff·재시도 횟수·`FAILED` 전이 없이 `PROCESSING` 보존과 stale recovery 뒤 at-least-once 재발행, Kafka 발행 실패 뒤 backoff·재시도 횟수·`FAILED` 전이
- stale recovery로 다른 선점 토큰이 이벤트를 다시 선점한 뒤 이전 Publisher의 완료·실패 처리가 상태를 덮어쓰지 않는지 확인
- Kafka `send()` 호출 자체 또는 발행 완료가 `processingTimeout`을 넘어도 유효한 lease를 갱신하는 Publisher의 이벤트를 다른 Publisher가 회수·재선점·재발행하지 않는지, 두 Publisher의 같은 이벤트 동시 선점 방지와 lease 갱신이 멈춘 오래된 `PROCESSING` 회복
- Kafka send executor 거절 또는 작업 미시작 시 Kafka 호출 없이 기존 backoff·재시도 경로로 전환하는지 확인
- Kafka 중복 메시지, Redis 갱신 실패 후 DLT 이동
- Redis Lua 집계의 날짜별 키·TTL·메뉴 주문 수 증가와 같은 `eventId`의 중복 무증가, 자정 경계·지연 소비에도 이벤트 주문 시각의 Asia/Seoul 날짜 키 선택. `orderedAt`이 없는 이전 Kafka 메시지는 주문 원장으로 시각을 보완한다.
- Testcontainers Redis에서 실제 Lua 실행으로 중복 이벤트의 ZSET 점수 무증가와 마커·랭킹 키 TTL 검증. Docker daemon이 없으면 이 테스트는 skip하며, Docker 사용 환경에서는 JUnit 결과의 `skipped=0`을 확인한다.
- 신규 로컬 MySQL DB는 공통 V1/V2/V4/V5와 로컬 전용 V3를 적용해 메뉴 5건과 `users.id=1` 테스트 사용자를 준비한다.
- 신규 비로컬 MySQL DB는 공통 V1/V2/V4/V5만 적용해 메뉴 5건을 준비하고 `users.id=1` 테스트 사용자를 생성하지 않는다.
- 기존 V3 이력이 있는 비로컬 MySQL DB는 V3 하나만 missing legacy migration으로 허용하는 전략에서 Flyway `migrate`가 checksum·누락 migration 오류 없이 동작하며, 기존 사용자 row를 삭제하지 않는다. V3 외 누락된 versioned migration은 실패해야 한다.
- 환경별 seed 변경 뒤 포인트 충전·주문 API의 기존 controller/service 회귀 테스트를 실행한다.
- classpath Lua 리소스 로드와 집계 서비스의 스크립트 주입
- 임베디드 Kafka에서 일반 Redis 실패 시 최초 처리 1회와 재시도 2회(총 3회) 뒤 DLT 이동, 재구성 잠금 예외는 같은 재시도 예산을 넘어도 잠금 해제 뒤 처리, 성공 전 offset 미커밋(RECORD ack), 파티션 수와 Consumer 동시성 정합성
- 최근 7일 Top 3, 동점 정렬, DB 재구성 쿼리
- 인기 메뉴 API의 `rank/menuId/menuName/orderCount` 공통 성공 응답, `ACTIVE` 메뉴 필터와 순위 보충, Redis 비어 있음 뒤 `PAID` 주문 기반 일자별 ZSET 복구
- Redis 재구성 잠금 중 Consumer가 ZSET을 갱신하지 않고 재시도하며, 재구성 뒤 복원된 이벤트 마커로 중복 집계를 막는지 확인
- `DATA`/`EMPTY` 일자 완료 상태가 누락되거나 `DATA` ZSET이 유실되면 DB 원장으로 7일 전체를 다시 복구하고, 토큰이 다른 재구성 잠금은 삭제하지 않는지 확인
- UUID 토큰이 일치할 때만 재구성 lease를 연장하고, 다른 토큰은 lease 연장·잠금 해제를 할 수 없는지 확인
- lease 연장 실패 또는 소유권 상실 시 재구성이 이후 Redis 점수표를 수정하지 않고, UUID 값의 이번 재구성 마커만 조건부 정리하는지 확인
- 잠금 TTL보다 긴 Testcontainers 재구성 중 Consumer가 재시도하고, 완료 뒤 DB로 복원된 이벤트가 ZSET에 정확히 한 번만 반영되는지 확인

코드, DB migration, 인프라 설정을 변경한 작업은 변경 범위의 focused test와 전체 `./gradlew test`를 실행한다. 실제 실행 결과는 `verification-log.md`에 기록한다.
