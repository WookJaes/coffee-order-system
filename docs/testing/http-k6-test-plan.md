# HTTP 및 k6 검증 계획

## 목표

현재 API 응답 계약을 실제 HTTP로 검증하고, 주문·포인트 변경 경로의 동시성 및 부하 관찰값을 기록한다. 운영 성능 튜닝이나 락 정책 변경은 하지 않는다.

## HTTP 검증

| 시나리오 | 요청 | 기대 HTTP/응답 계약 | 추가 확인 |
| --- | --- | --- | --- |
| 포인트 충전 성공 | `POST /api/points/charge` | `200`, `status/message/data`, 메시지 `요청이 성공했습니다.` | `CHARGE` 이력과 잔액 증가 |
| 주문·결제 성공 | `POST /api/orders` + `Idempotency-Key` | `201`, 메시지 `주문 및 결제가 성공적으로 완료되었습니다.` | `PAID` 주문, `USE` 이력, Outbox 1건 |
| 같은 키 재시도 | 동일 요청·동일 키 재전송 | 최초 주문과 같은 `orderId`, `remainingPoint` | 주문·`USE`·Outbox 증가분이 각각 1건 |
| 잔액 부족 | 결제 금액보다 큰 주문 | `400`, `status/message`, 메시지 `포인트가 부족합니다.` | 주문·`USE`·Outbox 증가 없음 |
| 인기 메뉴 | `GET /api/menus/popular` | `200`, `status/message/data`, 메시지 `요청이 성공했습니다.` | `rank/menuId/menuName/orderCount` 형식 |

실패 응답은 `status`, `message`만 반환하며 `data`나 내부 오류 코드를 포함하지 않는다.

## k6 시나리오

| 시나리오 | 대상 | 부하 프로필 | 기록 지표 | 합격 기준 |
| --- | --- | --- | --- | --- |
| 동일 사용자 동시 주문 | `POST /api/orders` | 같은 사용자, VU별 1회, 서로 다른 `Idempotency-Key` | 201/400 응답 수, 예상 밖 오류율, P95 | 음수 잔액 없음, 주문=`USE`=Outbox 증가분 |
| 다중 사용자 주문 Load | `POST /api/orders` | 사용자별 충분한 포인트와 새 `Idempotency-Key`로 낮은 VU부터 점진적으로 증가 | 201/실패 응답 수, 예상 밖 오류율, 평균·P95 | 스크립트 threshold 및 오류 원인 기록 |
| 다중 사용자 주문 Stress | `POST /api/orders` | 사용자별 충분한 포인트와 새 `Idempotency-Key`로 VU를 단계적으로 올리고 유지 후 감소 | 201/실패 응답 수, 예상 밖 오류율, 평균·P95 | 스크립트 threshold 및 오류 원인 기록 |
| 다중 사용자 주문 Spike | `POST /api/orders` | 사용자별 충분한 포인트와 새 `Idempotency-Key`로 목표 VU를 짧게 급증 후 감소 | 201/실패 응답 수, 예상 밖 오류율, 평균·P95 | 스크립트 threshold 및 오류 원인 기록 |

동일 사용자 동시 주문은 한 사용자 행의 락 경합과 정합성을 확인한다. 다중 사용자 주문은 사용자별 테스트 데이터를 준비해 다른 사용자의 결과가 섞이지 않게 한다. 포인트 충전 API는 k6 `setup()`에서 각 synthetic 사용자에게 충분한 포인트를 적립하는 준비 단계로 호출하며, 주문 요청은 모두 서로 다른 `Idempotency-Key`를 사용한다.

## DB 정합성 기준

동일 사용자 동시 주문의 실행 전후 값을 비교한다.

```sql
SELECT balance FROM points WHERE user_id = :userId;

SELECT COUNT(*) FROM orders
WHERE user_id = :userId AND status = 'PAID';

SELECT COUNT(*) FROM point_histories
WHERE user_id = :userId AND type = 'USE';

SELECT COUNT(*)
FROM order_events oe
JOIN orders o ON o.id = oe.order_id
WHERE o.user_id = :userId;
```

`balance >= 0`이어야 하며, 실행 전후 `PAID` 주문·`USE` 이력·Outbox 이벤트의 증가분은 같아야 한다. setup 충전 후에는 사용자별 잔액과 `CHARGE` 이력이 생성됐는지 확인한다.

다중 사용자 시나리오는 각 사용자별 기준을 확인한 뒤 전체 합계도 함께 확인한다.

## 결과 기록

- HTTP 계약 실행 결과는 `http/results.md`에 회차별로 기록한다.
- k6 실행 지표와 threshold는 `k6/results.md`에 회차별로 기록한다.
- 동일 사용자 주문 DB 정합성은 `k6/db-verification.md`에 회차별로 기록한다.
- 실제 실행 명령은 `docs/testing/verification-log.md`에 기록한다.
- 실행하지 못한 시나리오는 수치나 PASS를 기록하지 않는다.
