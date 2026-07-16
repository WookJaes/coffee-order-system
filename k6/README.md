# k6 부하 테스트

HTTP 부하 테스트는 API 구현을 변경하지 않고, 현재 포인트·주문 잠금 정책의 관찰값을 기록한다.

실행 지표와 threshold는 `k6/results.md`, 동일 사용자 주문 DB 정합성은 `k6/db-verification.md`에 회차별로 기록한다.

## 범위

- `same-user-order.js`: 같은 사용자가 서로 다른 `Idempotency-Key`로 동시에 주문할 때의 잔액·주문·`USE` 이력·Outbox 정합성
- `order-load.js`: 여러 synthetic 사용자의 낮은 동시 주문 기준선
- `order-stress.js`: 여러 synthetic 사용자의 지속 주문 부하
- `order-spike.js`: 여러 synthetic 사용자의 순간 주문 부하

모든 시나리오는 상태를 변경하므로 매 실행마다 synthetic 테스트 사용자를 준비한다. 각 주문 VU에 포인트 충전 API로 충분한 포인트를 적립한 뒤 주문을 시작하고, 모든 주문 요청에는 새 `Idempotency-Key`를 사용한다. 포인트 충전은 주문 부하를 위한 setup이며 별도 k6 부하 지표로 해석하지 않는다.

## 사전 조건

1. Docker Compose의 MySQL, Redis, Kafka가 실행 중이어야 한다.
2. 앱은 기존 로컬 `.env`를 수정하지 않고 해당 값을 프로세스 환경에만 주입하여 실행한다.
3. k6 실행 파일은 로컬 설치본 또는 `grafana/k6` Docker 이미지 중 하나를 사용한다.
4. 주문 부하 실행 전 `scripts/k6/prepare-synthetic-users.sh`로 synthetic 사용자를 생성한다.

```bash
docker compose up -d --wait
set -a; source .env; set +a
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun --console=plain
USER_COUNT=8 scripts/k6/prepare-synthetic-users.sh
```

## 실행 원칙

- `BASE_URL`, `USERS_FILE`, 메뉴 ID, VU 수는 환경변수로 전달한다. 스크립트 안에 고정하지 않는다.
- 같은 사용자 주문 실행 전후에는 DB 스냅샷을 남긴다. 최종 잔액은 0 이상이고, 성공 주문 수·`USE` 이력 수·Outbox 수의 증가분은 같아야 한다.
- synthetic 사용자 생성 스크립트는 `build/k6/synthetic-users.json`에 사용자 ID만 기록한다. k6 `setup()`은 이 사용자에게 포인트 충전 API를 호출한 뒤 주문을 시작한다.
- k6 출력에서 HTTP 성공/실패 수, 오류율, `http_req_duration`와 시나리오별 P95를 결과 문서에 기록한다.
- 실패 또는 미실행 시 결과 문서와 검증 로그에 `PARTIAL` 또는 미검증 사유를 기록한다.

`safe` 프로필은 기본값이며 Load 2 VU, Stress 6 VU, Spike 8 VU로 제한하고, setup에서 사용자별로 4회 충전한다. `heavy` 프로필은 더 큰 사용자 수와 포인트 충전 횟수가 필요하므로, 실행 전에 `USER_COUNT`를 해당 시나리오의 최대 VU 이상으로 설정한다.

## 실행 명령

HTTP와 k6 스크립트가 추가된 뒤 아래 형식을 사용한다.

```bash
k6 run -e BASE_URL=http://localhost:8080 -e USER_ID=<user-id> -e MENU_ID=<menu-id> k6/same-user-order.js
k6 run -e BASE_URL=http://localhost:8080 -e USERS_FILE=build/k6/synthetic-users.json -e MENU_ID=<menu-id> k6/order-load.js
k6 run -e BASE_URL=http://localhost:8080 -e USERS_FILE=build/k6/synthetic-users.json -e MENU_ID=<menu-id> k6/order-stress.js
k6 run -e BASE_URL=http://localhost:8080 -e USERS_FILE=build/k6/synthetic-users.json -e MENU_ID=<menu-id> k6/order-spike.js
```

`heavy` 프로필은 명시적으로만 실행한다.

```bash
USER_COUNT=75 scripts/k6/prepare-synthetic-users.sh
k6 run -e K6_PROFILE=heavy -e BASE_URL=http://localhost:8080 -e USERS_FILE=build/k6/synthetic-users.json k6/order-spike.js
```

Docker로 실행할 때는 컨테이너에서 호스트 앱으로 접근할 수 있도록 `BASE_URL=http://host.docker.internal:8080`을 사용한다.
