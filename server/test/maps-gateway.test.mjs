import test from 'node:test';
import assert from 'node:assert/strict';
import { createMapsGateway, MapBudget } from '../maps/worker.mjs';
import { DatabaseSync } from 'node:sqlite';

const body = { mode: 'drive', waypoints: [
  { location: [-74, 40], timestamp: '2026-01-01T00:00:00Z' },
  { location: [-74.001, 40], timestamp: '2026-01-01T00:00:10Z' }
] };
const request = (path = '/v1/mapmatching', headers = {}, value = body) => new Request('https://maps.example' + path,
  { method: 'POST', headers: { 'CF-Connecting-IP': '192.0.2.1', ...headers }, body: JSON.stringify(value) });
const env = permit => ({ GEOAPIFY_API_KEY: 'test-key-never-real', MAP_BUDGET: {
  idFromName: name => name, get: () => ({ fetch: async () => permit || Response.json({ allowed: true }) })
} });

test('maps gateway fails closed without secrets or a shared budget', async () => {
  const worker = createMapsGateway(() => { throw new Error('must not call'); });
  assert.equal((await worker.fetch(request(), {})).status, 503);
  assert.deepEqual(await (await worker.fetch(new Request('https://maps.example/health'), {})).json(), { ok: true, configured: false });
});
test('pinned upstream receives sanitized payload and no caller credentials', async () => {
  const worker = createMapsGateway(async (url, init) => {
    assert.equal(url.origin, 'https://api.geoapify.com');
    assert.equal(url.pathname, '/v1/mapmatching');
    assert.equal(url.searchParams.get('apiKey'), 'test-key-never-real');
    assert.equal(init.redirect, 'manual');
    assert.equal(init.headers.Authorization, undefined);
    assert.deepEqual(JSON.parse(init.body), body);
    return Response.json({ type: 'FeatureCollection', features: [] });
  });
  const response = await worker.fetch(request('/v1/mapmatching?apiKey=caller', { Authorization: 'do-not-forward' }, { ...body, target: 'https://evil.example' }), env());
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('cache-control'), 'no-store');
});
test('malformed, oversized and untrusted-provenance requests never reach provider', async () => {
  const worker = createMapsGateway(() => { throw new Error('must not call'); });
  assert.equal((await worker.fetch(request('/v1/mapmatching', {}, { ...body, waypoints: Array(11).fill(body.waypoints[0]) }), env())).status, 400);
  assert.equal((await worker.fetch(request('/v1/mapmatching', {}, { ...body, extra: 'x'.repeat(17000) }), env())).status, 413);
  assert.equal((await worker.fetch(request('/v1/mapmatching', { 'CF-Worker': 'untrusted.example' }), env())).status, 403);
  assert.equal((await worker.fetch(request('/v1/mapmatching', { 'CF-Connecting-IP': '2a06:98c0:3600::103' }), env())).status, 403);
  assert.equal((await worker.fetch(new Request('https://maps.example/anything'), env())).status, 404);
});
test('exhausted allowance or budget failure cannot fall through to upstream', async () => {
  const worker = createMapsGateway(() => { throw new Error('must not call'); });
  const response = await worker.fetch(request(), env(Response.json({ error: 'exhausted' }, { status: 429, headers: { 'Retry-After': '3600' } })));
  assert.equal(response.status, 429);
  assert.equal(response.headers.get('Retry-After'), '3600');
});
test('upstream failures and redirects never disclose provider bodies or keys', async () => {
  for (const status of [302, 401, 403, 429, 500]) {
    const worker = createMapsGateway(async () => new Response('secret upstream details', { status, headers: { Location: 'https://evil.example', 'Retry-After': '90' } }));
    const response = await worker.fetch(request(), env());
    assert.equal(response.status, status === 429 ? 429 : [401, 403].includes(status) ? 403 : 502);
    assert.equal(response.headers.get('Location'), null);
    assert.equal((await response.text()).includes('secret'), false);
  }
});
test('only supported and bounded raster tiles are fetched', async () => {
  const worker = createMapsGateway(async url => {
    assert.equal(url.origin, 'https://maps.geoapify.com');
    return new Response(new Uint8Array([137, 80, 78, 71]), { headers: { 'Content-Type': 'image/png' } });
  });
  const tile = path => new Request('https://maps.example' + path, { headers: { 'CF-Connecting-IP': '192.0.2.1' } });
  assert.equal((await worker.fetch(tile('/v1/tile/osm-liberty/2/1/1.png'), env())).status, 200);
  assert.equal((await worker.fetch(tile('/v1/tile/osm-liberty/2/9/1.png'), env())).status, 400);
  assert.equal((await worker.fetch(tile('/v1/tile/unknown/2/1/1.png'), env())).status, 404);
});
test('durable reservations persist across instances and enforce global and client caps', async () => {
  const db = new DatabaseSync(':memory:');
  const ctx = { storage: { sql: { exec(sql, ...bindings) {
    const statement = db.prepare(sql);
    return sql.startsWith('SELECT') ? statement.all(...bindings) : (statement.run(...bindings), []);
  } } } };
  const reserve = (budget, bucket, limit = 3, ipLimit = 2) => budget.fetch(new Request('https://budget/reserve', {
    method: 'POST', body: JSON.stringify({ bucket: bucket.repeat(64), limit, ipLimit })
  }));
  const first = new MapBudget(ctx);
  assert.equal((await reserve(first, 'a')).status, 200);
  assert.equal((await reserve(first, 'a')).status, 200);
  const restarted = new MapBudget(ctx);
  assert.equal((await reserve(restarted, 'a')).status, 429);
  assert.equal((await reserve(restarted, 'b')).status, 200);
  assert.equal((await reserve(restarted, 'c')).status, 429);
  db.close();
});
