import test from 'node:test';
import assert from 'node:assert/strict';
import { createMapsGateway, MapBudget } from '../maps/worker.mjs';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';

const body = { mode: 'drive', waypoints: [
  { location: [-74, 40], timestamp: '2026-01-01T00:00:00Z' },
  { location: [-74.001, 40], timestamp: '2026-01-01T00:00:10Z' }
] };
const request = (path = '/v1/mapmatching', headers = {}, value = body) => new Request('https://maps.example' + path,
  { method: 'POST', headers: { 'CF-Connecting-IP': '192.0.2.1', ...headers }, body: JSON.stringify(value) });
const env = permit => ({ GEOAPIFY_API_KEY: 'test-key-never-real', MAP_BUDGET: {
  idFromName: name => name, get: () => ({ fetch: async () => permit || Response.json({ allowed: true }) })
} });

test('native tile caching never enables caching private GPS matching responses', async () => {
  const configuration = JSON.parse(readFileSync(new URL('../maps/wrangler.jsonc', import.meta.url), 'utf8'));
  assert.equal(configuration.cache.enabled, true);
  const worker = createMapsGateway(async url => url.pathname.includes('/tile/')
    ? new Response(new Uint8Array([137, 80, 78, 71]), { headers: { 'Content-Type': 'image/png' } })
    : Response.json({ type: 'FeatureCollection', features: [] }));
  const tile = await worker.fetch(new Request('https://maps.example/v1/tile/positron/1/0/0.png',
    { headers: { 'CF-Connecting-IP': '192.0.2.1' } }), env());
  assert.equal(tile.headers.get('Cache-Control'), 'public, max-age=86400');
  const matching = await worker.fetch(request(), env());
  assert.equal(matching.headers.get('Cache-Control'), 'no-store');
  const error = await worker.fetch(new Request('https://maps.example/bad'), env());
  assert.equal(error.headers.get('Cache-Control'), 'no-store');
});

