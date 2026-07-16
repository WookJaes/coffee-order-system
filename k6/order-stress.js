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
    maxVUs: 6,
    chargeRounds: 4,
    stages: [{ duration: '3s', target: 2 }, { duration: '5s', target: 4 }, { duration: '5s', target: 6 }, { duration: '3s', target: 0 }],
  },
  heavy: {
    maxVUs: 50,
    chargeRounds: 4,
    stages: [{ duration: '30s', target: 10 }, { duration: '1m', target: 25 }, { duration: '1m', target: 50 }, { duration: '30s', target: 0 }],
  },
};
const selected = requireKnownProfile(profiles);
const users = loadSyntheticUsers();
requireUserCapacity(users, selected.maxVUs);

export const options = {
  scenarios: {
    order_stress: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: selected.stages,
      gracefulRampDown: '5s',
      gracefulStop: '5s',
      tags: { test_type: 'stress', profile: selected.name },
    },
  },
  thresholds: orderThresholds(1500),
  ...summaryOptions,
};

export function setup() {
  return prepareSyntheticUsers(users, selected.chargeRounds);
}

export default function (data) {
  createOrder(userForVu(data.users), 'order_stress');
}
