# API 명세

Base path: `/api`

## 공통 응답

성공 응답 예시:

```json
{"status": 200, "message": "요청이 성공했습니다.", "data": {}}
```

실패 응답 예시:

```json
{"status": 400, "message": "잘못된 요청입니다."}
```

## API

| Method | Path | 설명 | 성공 |
| --- | --- | --- | --- |
| GET | `/api/menus` | 판매 메뉴 목록 조회 | 200 |
| POST | `/api/points/charge` | 사용자 포인트 충전 | 200 |
| POST | `/api/orders` | 메뉴 주문 및 포인트 결제 | 201 |
| GET | `/api/menus/popular` | 최근 7일 인기 메뉴 Top 3 | 200 |

### GET /api/menus

판매 상태가 `ACTIVE`인 메뉴 목록을 반환한다.

```json
{
  "status": 200,
  "message": "요청이 성공했습니다.",
  "data": [
    {
      "menuId": 1,
      "name": "아메리카노",
      "price": 4500
    }
  ]
}
```

### POST /api/points/charge

`amount`는 1 이상 100,000 이하여야 한다.

`local` 프로필에서만 로컬 전용 Flyway V3가 Postman 검증용 `userId=1`의 테스트 사용자를 사전 등록한다. 비로컬 환경의 공통 migration은 이 사용자를 생성하지 않는다. 요청·응답 계약은 변하지 않는다.

```json
{"userId": 1, "amount": 10000}
```

### POST /api/orders

메뉴 한 건을 포인트로 결제한다. `Idempotency-Key` 헤더는 필수이며 null 또는 blank거나 100자를 초과하면 400으로 실패한다. 100자 키는 허용한다.

Headers:

```text
Idempotency-Key: 4de91f71-4c2d-4eb9-bc8e-2b0f4603a1fb
```

```json
{"userId": 1, "menuId": 1, "quantity": 2}
```

성공 응답:

```json
{
  "status": 201,
  "message": "주문 및 결제가 성공적으로 완료되었습니다.",
  "data": {
    "orderId": 1,
    "userId": 1,
    "menuId": 1,
    "quantity": 2,
    "paymentAmount": 9000,
    "remainingPoint": 1000,
    "status": "PAID"
  }
}
```

- 같은 사용자·같은 멱등성 키·같은 메뉴·같은 수량 요청은 기존 주문 결과를 반환하며 포인트를 다시 차감하지 않는다. 재응답의 `remainingPoint`는 주문 당시 `USE` 이력의 차감 후 잔액이다.
- 같은 사용자·같은 멱등성 키에 다른 `menuId` 또는 `quantity`를 사용하면 409와 `IDEMPOTENCY_KEY_CONFLICT`의 메시지로 실패한다.
- `quantity`는 1 이상의 정수다. 결제 금액은 주문 시점 메뉴 가격과 수량의 곱이며, 응답의 `paymentAmount`와 `orders.order_price`에 저장한다.
- 사용자·메뉴·포인트 정보 없음은 각각 404, 판매 상태가 `ACTIVE`가 아닌 메뉴와 잔액 부족은 400으로 실패한다.
- 성공 시 주문, 포인트 차감, 사용 이력, `PENDING` Outbox 이벤트가 하나의 트랜잭션으로 저장된다. Kafka 발행은 이 API 범위에 포함하지 않는다.

### GET /api/menus/popular

최근 7일간 `PAID` 상태 주문의 메뉴별 주문 횟수를 기준으로 인기 메뉴 3개를 반환한다.

```json
{
  "status": 200,
  "message": "요청이 성공했습니다.",
  "data": [
    {
      "rank": 1,
      "menuId": 1,
      "menuName": "아메리카노",
      "orderCount": 12
    },
    {
      "rank": 2,
      "menuId": 3,
      "menuName": "카페라떼",
      "orderCount": 9
    }
  ]
}
```

#### 조회 기준

- 기간은 요청 시각을 기준으로 최근 7일이며, `orders.ordered_at`을 사용한다.
- Kafka 지연·재시도·재소비가 있어도 일자별 Redis 랭킹 키는 주문의 `ordered_at`과 같은 Asia/Seoul 날짜를 사용한다.
- `PAID` 상태 주문만 집계한다.
- 주문 횟수 내림차순으로 정렬하고, 동점이면 메뉴 ID 오름차순으로 정렬한다.
- 기본 조회 대상은 Redis 일자별 ZSET을 합산한 랭킹이다.
- Redis 랭킹은 파생 데이터이며, 정확한 원본 데이터는 `orders`다. 랭킹 유실 시 `orders`의 최근 7일 `PAID` 주문으로 재구성한다.
- 기간은 Asia/Seoul 요청일을 포함한 7개 달력일이며, DB 복구 범위는 시작일 00:00 이상·다음 날 00:00 미만이다.
- 현재 `ACTIVE` 메뉴만 응답한다. Redis 또는 DB 랭킹에 남았지만 `SOLD_OUT` 또는 삭제된 메뉴는 제외하고 다음 `ACTIVE` 메뉴로 최대 3건까지 채운다.
- 7일치 Redis에 멤버가 없으면 DB 복구를 시도한다. DB 집계가 있으면 날짜별 ZSET에 같은 TTL로 재저장하고, 없으면 정상적인 빈 목록을 반환한다.

### 예외 처리 기준

예외 코드는 서버 내부 `ErrorCode` enum과 예외 처리 분기에서만 사용한다. 실패 응답 JSON에는 예외 코드를 포함하지 않고 `status`, `message`만 반환한다.

- 지원하지 않는 HTTP 메서드는 405 공통 오류 응답을 반환한다.
- 지원하지 않는 Content-Type은 415 공통 오류 응답을 반환한다.
- 예상하지 못한 예외는 내부 상세를 노출하지 않는 500 공통 오류 응답을 반환하고 ERROR 로그를 남긴다.
