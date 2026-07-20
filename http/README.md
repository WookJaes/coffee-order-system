# HTTP 검증

`api-contract-verification.http`는 IntelliJ HTTP Client에서 위에서 아래 순서대로 실행하는 Issue #9 재실행용 계약 검증 파일이다.

```bash
docker compose up -d --wait
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun --console=plain
```

애플리케이션이 기동되면 `http/api-contract-verification.http`의 요청을 순서대로 실행한다. 기본값은 Flyway V3 테스트 사용자(`userId=1`)와 ACTIVE 메뉴(`menuId=1`)를 사용한다. 실제 `.env` 파일을 수정하지 않는다.

JetBrains HTTP Client CLI로 전체 파일과 assertion을 실행할 수 있다. Docker Desktop에서 저장소 폴더 공유가 허용된 환경에서는 다음과 같이 실행한다.

```bash
docker run --rm --add-host host.docker.internal:host-gateway \
  -v "$PWD/http:/workdir" \
  jetbrains/intellij-http-client -D api-contract-verification.http --report
```

`-D`는 컨테이너의 `localhost` 요청을 호스트 애플리케이션으로 연결한다. 성공 시 `reports/report.xml`에 JUnit 형식 결과가 생성된다.

각 실행은 새 `Idempotency-Key`로 최초 주문을 생성하고, 바로 다음 요청에서 동일 키를 재사용한다. 따라서 여러 번 실행해도 재시도 검증 자체가 기존 주문과 충돌하지 않는다.

DB까지 확인할 때는 최초 주문과 재시도 요청 전후에 다음 증가분을 비교한다. 같은 키 재시도에서는 세 값이 추가로 증가하지 않아야 한다.

```sql
SELECT COUNT(*) FROM orders WHERE user_id = 1;
SELECT COUNT(*) FROM point_histories WHERE user_id = 1 AND type = 'USE';
SELECT COUNT(*)
FROM order_events oe
JOIN orders o ON o.id = oe.order_id
WHERE o.user_id = 1;
```
