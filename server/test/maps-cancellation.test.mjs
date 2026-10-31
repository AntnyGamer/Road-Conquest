import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { createMapsGateway, MapBudget } from '../maps/worker.mjs';

const body = { mode: 'drive', waypoints: [
  { location: [-74, 40], timestamp: '2026-01-01T00:00:00Z', accuracyMeters: 5 },
  { location: [-74.001, 40], timestamp: '2026-01-01T00:00:10Z', accuracyMeters: 5 }
] };
function request(path, signal) {
  return new Request('https://maps.example' + path, {
    method: path.includes('/tile/') ? 'GET' : 'POST', headers: { 'CF-Connecting-IP': '192.0.2.1' },
    ...(path.includes('/tile/') ? {} : { body: JSON.stringify(body) }), signal
  });
}
function fixture(t, configRead = () => null) {
  const db = new DatabaseSync(':memory:');
  t.after(() => db.close());
  const count = { sql: 0, config: 0, bindings: 0, budget: 0, upstream: 0 };
  const budget = new MapBudget({ storage: {
    sql: { exec(query, ...args) {
      count.sql++;
      const statement = db.prepare(query);
      return statement.columns().length ? statement.all(...args) : (statement.run(...args), []);
    } },
    transactionSync(fn) {
      db.exec('BEGIN');
      try { const value = fn(); db.exec('COMMIT'); return value; }
      catch (error) { db.exec('ROLLBACK'); throw error; }
    }
  } });
  count.sql = 0;
  const env = { GEOAPIFY_API_KEY: 'synthetic-primary', LOCATIONIQ_API_KEY: 'synthetic-backup',
    SERVICE_CONFIG: { get: async () => { count.config++; return configRead(); } },
    MAP_BUDGET: { idFromName: name => name, get: () => {
      count.bindings++;
      return { fetch: (url, init) => { count.budget++; return budget.fetch(new Request(url, init)); } };
    } }
  };
  const gateway = createMapsGateway(async url => {
    count.upstream++;
    return url.pathname.includes('/tile/')
      ? new Response(new Uint8Array([137, 80, 78, 71]), { headers: { 'Content-Type': 'image/png' } })
      : Response.json({});
  });
  return { db, count, env, gateway };
}
function interceptSign(t, callback) {
  const previous = Object.getOwnPropertyDescriptor(crypto.subtle, 'sign');
  const original = crypto.subtle.sign;
  crypto.subtle.sign = async function (...args) {
    const result = await original.apply(this, args);
    callback(new TextDecoder().decode(args[2]));
    return result;
  };
  t.after(() => {
    if (previous) Object.defineProperty(crypto.subtle, 'sign', previous);
    else delete crypto.subtle.sign;
  });
}
async function cancelled(response) {
  assert.equal(response.status, 503);
  assert.equal(response.headers.get('X-RoadConquest-Error'), 'request_cancelled');
  assert.equal(response.headers.get('Retry-After'), '60');
  assert.equal(response.headers.get('Cache-Control'), 'no-store');
  assert.deepEqual(await response.json(), { error: 'Map service temporarily unavailable' });
}
const paths = ['/v1/mapmatching', '/v1/locationiq/mapmatching', '/v1/tile/positron/1/0/0.png'];

test('already-abandoned matching and tiles make no configuration, crypto or budget calls', async t => {
  const s = fixture(t);
  let signs = 0; interceptSign(t, () => { signs++; });
  const controller = new AbortController(); controller.abort();
  for (const path of paths) await cancelled(await s.gateway.fetch(request(path, controller.signal), s.env));
  assert.deepEqual(s.count, { sql: 0, config: 0, bindings: 0, budget: 0, upstream: 0 });
  assert.equal(signs, 0);
  assert.equal(s.db.prepare('SELECT COUNT(*) n FROM usage').get().n, 0);
});

test('an already-abandoned request never consumes its GPS body', async t => {
  const s = fixture(t);
  let reads = 0;
  const controller = new AbortController(); controller.abort();
  const stream = new ReadableStream({ pull() { reads++; throw new Error('abandoned body must not be read'); } },
    { highWaterMark: 0 });
  const req = new Request('https://maps.example/v1/mapmatching', { method: 'POST',
    headers: { 'CF-Connecting-IP': '192.0.2.1' }, body: stream, duplex: 'half', signal: controller.signal });
  t.after(() => req.body.cancel().catch(() => {}));
  await cancelled(await s.gateway.fetch(req, s.env));
  assert.equal(reads, 0);
  assert.equal(s.count.budget, 0);
});

test('cancellation while reading a driving body stops before remote configuration or accounting', async t => {
  const s = fixture(t), controller = new AbortController();
  const stream = new ReadableStream({ pull(c) {
    c.enqueue(new TextEncoder().encode(JSON.stringify(body))); c.close(); controller.abort();
  } }, { highWaterMark: 0 });
  const req = new Request('https://maps.example/v1/mapmatching', { method: 'POST',
    headers: { 'CF-Connecting-IP': '192.0.2.1' }, body: stream, duplex: 'half', signal: controller.signal });
  await cancelled(await s.gateway.fetch(req, s.env));
  assert.deepEqual(s.count, { sql: 0, config: 0, bindings: 0, budget: 0, upstream: 0 });
});

