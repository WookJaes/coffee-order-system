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
- 메뉴 없음, 품절 메뉴, 포인트 없음, 잔액 부족, 1 미만 수량
- 수량별 총 결제금액과 포인트 사용 이력·Outbox 금액 정합성
- 같은 멱등성 키 재시도(이후 포인트 변동에도 최초 응답 유지), 같은 키의 다른 요청
- 동일 사용자 동시 주문 시 잔액과 주문 수의 정합성
- Outbox `PENDING` 발행 성공 후 `SENT`, 발행 실패 뒤 backoff·재시도 횟수·`FAILED` 전이
- 두 Publisher의 같은 이벤트 동시 선점 방지와 오래된 `PROCESSING` 회복
- Kafka 중복 메시지, Redis 갱신 실패 후 DLT 이동
- 최근 7일 Top 3, 동점 정렬, DB 재구성 쿼리

코드, DB migration, 인프라 설정을 변경한 작업은 변경 범위의 focused test와 전체 `./gradlew test`를 실행한다. 실제 실행 결과는 `verification-log.md`에 기록한다.
