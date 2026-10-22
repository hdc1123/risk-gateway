// k6 run -e BASE_URL=https://test-gateway -e GATEWAY_TOKEN=... -e USER_ID=1 -e ACCOUNT=2 scripts/load-test.js
// 只允许隔离测试环境 + mock/专用测试 Adapter；先由特征生产者准备账户快照。
import http from 'k6/http';
import { check } from 'k6';
export const options = {
  scenarios: { orders: { executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 20), timeUnit: '1s',
    duration: __ENV.DURATION || '60s', preAllocatedVUs: 20, maxVUs: 100 } },
  thresholds: { http_req_failed: ['rate<0.01'], checks: ['rate>0.99'], http_req_duration: ['p(99)<100'] },
};
export default function () {
  if (__ENV.CONFIRM_TEST_ENV !== 'yes') throw new Error('必须设置 CONFIRM_TEST_ENV=yes，禁止真实交易压测');
  if (!__ENV.RUN_ID) throw new Error('必须为每次运行配置唯一 RUN_ID，避免跨运行订单标识冲突');
  const id = `${__ENV.RUN_ID}-${__VU}-${__ITER}`;
  const response = http.post(`${__ENV.BASE_URL}/api/v1/risk/order`, JSON.stringify({
    eventId: id, eventType: 'ORDER_SUBMIT', userId: Number(__ENV.USER_ID), mt5Account: Number(__ENV.ACCOUNT),
    orderId: id, symbol: 'EURUSD', volume: 1.00, side: 'BUY', price: 1.08, orderType: 'MARKET', timestamp: Date.now(),
  }), { headers: { Authorization: `Bearer ${__ENV.GATEWAY_TOKEN}`, 'Content-Type': 'application/json', 'X-Request-Id': id } });
  check(response, {
    'HTTP 200': r => r.status === 200,
    'business accepted without degradation': r => {
      try { const body = r.json(); return body.code === 0 && body.data && !body.data.degradeFlag; }
      catch (_) { return false; }
    },
  });
}
