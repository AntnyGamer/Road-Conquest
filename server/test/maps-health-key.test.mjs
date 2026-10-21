import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { createMapsGateway } from '../maps/worker.mjs';

const points = [
  { location: [-74, 40], timestamp: '2026-01-01T00:00:00Z', accuracyMeters: 5 },
  { location: [-74.001, 40], timestamp: '2026-01-01T00:00:10Z', accuracyMeters: 5 }
];
const request = (fallback = false, ip = '192.0.2.1') => new Request(
  'https://maps.example/v1/' + (fallback ? 'locationiq/mapmatching' : 'mapmatching'),
  { method: 'POST', headers: { 'CF-Connecting-IP': ip }, body: JSON.stringify({ mode: 'drive', waypoints: points }) }
);

function signatures(t, failHealthOnce = false) {
  const prior = Object.getOwnPropertyDescriptor(crypto.subtle, 'sign');
  const sign = crypto.subtle.sign;
  const count = { health: 0, client: 0 };
  crypto.subtle.sign = function (...args) {
    if (new TextDecoder().decode(args[2]) === 'health:matching') {
      count.health++;
      if (failHealthOnce) { failHealthOnce = false; throw new Error('temporary health derivation failure'); }
    } else count.client++;
    return sign.apply(this, args);
  };
  t.after(() => {
    if (prior) Object.defineProperty(crypto.subtle, 'sign', prior);
    else delete crypto.subtle.sign;
  });
  return count;
}

function environment(primary = 'primary-test-only', fallback = 'fallback-test-only', reserved = []) {
  return { GEOAPIFY_API_KEY: primary, LOCATIONIQ_API_KEY: fallback, MAP_BUDGET: {
    idFromName: name => name,
    get: name => ({ fetch: async (_url, init) => {
      reserved.push({ name, ...JSON.parse(init.body) });
      return Response.json({ allowed: true });
    } })
  } };
}

test('concurrent driving batches reuse only the fixed provider health identity, preserving every client reservation and upstream call', async t => {
  const count = signatures(t), reserved = [];
  const env = environment(undefined, undefined, reserved);
  let upstreamCalls = 0;
  const worker = createMapsGateway(async (url, init) => {
    upstreamCalls++;
    if (url.hostname === 'api.geoapify.com') {
      assert.deepEqual(JSON.parse(init.body), { mode: 'drive', waypoints: points.map(({ location, timestamp }) => ({ location, timestamp })) });
    } else assert.equal(url.searchParams.get('timestamps'), '1767225600;1767225610');
    return Response.json({ type: 'FeatureCollection', features: [] });
  });
  const today = new Date().toISOString().slice(0, 10);
  await Promise.all(Array.from({ length: 200 }, async (_, i) => {
    const response = await worker.fetch(request(i % 2 === 1, '192.0.2.' + (i + 1)), env);
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('Cache-Control'), 'no-store');
    await response.body.cancel();
  }));
  assert.equal(upstreamCalls, 200);
  assert.equal(reserved.length, 200);
  for (const entry of reserved) {
    const key = entry.name.startsWith('locationiq') ? env.LOCATIONIQ_API_KEY : env.GEOAPIFY_API_KEY;
    assert.equal(entry.healthKey, createHmac('sha256', key).update('health:matching').digest('hex'));
    assert.equal(entry.costUnits, entry.name.startsWith('locationiq') ? 1 : 4);
    assert.match(entry.bucket, /^[a-f0-9]{64}$/);
  }
  assert.equal(new Set(reserved.map(entry => entry.attemptId)).size, 200);
  assert.equal(new Set(reserved.map(entry => entry.bucket)).size, 200);
  // Every client identity is still freshly calculated, even when request inputs repeat.
  assert.equal(count.client, 200);
  assert.equal(count.health, 2, 'One fixed identity per provider secret, coalesced across concurrent calls');
  if (new Date().toISOString().slice(0, 10) === today) {
    const expected = new Set(Array.from({ length: 200 }, (_, i) => createHmac('sha256',
      i % 2 ? env.LOCATIONIQ_API_KEY : env.GEOAPIFY_API_KEY)
      .update(today + '\n192.0.2.' + (i + 1)).digest('hex')));
    assert.deepEqual(new Set(reserved.map(entry => entry.bucket)), expected);
  }
});

test('health identities stay bounded across provider-key rotation and never affect tile signing', async t => {
  const count = signatures(t), reserved = [];
  const worker = createMapsGateway(async url => url.pathname.includes('/tile/')
    ? new Response(new Uint8Array([137, 80, 78, 71]), { headers: { 'Content-Type': 'image/png' } })
    : Response.json({}));
  for (const secret of ['first', 'first', 'second', 'third', 'first']) {
    const response = await worker.fetch(request(), environment(secret, undefined, reserved));
    assert.equal(response.status, 200);
    assert.equal(reserved.at(-1).healthKey, createHmac('sha256', secret).update('health:matching').digest('hex'));
    await response.body.cancel();
  }
  assert.equal(count.health, 4, 'Two-entry cache evicts the oldest secret on rotation');
  const tile = await worker.fetch(new Request('https://maps.example/v1/tile/positron/1/0/0.png',
    { headers: { 'CF-Connecting-IP': '192.0.2.1' } }), environment('first'));
  assert.equal(tile.status, 200);
  await tile.body.cancel();
  assert.equal(count.client, 6);
  assert.equal(count.health, 4, 'Tiles never derive or reuse a matching health identity');
});

test('a failed health derivation retries instead of permanently poisoning a warm gateway', async t => {
  const count = signatures(t, true);
  let upstreamCalls = 0;
  const worker = createMapsGateway(async () => { upstreamCalls++; return Response.json({}); });
  const env = environment();
  await assert.rejects(worker.fetch(request(), env), /temporary health derivation failure/);
  for (let i = 0; i < 2; i++) {
    const response = await worker.fetch(request(), env);
    assert.equal(response.status, 200);
    await response.body.cancel();
  }
  assert.equal(upstreamCalls, 2);
  assert.equal(count.health, 2);
  assert.equal(count.client, 3);
});