test('maps gateway fails closed without secrets or a shared budget', async () => {
  const worker = createMapsGateway(() => { throw new Error('must not call'); });
  assert.equal((await worker.fetch(request(), {})).status, 503);
  assert.deepEqual(await (await worker.fetch(new Request('https://maps.example/health'), {})).json(), { ok: true, configured: false, locationiqConfigured: false });
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
  assert.equal((await worker.fetch(request('/v1/mapmatching', {}, { ...body, waypoints: Array(201).fill(body.waypoints[0]) }), env())).status, 400);
  assert.equal((await worker.fetch(request('/v1/mapmatching', {}, { ...body, extra: 'x'.repeat(33000) }), env())).status, 413);
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
test('gateway reserves quarter-credit tiles and one-credit batches, even with 100 points', async () => {
  const costs = [];
  const captured = () => ({ GEOAPIFY_API_KEY: 'test-key-never-real', MAP_BUDGET: {
    idFromName: name => name, get: () => ({ fetch: async (_url, init) => {
      costs.push(JSON.parse(init.body)); return Response.json({ allowed: true });
    } })
  } });
  const worker = createMapsGateway(async (url) => url.hostname === 'api.geoapify.com'
    ? Response.json({ type: 'FeatureCollection', features: [] })
    : new Response(new Uint8Array([137,80,78,71]), { headers: { 'Content-Type': 'image/png' } }));
  const tile = new Request('https://maps.example/v1/tile/osm-liberty/2/1/1.png',
    { headers: { 'CF-Connecting-IP': '192.0.2.1' } });
  assert.equal((await worker.fetch(tile, captured())).status, 200);
  assert.equal((await worker.fetch(request('/v1/mapmatching', {},
    { ...body, waypoints: Array(100).fill(body.waypoints[0]) }), captured())).status, 200);
  assert.equal(costs[0].costUnits, 1);
  assert.equal(costs[1].costUnits, 4);
  assert.equal(costs[1].limit, 9600);
  assert.equal(costs[1].ipLimit, 2400);
  assert.equal((await worker.fetch(request('/v1/mapmatching', {},
    { ...body, waypoints: Array(200).fill(body.waypoints[0]) }), captured())).status, 200);
  assert.equal(costs[2].costUnits, 8);
});

test('durable reservations persist across instances and enforce global and client caps', async () => {
  const db = new DatabaseSync(':memory:');
  let counterReads = 0;
  const ctx = { storage: { sql: { exec(sql, ...bindings) {
    if (sql.startsWith('SELECT')) counterReads++;
    const statement = db.prepare(sql);
    return sql.startsWith('SELECT') ? statement.all(...bindings) : (statement.run(...bindings), []);
  } } } };
  const reserve = (budget, bucket, limit = 12, ipLimit = 8, costUnits = 4) => budget.fetch(new Request('https://budget/reserve', {
    method: 'POST', body: JSON.stringify({ bucket: bucket.repeat(64), limit, ipLimit, costUnits })
  }));
  const first = new MapBudget(ctx);
  assert.equal((await reserve(first, 'a')).status, 200);
  assert.equal((await reserve(first, 'a')).status, 200);
  assert.equal(counterReads, 2, 'Each reservation should need only one budget read');
  const restarted = new MapBudget(ctx);
  assert.equal((await reserve(restarted, 'a')).status, 429);
  assert.equal((await reserve(restarted, 'b')).status, 200);
  assert.equal((await reserve(restarted, 'c')).status, 429);
  assert.equal(counterReads, 5, 'Denied requests also use one read and make no reservation');
  // The next request must be rejected if its *cost*, not just request count,
  // would cross the remaining allowance.
  const restarted2 = new MapBudget(ctx);
  assert.equal((await reserve(restarted2, 'b', 20, 20, 9)).status, 429);
  // Old whole-credit records remain debited after a mid-day upgrade.
  const day = new Date().toISOString().slice(0, 10);
  db.prepare('INSERT INTO usage(day,bucket,used) VALUES(?,?,?)').run(day, 'all', 1);
  assert.equal((await reserve(restarted2, 'd', 16, 16, 1)).status, 429);
  db.close();
});

const fallbackBody = { mode: 'drive', waypoints: body.waypoints.map(p => ({ ...p, accuracyMeters: 3 })) };
const fallbackEnv = () => ({ ...env(), LOCATIONIQ_API_KEY: 'fallback-key-never-real' });

test('LocationIQ has its own capped budget and a pinned matching-only upstream', async () => {
  let reserved;
  const settings = fallbackEnv();
  // Losing Geoapify configuration or quota must not disable the backup.
  delete settings.GEOAPIFY_API_KEY;
  settings.LOCATIONIQ_DAILY_REQUEST_CAP = '999999';
  settings.MAP_BUDGET = { idFromName: name => name, get: name => {
    assert.equal(name, 'locationiq-free-allowance');
    return { fetch: async (_url, init) => { reserved = JSON.parse(init.body); return Response.json({ allowed: true }); } };
  } };
  const worker = createMapsGateway(async (url, init) => {
    assert.equal(url.origin, 'https://us1.locationiq.com');
    assert.equal(url.pathname, '/v1/matching/driving/-74,40;-74.001,40');
    assert.equal(url.searchParams.get('key'), settings.LOCATIONIQ_API_KEY);
    assert.equal(url.searchParams.get('steps'), 'true');
    assert.equal(url.searchParams.get('geometries'), 'geojson');
    assert.equal(url.searchParams.get('tidy'), 'false');
    assert.equal(url.searchParams.get('gaps'), 'split');
    assert.equal(url.searchParams.get('radiuses'), '3;3');
    assert.equal(url.searchParams.get('timestamps'), '1767225600;1767225610');
    assert.equal(url.searchParams.get('generate_hints'), 'false');
    assert.equal(init.method, 'GET');
    assert.equal(init.body, undefined);
    assert.equal(init.headers.Authorization, undefined);
    assert.equal(init.headers['CF-Connecting-IP'], undefined);
    assert.equal(init.redirect, 'manual');
    return Response.json({ code: 'Ok', matchings: [], tracepoints: [] });
  });
  const response = await worker.fetch(request('/v1/locationiq/mapmatching?key=caller&tidy=true',
    { Authorization: 'account-token' }, { ...fallbackBody, target: 'https://evil.example', profile: 'walking' }), settings);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('Cache-Control'), 'no-store');
  assert.equal(reserved.limit, 4000);
  assert.equal(reserved.ipLimit, 1000);
  assert.equal(reserved.costUnits, 1);
  assert.equal(reserved.spacingMs, 1050);
});

test('LocationIQ rejects oversized windows, invalid accuracy, bad timestamps and Worker provenance without spending', async () => {
  const settings = fallbackEnv();
  settings.MAP_BUDGET.get = () => { throw new Error('must not reserve'); };
  const worker = createMapsGateway(() => { throw new Error('must not call'); });
  for (const value of [
    { ...fallbackBody, waypoints: Array(26).fill(fallbackBody.waypoints[0]) },
    { ...fallbackBody, waypoints: fallbackBody.waypoints.map(p => ({ ...p, accuracyMeters: 0 })) },
    { ...fallbackBody, waypoints: fallbackBody.waypoints.map(p => ({ ...p, accuracyMeters: 1001 })) },
    { ...fallbackBody, waypoints: fallbackBody.waypoints.map(p => ({ ...p, timestamp: body.waypoints[0].timestamp })) }
  ]) assert.equal((await worker.fetch(request('/v1/locationiq/mapmatching', {}, value), settings)).status, 400);
  assert.equal((await worker.fetch(request('/v1/locationiq/mapmatching', { 'CF-Worker': 'untrusted.example' }, fallbackBody), settings)).status, 403);
  assert.equal((await worker.fetch(request('/v1/locationiq/mapmatching', {}, fallbackBody), env())).status, 503);
});

test('LocationIQ accepts 25 timestamped points in one request and obeys exhausted shared allowance', async () => {
  let calls = 0;
  const worker = createMapsGateway(async url => {
    calls++;
    assert.equal(url.pathname.split('/').at(-1).split(';').length, 25);
    return Response.json({ code: 'NoMatch' });
  });
  const trace = { mode: 'drive', waypoints: Array.from({ length: 25 }, (_, i) => ({
    location: [-74 + i / 10000, 40], accuracyMeters: 3, timestamp: new Date(Date.UTC(2026, 0, 1, 0, 0, i)).toISOString()
  })) };
  assert.equal((await worker.fetch(request('/v1/locationiq/mapmatching', {}, trace), fallbackEnv())).status, 200);
  const settings = fallbackEnv();
  settings.MAP_BUDGET.get = () => ({ fetch: async () => Response.json({}, { status: 429, headers: { 'Retry-After': '3600' } }) });
  const blocked = await worker.fetch(request('/v1/locationiq/mapmatching', {}, trace), settings);
  assert.equal(blocked.status, 429);
  assert.equal(blocked.headers.get('Retry-After'), '3600');
  assert.equal(calls, 1);
});

test('documented LocationIQ NoMatch is valid while unknown errors remain secret-safe failures', async () => {
  for (const code of ['NoMatch', 'NoSegment']) {
    const worker = createMapsGateway(async () => Response.json({ code, message: 'private upstream detail' }, { status: 404 }));
    const response = await worker.fetch(request('/v1/locationiq/mapmatching', {}, fallbackBody), fallbackEnv());
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { code });
  }
  for (const status of [302, 401, 403, 404, 429, 500]) {
    const worker = createMapsGateway(async () => new Response('private upstream key', { status,
      headers: { Location: 'https://evil.example', 'Retry-After': '120' } }));
    const response = await worker.fetch(request('/v1/locationiq/mapmatching', {}, fallbackBody), fallbackEnv());
    assert.equal(response.status, status === 429 ? 429 : [401, 403].includes(status) ? 403 : 502);
    assert.equal(response.headers.get('Location'), null);
    assert.equal(response.headers.get('Retry-After'), '120');
    assert.equal((await response.text()).includes('private'), false);
  }
});

test('LocationIQ pacing survives restart and reserves fewer than 60 calls per minute', async () => {
  const db = new DatabaseSync(':memory:');
  const ctx = { storage: { sql: { exec(sql, ...bindings) {
    const statement = db.prepare(sql);
    return sql.startsWith('SELECT') ? statement.all(...bindings) : (statement.run(...bindings), []);
  } } } };
  const reserve = budget => budget.fetch(new Request('https://budget/reserve', { method: 'POST',
    body: JSON.stringify({ bucket: 'a'.repeat(64), limit: 4000, ipLimit: 1000, spacingMs: 1050 }) }));
  const first = await (await reserve(new MapBudget(ctx))).json();
  assert.equal(first.waitMs, 0);
  const second = await (await reserve(new MapBudget(ctx))).json();
  assert.ok(second.waitMs > 900 && second.waitMs <= 1050);
  const day = new Date().toISOString().slice(0, 10);
  db.prepare('UPDATE usage SET used = ? WHERE day = ? AND bucket = ?').run(1000, day, 'units:' + 'a'.repeat(64));
  assert.equal((await reserve(new MapBudget(ctx))).status, 429);
  db.close();
});
