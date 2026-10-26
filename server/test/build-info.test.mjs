import test from 'node:test';
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const execute = promisify(execFile);

test('bundled liveness reports the checked-out revision without opening the database or trusting runtime overrides', async () => {
  const { stdout: revision } = await execute('git', ['rev-parse', 'HEAD'], { cwd: new URL('../', import.meta.url) });
  const moduleUrl = new URL('../dist/index.mjs', import.meta.url).href;
  const { stdout } = await execute(process.execPath, ['--input-type=module', '-e', `
    const { default: api, pool } = await import(${JSON.stringify(moduleUrl)});
    pool.query = () => { throw Error('Liveness must not open a database connection'); };
    try {
      const response = await api.fetch(new Request('https://accounts.example/health/live'));
      console.log(JSON.stringify({ status: response.status, cache: response.headers.get('Cache-Control'),
        body: await response.json(), connections: pool.totalCount }));
    } finally { await pool.end(); }
  `], { env: { ...process.env, DATABASE_URL: 'postgresql://unused@127.0.0.1:1/unavailable',
    PASSWORD_PEPPER: 'synthetic-build-info-test-pepper-32', REQUIRE_HTTPS: '1', TRUST_PROXY: '1',
    FORUM_ENABLED: '0', LEADERBOARDS_ENABLED: '0', ROADCONQUEST_SERVER_REVISION: 'f'.repeat(40) }, timeout: 15000 });
  assert.deepEqual(JSON.parse(stdout), { status: 200, cache: 'no-store',
    body: { ok: true, api_version: 1, revision: revision.trim() }, connections: 0 });
});

test('native revision metadata accepts only a full public commit hash', async () => {
  const moduleUrl = new URL('../src/build-info.mjs', import.meta.url).href;
  for (const [value, expected] of [['A'.repeat(40), 'a'.repeat(40)], ['secret-like-invalid-value', null], ['', null]]) {
    const { stdout } = await execute(process.execPath, ['--input-type=module', '-e',
      `const { SERVER_INFO } = await import(${JSON.stringify(moduleUrl)}); console.log(JSON.stringify(SERVER_INFO));`],
    { env: { ...process.env, ROADCONQUEST_SERVER_REVISION: value } });
    assert.deepEqual(JSON.parse(stdout), { api_version: 1, revision: expected });
  }
});
