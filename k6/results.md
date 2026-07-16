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

**실행일:** 2026-07-16

| 날짜 | 프로필 | 스크립트 | 최대 VU | 실행 길이 | 주문 iterations | 주문 오류율 | P95 | threshold | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-07-16 | safe | `same-user-order.js` | 5 | VU별 1회 | 5 | 0.00% | 473.02 ms | PASS | setup 충전 1건, 전체 HTTP 6건 |
| 2026-07-16 | safe | `order-load.js` | 2 | 16초 | 55 | 0.00% | 50.40 ms | PASS | setup 충전 32건, 전체 HTTP 87건 |
| 2026-07-16 | safe | `order-stress.js` | 6 | 16초 | 172 | 0.00% | 171.36 ms | PASS | setup 충전 32건, 전체 HTTP 204건 |
| 2026-07-16 | safe | `order-spike.js` | 8 | 16초 | 235 | 0.00% | 197.52 ms | PASS | setup 충전 32건, 전체 HTTP 267건 |

`주문 오류율`은 주문 응답 분류 기준이며, setup 충전은 전체 HTTP 건수에만 포함한다. 이 safe 실행은 1회차 기준선이며 최대 처리량이나 운영 병목을 확정하지 않는다.

## 2회차: PR #27 수정 반영 후 재검증

**실행일:** 2026-07-16

**실행 환경:** 로컬 Spring Boot 단일 인스턴스(8080), Docker Compose MySQL 8.4.5·Redis·Kafka, Docker `grafana/k6:2.0.0`. 매 시나리오 직전에 새 synthetic 사용자를 만들었고, k6 Docker 실행에는 `host.docker.internal:8080`을 사용했다. P95·P99를 수집했다.

| 날짜 | 프로필 | 스크립트 | 최대 VU | 실행 길이 | 주문 iterations | 주문 오류율 | P95 | threshold | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-07-16 | safe | `same-user-order.js` | 5 | VU별 1회 | 5 | 0.00% | 68.13 ms | PASS | P99 69.38 ms, 16.48 orders/s, 전체 HTTP 6건, 서로 다른 키 |
| 2026-07-16 | safe | `order-load.js` | 2 | 14초 | 54 | 0.00% | 47.60 ms | PASS | P99 63.15 ms, 3.79 orders/s, 전체 HTTP 62건, 새 사용자 2명·setup 충전 8건 |
| 2026-07-16 | safe | `order-stress.js` | 6 | 16초 | 208 | 0.00% | 44.13 ms | PASS | P99 93.91 ms, 12.80 orders/s, 전체 HTTP 232건, 새 사용자 6명·setup 충전 24건 |
| 2026-07-16 | safe | `order-spike.js` | 8 | 16초 | 298 | 0.00% | 35.26 ms | PASS | P99 94.23 ms, 18.41 orders/s, 전체 HTTP 330건, 새 사용자 8명·setup 충전 32건 |

처리량은 k6 `iterations` rate(주문 iteration/s)이며 setup 충전은 제외한다. 1회차 대비 동일 사용자 P95는 473.02ms에서 68.13ms로, Load/Stress/Spike의 P95는 각각 50.40/171.36/197.52ms에서 47.60/44.13/35.26ms로 관찰됐다. 실행 환경·데이터 누적 상태가 다르므로 이를 성능 개선 효과의 확정 비교로 해석하지 않는다.
