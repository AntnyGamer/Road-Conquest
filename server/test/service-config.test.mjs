import test from 'node:test';
import assert from 'node:assert/strict';
import { serviceSwitches, parseSwitches, DEFAULT_SWITCHES } from '../maps/config.mjs';
import { createMapsGateway } from '../maps/worker.mjs';

test('remote switches accept only known booleans and coalesce/cache KV reads', async () => {
  const parsed = parseSwitches({ version: 1, providers: { 'geoapify-matching': false, openfreemap: 'false', host: 'evil' } });
  assert.equal(parsed['geoapify-matching'], false); assert.equal(parsed.openfreemap, true); assert.equal(parsed.host, undefined);
  assert.throws(() => parseSwitches({ version: 2, providers: {} }));
  // A scalar providers field must not silently enable a previously disabled
  // service by being interpreted as a valid empty collection of switches.
  for (const invalid of [null, true, 4, 'disabled', []]) {
    assert.throws(() => parseSwitches({ version: 1, providers: invalid }), /Invalid configuration/);
  }
  let reads = 0; let fail = false; let removed = false;
  const env = { SERVICE_CONFIG: { get: async () => { reads++; if (fail) throw new Error();
    return removed ? null : JSON.stringify({ version: 1, providers: { 'geoapify-matching': false } }); } } };
  await Promise.all(Array.from({ length: 100 }, () => serviceSwitches(env, 1000)));
  assert.equal(reads, 1); fail = true;
  assert.equal((await serviceSwitches(env, 62_000))['geoapify-matching'], false);
  assert.equal(reads, 2); assert.deepEqual(await serviceSwitches({}), DEFAULT_SWITCHES);
  fail = false; removed = true;
  assert.deepEqual(await serviceSwitches(env, 123_000), DEFAULT_SWITCHES,
    'Removing a provider override must restore defaults, unlike transient KV failures');
  assert.equal(reads, 3);
  assert.deepEqual(await serviceSwitches(env, 124_000), DEFAULT_SWITCHES);
  assert.equal(reads, 3, 'The restored defaults retain the existing cache lifetime');
});
test('disabled providers never reserve quota or fetch upstream, and health supports free HEAD monitoring', async () => {
  const gateway = createMapsGateway(() => { throw new Error('No upstream allowed'); });
  const env = { GEOAPIFY_API_KEY: 'fake', MAP_BUDGET: {}, SERVICE_CONFIG: { get: async () => JSON.stringify({ version: 1, providers: { 'geoapify-matching': false } }) } };
  const request = new Request('https://maps.example/v1/mapmatching', { method: 'POST', body: JSON.stringify({ mode: 'drive', waypoints: [
    { location: [0,0], timestamp: '2026-01-01T00:00:00Z' }, { location: [0.001,0], timestamp: '2026-01-01T00:00:10Z' }
  ] }) });
  assert.equal((await gateway.fetch(request, env)).status, 503);
  assert.equal((await gateway.fetch(new Request('https://maps.example/health', { method: 'HEAD' }), env)).status, 200);
  const config = await gateway.fetch(new Request('https://maps.example/v1/config'), env);
  assert.equal(config.headers.get('Cache-Control'), 'public, max-age=300');
  assert.equal((await config.json()).providers['geoapify-matching'], false);
});

test('synchronous KV failures preserve switches and recover after the existing cache timeout',async()=>{
  let mode='disabled',reads=0;
  const env={SERVICE_CONFIG:{get:()=>{reads++;if(mode==='fail')throw new Error('KV unavailable');
    return Promise.resolve(JSON.stringify({version:1,providers:{'geoapify-matching':mode==='enabled'}}));}}};
  assert.equal((await serviceSwitches(env,1000))['geoapify-matching'],false);
  mode='fail';
  assert.equal((await serviceSwitches(env,62000))['geoapify-matching'],false);
  mode='enabled';
  const values=await Promise.all(Array.from({length:100},()=>serviceSwitches(env,123000)));
  assert.equal(reads,3);
  assert.equal(values.every(v=>v['geoapify-matching']),true);
});
