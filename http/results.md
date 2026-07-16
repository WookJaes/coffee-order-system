# HTTP 검증 결과

## 기록 기준

이 문서는 API 응답 계약과 멱등성 동작을 HTTP Client로 검증한 결과만 기록한다. k6 부하 지표는 [k6 부하 테스트 결과](../k6/results.md), 동일 사용자 주문 DB 정합성은 [k6 DB 검증 결과](../k6/db-verification.md)에서 관리한다.

각 회차는 `http/api-contract-verification.http` 전체를 순서대로 실행한 결과다. 구현 변경 후 재검증 전에는 2회차를 PASS로 기록하지 않는다.

## 1회차: 초안 검증

**실행일:** 2026-07-16

**실행 도구:** JetBrains HTTP Client CLI (`jetbrains/intellij-http-client`)
**결과:** `RUN SUCCESSFUL`, JUnit `tests=15`, `failures=0`, `errors=0`, `skip=0` (PR 리뷰 보완 후 재실행 포함)

| 시나리오 | 결과 | 관찰값 |
| --- | --- | --- |
| 포인트 충전 성공 | PASS | 200, `status/message/data`, 메시지 `요청이 성공했습니다.` |
| 주문·결제 성공 | PASS | 201, `status/message/data`, 메시지 `주문 및 결제가 성공적으로 완료되었습니다.` |
| 같은 키 재시도 | PASS | 최초·재시도 모두 `orderId=1223`, `remainingPoint=218500` |
| 잔액 부족 주문 | PASS | 400, `포인트가 부족합니다.`, `data`·내부 `code` 없음 |
| 인기 메뉴 조회 | PASS | 200, `rank/menuId/menuName/orderCount` 배열 |

DB 확인에서 `orderId=1223`의 주문·`USE` 이력·Outbox는 각각 1건이었다. HTTP 재시도 검증은 통과했으며, 이는 k6 동일 사용자 동시 주문에서 확인한 별도 정합성 결함을 상쇄하지 않는다.

## 2회차: PR #27 수정 반영 후 재검증

**실행일:** 2026-07-16

**실행 도구:** JetBrains HTTP Client CLI Docker 이미지 (`jetbrains/intellij-http-client`)
**결과:** `RUN SUCCESSFUL`, JUnit `tests=15`, `failures=0`, `errors=0`, `skip=0`

Docker Desktop의 저장소 bind mount 제한으로 검증 파일만 `/private/tmp/coffee-http-verification`에 복사해 실행했다. 실행 대상 애플리케이션과 요청 파일의 내용은 같았으며, 실제 `.env` 파일은 수정하지 않았다.

| 시나리오 | 결과 | 관찰값 |
| --- | --- | --- |
| 포인트 충전 성공 | PASS | 200, 공통 `status/message/data` 계약 assertion 통과 |
| 주문·결제 성공 | PASS | 201, `PAID` 및 생성 응답 계약 assertion 통과 |
| 같은 키 재시도 | PASS | 최초 응답과 재시도의 동적 `orderId`·`remainingPoint` 동일 assertion 통과 |
| 잔액 부족 주문 | PASS | 400, `포인트가 부족합니다.`, `data`·내부 `code` 없음 |
| 인기 메뉴 조회 | PASS | 200, `rank/menuId/menuName/orderCount` 배열 assertion 통과 |
