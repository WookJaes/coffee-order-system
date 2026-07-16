import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Rate } from 'k6/metrics';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const MENU_ID = Number(__ENV.K6_MENU_ID || '1');
export const QUANTITY = Number(__ENV.K6_QUANTITY || '1');
export const CHARGE_AMOUNT = Number(__ENV.K6_CHARGE_AMOUNT || '100000');
const THINK_TIME_SECONDS = Number(__ENV.K6_THINK_TIME_SECONDS || '0.2');

export const orderSuccessRate = new Rate('order_success_rate');
export const unexpectedOrderErrorRate = new Rate('unexpected_order_error_rate');

export function requireKnownProfile(profiles) {
  const profile = __ENV.K6_PROFILE || 'safe';
  if (!Object.prototype.hasOwnProperty.call(profiles, profile)) {
    throw new Error(`K6_PROFILE은 ${Object.keys(profiles).join(', ')} 중 하나여야 합니다.`);
  }
  return { name: profile, ...profiles[profile] };
}

export function loadSyntheticUsers() {
  const usersFile = __ENV.USERS_FILE;
  if (!usersFile) {
    throw new Error('USERS_FILE은 prepare-synthetic-users.sh가 만든 JSON 파일 경로여야 합니다.');
  }

  const document = JSON.parse(open(usersFile));
  if (!Array.isArray(document.users) || document.users.length === 0 ||
    !document.users.every((user) => Number.isInteger(user.userId) && user.userId > 0)) {
    throw new Error(`유효하지 않은 synthetic 사용자 파일입니다: ${usersFile}`);
  }
  return document.users;
}

function responseBody(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}

function isSuccessResponse(response, status, message) {
  const body = responseBody(response);
  return response.status === status &&
    body !== null && body.status === status && body.message === message;
}

export function prepareSyntheticUsers(users, chargeRounds = 1) {
  if (!Number.isInteger(chargeRounds) || chargeRounds < 1) {
    throw new Error('chargeRounds는 1 이상의 정수여야 합니다.');
  }

  for (const user of users) {
    for (let round = 0; round < chargeRounds; round += 1) {
      const response = http.post(
        `${BASE_URL}/api/points/charge`,
        JSON.stringify({ userId: user.userId, amount: CHARGE_AMOUNT }),
        {
          headers: { 'Content-Type': 'application/json' },
          tags: { api: 'point_charge', phase: 'setup', data_class: 'synthetic' },
        },
      );
      const prepared = check(response, {
        'synthetic user charge returns 200': (result) => result.status === 200,
        'synthetic user charge response contract': (result) =>
          isSuccessResponse(result, 200, '요청이 성공했습니다.'),
      });
      if (!prepared) {
        fail(`synthetic user ${user.userId} charge failed with HTTP ${response.status}`);
      }
    }
  }
  return { users, menuId: MENU_ID };
}

export function requireUserCapacity(users, maxVUs) {
  if (!Number.isInteger(maxVUs) || maxVUs < 1) {
    throw new Error('maxVUs는 1 이상의 정수여야 합니다.');
  }
  if (users.length < maxVUs) {
    throw new Error(`synthetic 사용자 수가 부족합니다: users=${users.length}, required=${maxVUs}`);
  }
}

export function userForVu(users) {
  return users[__VU - 1];
}

export function createOrder(user, scenarioName) {
  const idempotencyKey = `k6-${scenarioName}-${user.userId}-${__VU}-${__ITER}-${Date.now()}`;
  const response = http.post(
    `${BASE_URL}/api/orders`,
    JSON.stringify({ userId: user.userId, menuId: MENU_ID, quantity: QUANTITY }),
    {
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
      },
      tags: { api: 'orders', scenario: scenarioName, data_class: 'synthetic' },
    },
  );

  const body = responseBody(response);
  const responseContract = body !== null &&
    body.status === 201 &&
    body.message === '주문 및 결제가 성공적으로 완료되었습니다.' &&
    body.data !== null &&
    Number.isInteger(body.data.orderId) &&
    body.data.status === 'PAID';

  const succeeded = check(response, {
    'order returns 201': (result) => result.status === 201,
    'order response contract': () => responseContract,
  });

  orderSuccessRate.add(succeeded);
  unexpectedOrderErrorRate.add(!succeeded);
  sleep(THINK_TIME_SECONDS);
  return response;
}

export function orderThresholds(p95Milliseconds) {
  return {
    checks: ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
    http_req_duration: [`p(95)<${p95Milliseconds}`],
    order_success_rate: ['rate>0.99'],
    unexpected_order_error_rate: ['rate<0.01'],
  };
}

export const summaryOptions = {
  setupTimeout: '2m',
  summaryTimeUnit: 'ms',
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'count'],
};
