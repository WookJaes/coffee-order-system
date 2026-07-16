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
    maxVUs: 8,
    chargeRounds: 4,
    stages: [{ duration: '3s', target: 1 }, { duration: '2s', target: 8 }, { duration: '5s', target: 8 }, { duration: '3s', target: 1 }, { duration: '3s', target: 0 }],
  },
  heavy: {
    maxVUs: 75,
    chargeRounds: 4,
    stages: [{ duration: '30s', target: 5 }, { duration: '10s', target: 75 }, { duration: '1m', target: 75 }, { duration: '30s', target: 5 }, { duration: '30s', target: 0 }],
  },
};
const selected = requireKnownProfile(profiles);
const users = loadSyntheticUsers();
requireUserCapacity(users, selected.maxVUs);

export const options = {
  scenarios: {
    order_spike: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: selected.stages,
      gracefulRampDown: '5s',
      gracefulStop: '5s',
      tags: { test_type: 'spike', profile: selected.name },
    },
  },
  thresholds: orderThresholds(2000),
  ...summaryOptions,
};

export function setup() {
  return prepareSyntheticUsers(users, selected.chargeRounds);
}

export default function (data) {
  createOrder(userForVu(data.users), 'order_spike');
}
