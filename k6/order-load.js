import {
  createOrder,
  loadSyntheticUsers,
  prepareSyntheticUsers,
  requireKnownProfile,
  requireUserCapacity,
  orderThresholds,
  summaryOptions,
  userForVu,
} from './lib/order-scenario.js';

const profiles = {
  safe: {
    maxVUs: 2,
    chargeRounds: 4,
    stages: [{ duration: '3s', target: 1 }, { duration: '8s', target: 2 }, { duration: '3s', target: 0 }],
  },
  heavy: {
    maxVUs: 25,
    chargeRounds: 4,
    stages: [{ duration: '30s', target: 5 }, { duration: '2m', target: 25 }, { duration: '30s', target: 0 }],
  },
};
const selected = requireKnownProfile(profiles);
const users = loadSyntheticUsers();
requireUserCapacity(users, selected.maxVUs);

export const options = {
  scenarios: {
    order_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: selected.stages,
      gracefulRampDown: '5s',
      gracefulStop: '5s',
      tags: { test_type: 'load', profile: selected.name },
    },
  },
  thresholds: orderThresholds(1000),
  ...summaryOptions,
};

export function setup() {
  return prepareSyntheticUsers(users, selected.chargeRounds);
}

export default function (data) {
  createOrder(userForVu(data.users), 'order_load');
}
