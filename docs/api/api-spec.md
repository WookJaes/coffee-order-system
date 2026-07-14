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

로컬 Postman 검증용으로 Flyway V3가 `userId=1`의 테스트 사용자를 사전 등록한다.

```json
{"userId": 1, "amount": 10000}
```

### POST /api/orders

Headers:

```text
Idempotency-Key: 4de91f71-4c2d-4eb9-bc8e-2b0f4603a1fb
```

```json
{"userId": 1, "menuId": 1}
```

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
- `PAID` 상태 주문만 집계한다.
- 주문 횟수 내림차순으로 정렬하고, 동점이면 메뉴 ID 오름차순으로 정렬한다.
- 기본 조회 대상은 Redis 일자별 ZSET을 합산한 랭킹이다.
- Redis 랭킹은 파생 데이터이며, 정확한 원본 데이터는 `orders`다. 랭킹 유실 시 `orders`의 최근 7일 `PAID` 주문으로 재구성한다.

### 예외 처리 기준

예외 코드는 서버 내부 `ErrorCode` enum과 예외 처리 분기에서만 사용한다. 실패 응답 JSON에는 예외 코드를 포함하지 않고 `status`, `message`만 반환한다.