test('cancellation during a configuration refresh stops before signing or reserving', async t => {
  const controller = new AbortController();
  const s = fixture(t, () => { controller.abort(); return null; });
  let signs = 0; interceptSign(t, () => { signs++; });
  await cancelled(await s.gateway.fetch(request(paths[0], controller.signal), s.env));
  assert.equal(signs, 0);
  assert.deepEqual(s.count, { sql: 0, config: 1, bindings: 0, budget: 0, upstream: 0 });
});

test('a broken driving upload returns private JSON without spending provider allowance', async t => {
  const s = fixture(t);
  const stream = new ReadableStream({ pull(c) { c.error(new Error('private trace upload disconnected')); } },
    { highWaterMark: 0 });
  const req = new Request('https://maps.example/v1/mapmatching', { method: 'POST',
    headers: { 'CF-Connecting-IP': '192.0.2.1' }, body: stream, duplex: 'half' });
  const response = await s.gateway.fetch(req, s.env);
  assert.equal(response.status, 503);
  assert.equal(response.headers.get('Cache-Control'), 'no-store');
  assert.deepEqual(await response.json(), { error: 'Map service temporarily unavailable' });
  assert.deepEqual(s.count, { sql: 0, config: 0, bindings: 0, budget: 0, upstream: 0 });
  assert.equal(req.body.locked, false);
});

test('an aborted body reader error follows the same cancellation contract', async t => {
  const s = fixture(t), controller = new AbortController();
  const stream = new ReadableStream({ pull(c) {
    controller.abort(); c.error(new DOMException('Synthetic aborted upload', 'AbortError'));
  } }, { highWaterMark: 0 });
  const req = new Request('https://maps.example/v1/mapmatching', { method: 'POST',
    headers: { 'CF-Connecting-IP': '192.0.2.1' }, body: stream, duplex: 'half', signal: controller.signal });
  await cancelled(await s.gateway.fetch(req, s.env));
  assert.equal(s.count.budget, 0);
  assert.equal(req.body.locked, false);
});

test('an oversized upload stays 413 even when closing its stream fails', async t => {
  const s = fixture(t);
  const stream = new ReadableStream({
    pull(c) { c.enqueue(new Uint8Array(32_769)); },
    cancel() { throw new Error('Synthetic connection closed'); }
  }, { highWaterMark: 0 });
  const req = new Request('https://maps.example/v1/mapmatching', { method: 'POST',
    headers: { 'CF-Connecting-IP': '192.0.2.1' }, body: stream, duplex: 'half' });
  assert.equal((await s.gateway.fetch(req, s.env)).status, 413);
  assert.equal(s.count.budget, 0);
  assert.equal(req.body.locked, false);
});

test('a burst of abandoned tiles cannot occupy the queue needed by live matching', async t => {
  const s = fixture(t), controller = new AbortController(); controller.abort();
  for (let i = 0; i < 100; i++) {
    const abandoned = await s.gateway.fetch(request(paths[2], controller.signal), s.env);
    assert.equal(abandoned.status, 503); await abandoned.body.cancel();
  }
  assert.equal(s.count.budget, 0);
  const active = await s.gateway.fetch(request(paths[0]), s.env);
  assert.equal(active.status, 200); await active.body.cancel();
  assert.equal(s.count.budget, 2);
  assert.equal(s.count.upstream, 1);
  assert.equal(s.db.prepare("SELECT used FROM usage WHERE bucket='units:all'").get().used, 4);
});

test('one cancelled caller does not cancel a configuration refresh shared by active callers', async t => {
  const controller = new AbortController();
  let release, entered;
  const pending = new Promise(resolve => { release = resolve; });
  const started = new Promise(resolve => { entered = resolve; });
  const s = fixture(t, () => { entered(); return pending; });
  const abandoned = s.gateway.fetch(request(paths[0], controller.signal), s.env);
  const active = s.gateway.fetch(request(paths[0]), s.env);
  await started;
  controller.abort(); release(null);
  await cancelled(await abandoned);
  const response = await active;
  assert.equal(response.status, 200); await response.body.cancel();
  assert.deepEqual(s.count.config, 1);
  assert.equal(s.count.budget, 2);
  assert.equal(s.count.upstream, 1);
});

for (const phase of ['client', 'health']) test('cancellation during ' + phase + ' signing avoids reservation and leaves the next request usable', async t => {
  const s = fixture(t), controller = new AbortController();
  interceptSign(t, message => { if ((message === 'health:matching') === (phase === 'health')) controller.abort(); });
  await cancelled(await s.gateway.fetch(request(paths[0], controller.signal), s.env));
  assert.equal(s.count.bindings, 0);
  assert.equal(s.count.sql, 0);
  assert.equal(s.count.budget, 0);
  assert.equal(s.count.upstream, 0);
  // A caller cancelling must neither poison provider health nor spend its next allowance.
  const active = await s.gateway.fetch(request(paths[0]), s.env);
  assert.equal(active.status, 200); await active.body.cancel();
  assert.equal(s.count.budget, 2);
  assert.equal(s.count.upstream, 1);
  assert.equal(s.db.prepare("SELECT used FROM usage WHERE bucket='units:all'").get().used, 4);
  assert.equal(s.db.prepare("SELECT used FROM usage WHERE bucket='attempts:all'").get().used, 1);
});
