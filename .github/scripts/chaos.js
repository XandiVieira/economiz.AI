// Weekly chaos run: N virtual users hit the live dev app CONCURRENTLY, doing a
// realistic burst of actions and contending on the SAME shared catalog products —
// to shake out races, deadlocks, and pool exhaustion a sequential E2E can't.
//
// - Throwaway VUs register a unique account, act, then DELETE themselves (no garbage).
// - VU 1 is the PERSISTENT "returning user" (developer+chaospersist@) — it logs in and
//   ACCUMULATES history week over week instead of deleting, so we also cover the
//   "user with existing data" scenarios a fresh user misses.
// - No receipt confirms / no admin / no SEFAZ scans, so nothing pollutes the community
//   price index and there's no admin-login rate-limit.
//
// The run FAILS (red) if any request 5xx's — that's a real bug; the log-sweep will also
// see it in Render logs and hand it to the autofix.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL;
const RUN = __ENV.RUN_ID || 'local';
const serverErrors = new Counter('app_5xx');

export const options = {
  scenarios: {
    chaos: {
      executor: 'per-vu-iterations',
      vus: Number(__ENV.VUS || 6),
      iterations: 1,
      maxDuration: '6m',
    },
  },
  thresholds: {
    app_5xx: ['count==0'],            // any server error fails the run
    checks: ['rate>0.90'],            // most actions should succeed (4xx on fuzzed input is fine)
  },
};

function body(res) { try { return res.json(); } catch (e) { return null; } }

function ok(res, label) {
  const noCrash = res.status < 500;
  if (!noCrash) serverErrors.add(1);
  check(res, { [`${label} no 5xx`]: () => noCrash });
  return res;
}

function auth(token) {
  return { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } };
}

export default function () {
  const H = { headers: { 'Content-Type': 'application/json' } };
  const persistent = __VU === 1;
  const email = persistent
    ? 'developer+chaospersist@economizaai.app'
    : `developer+chaos-${RUN}-${__VU}@economizaai.app`;
  const password = 'ChaosUser!2026';

  // Register (persistent account 409s after the first ever run -> log in instead).
  let res = ok(http.post(`${BASE}/api/v1/auth/register`, JSON.stringify({
    name: 'Chaos User', email, password, acceptedTermsVersion: '1.0', acceptedPrivacyVersion: '1.0',
  }), H), 'register');
  let token = res.status === 201 ? body(res)?.token : null;
  if (!token) {
    token = body(ok(http.post(`${BASE}/api/v1/auth/login`,
      JSON.stringify({ email, password }), H), 'login'))?.token;
  }
  if (!token) return;
  const A = auth(token);

  sleep(Math.random() * 0.5);   // stagger so the VUs overlap on the shared entities below

  // --- reads under concurrency ---
  ok(http.get(`${BASE}/api/v1/dashboard`, A), 'dashboard');
  ok(http.get(`${BASE}/api/v1/insights/spend`, A), 'insights.spend');
  ok(http.get(`${BASE}/api/v1/markets`, A), 'markets');
  ok(http.get(`${BASE}/api/v1/deals`, A), 'deals');

  // --- contention: every VU grabs the SAME product from the shared catalog and
  //     concurrently creates alerts / rules / purchases / views against it ---
  const products = body(ok(http.get(`${BASE}/api/v1/products?query=a`, A), 'products.search'));
  const list = Array.isArray(products) ? products : (products?.content || []);
  const productId = list.length ? list[0].id : null;
  if (productId) {
    ok(http.post(`${BASE}/api/v1/products/${productId}/view`, null, A), 'product.view');
    ok(http.post(`${BASE}/api/v1/consumption/manual-purchase`,
      JSON.stringify({ productId, quantity: 1 }), A), 'manual-purchase');
    ok(http.post(`${BASE}/api/v1/alerts`,
      JSON.stringify({ productId, thresholdPrice: 5.99, radiusKm: 5 }), A), 'alert.create');
    ok(http.post(`${BASE}/api/v1/notification-rules`,
      JSON.stringify({ type: 'STOCKOUT', productId, leadTimeDays: 3 }), A), 'rule.create');
    ok(http.get(`${BASE}/api/v1/price-index/products/${productId}/best-markets`, A), 'best-markets');
  }

  // --- household-scoped writes (cleaned with the account) ---
  const created = body(ok(http.post(`${BASE}/api/v1/shopping-lists`, JSON.stringify({
    name: `Chaos ${RUN}-${__VU}`, items: [{ freeText: 'papel higiênico', quantity: 1 }],
  }), A), 'list.create'));
  if (created?.id) {
    ok(http.post(`${BASE}/api/v1/shopping-lists/${created.id}/items`,
      JSON.stringify({ freeText: 'arroz', quantity: 2 }), A), 'list.addItem');
  }

  sleep(Math.random() * 0.5);

  // Throwaways clean up; the persistent user keeps its accumulating history.
  if (!persistent) {
    ok(http.del(`${BASE}/api/v1/users/me`, null, A), 'delete-account');
  }
}
