# k6 부하 테스트 결과

## 기록 기준

이 문서는 k6 실행 지표와 threshold 결과만 기록한다. 동일 사용자 주문의 잔액·`USE` 이력·Outbox 정합성은 [DB 검증 결과](db-verification.md)에서 관리하고, API 응답 계약 HTTP 검증은 [HTTP 검증 결과](../http/results.md)에서 관리한다.

## 공통 실행 환경

| 항목 | 값 |
| --- | --- |
| 애플리케이션 | 로컬 단일 Spring Boot 인스턴스 |
| 데이터 저장소 | Docker Compose MySQL, Redis, Kafka |
| 측정 도구 | k6 `grafana/k6:2.0.0` |
| 대상 스크립트 | `k6/same-user-order.js`, `k6/order-load.js`, `k6/order-stress.js`, `k6/order-spike.js` |

## 1회차: 초안 검증

| 날짜 | 프로필 | 스크립트 | 최대 VU | 실행 길이 | 주문 iterations | 주문 오류율 | P95 | threshold | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-07-16 | safe | `same-user-order.js` | 5 | VU별 1회 | 5 | 0.00% | 473.02 ms | PASS | setup 충전 1건, 전체 HTTP 6건 |
| 2026-07-16 | safe | `order-load.js` | 2 | 16초 | 55 | 0.00% | 50.40 ms | PASS | setup 충전 32건, 전체 HTTP 87건 |
| 2026-07-16 | safe | `order-stress.js` | 6 | 16초 | 172 | 0.00% | 171.36 ms | PASS | setup 충전 32건, 전체 HTTP 204건 |
| 2026-07-16 | safe | `order-spike.js` | 8 | 16초 | 235 | 0.00% | 197.52 ms | PASS | setup 충전 32건, 전체 HTTP 267건 |

`주문 오류율`은 주문 응답 분류 기준이며, setup 충전은 전체 HTTP 건수에만 포함한다. 이 safe 실행은 1회차 기준선이며 최대 처리량이나 운영 병목을 확정하지 않는다.

## 2회차: 수정 반영 후 재검증

**상태:** 미실행

수정이 반영된 뒤 1회차와 동일한 safe 프로필로 실행하고 아래 표에 새 행을 추가한다. 실제 실행 전에는 수치나 PASS를 기록하지 않는다.

| 날짜 | 프로필 | 스크립트 | 최대 VU | 실행 길이 | 주문 iterations | 주문 오류율 | P95 | threshold | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| YYYY-MM-DD | safe | `same-user-order.js` | 5 | VU별 1회 | 미측정 | 미측정 | 미측정 | 미실행 | DB 검증 결과와 함께 기록 |
| YYYY-MM-DD | safe | `order-load.js` | 2 | 16초 | 미측정 | 미측정 | 미측정 | 미실행 | 1회차와 비교 |
| YYYY-MM-DD | safe | `order-stress.js` | 6 | 16초 | 미측정 | 미측정 | 미측정 | 미실행 | 1회차와 비교 |
| YYYY-MM-DD | safe | `order-spike.js` | 8 | 16초 | 미측정 | 미측정 | 미측정 | 미실행 | 1회차와 비교 |
