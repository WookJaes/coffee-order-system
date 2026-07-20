import { prepareSyntheticUsers, createOrder, orderThresholds, summaryOptions } from './lib/order-scenario.js';

const userId = Number(__ENV.USER_ID);
const vus = Number(__ENV.K6_SAME_USER_VUS || '5');

if (!Number.isInteger(userId) || userId < 1) {
  throw new Error('USER_ID는 1 이상의 정수여야 합니다.');
}
if (!Number.isInteger(vus) || vus < 1) {
  throw new Error('K6_SAME_USER_VUS는 1 이상의 정수여야 합니다.');
}

export const options = {
  scenarios: {
    same_user_order: {
      executor: 'per-vu-iterations',
      vus,
      iterations: 1,
      maxDuration: '30s',
      tags: { test_type: 'same_user_concurrency' },
    },
  },
  thresholds: orderThresholds(2000),
  ...summaryOptions,
};

export function setup() {
  return prepareSyntheticUsers([{ userId }]);
}

export default function (data) {
  createOrder(data.users[0], 'same_user_order');
}
