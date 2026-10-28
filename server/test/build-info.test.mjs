import test from 'node:test';
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { cp, mkdir, mkdtemp, realpath, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const execute = promisify(execFile);

test('bundled liveness reports the checked-out revision without opening the database or trusting runtime overrides', async () => {
  let revision = process.env.ROADCONQUEST_SERVER_REVISION || null;
  try {
    const [checkout, head] = (await execute('git', ['rev-parse', '--show-toplevel', 'HEAD'],
      { cwd: new URL('../', import.meta.url) })).stdout.trim().split(/\r?\n/);
    if (await realpath(checkout) === await realpath(new URL('../../', import.meta.url))) revision = head;
  } catch { /* Source archives use the explicit build revision, or null. */ }
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
    body: { ok: true, api_version: 1, revision: revision?.toLowerCase() ?? null }, connections: 0 });
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

test('exported source inside another repository never claims the parent commit', async () => {
  const temporary = await mkdtemp(join(tmpdir(), 'roadconquest-build-'));
  try {
    // Deliberately place the exported server below a different Git checkout.
    await execute('git', ['init', '-q', temporary]);
    await execute('git', ['-C', temporary, '-c', 'user.name=Build Test', '-c', 'user.email=build@example.invalid',
      'commit', '--allow-empty', '-qm', 'Unrelated parent']);
    const archive = join(temporary, 'export', 'server');
    await mkdir(join(archive, 'tools'), { recursive: true });
    for (const path of ['src', 'package.json', 'tools/build-function.mjs']) {
      await cp(new URL('../' + path, import.meta.url), join(archive, path), { recursive: true });
    }
    await symlink(fileURLToPath(new URL('../node_modules', import.meta.url)), join(archive, 'node_modules'), 'junction');
    const environment = { ...process.env, DATABASE_URL: 'postgresql://unused@127.0.0.1:1/unavailable',
      PASSWORD_PEPPER: 'synthetic-build-info-test-pepper-32', FORUM_ENABLED: '0', LEADERBOARDS_ENABLED: '0' };
    environment.GITHUB_SHA = 'b'.repeat(40); // A packaging job's commit need not identify the exported source.
    delete environment.ROADCONQUEST_SERVER_REVISION;
    // Each process reads a newly built bundle, rather than reusing the module cache.
    const inspect = join(archive, 'inspect.mjs');
    await writeFile(inspect, `const { default: api, pool } = await import('./dist/index.mjs');
      pool.query = () => { throw Error('Build metadata must not query the database'); };
      try { console.log(JSON.stringify(await (await api.fetch(new Request('https://accounts.example/health/live'))).json())); }
      finally { await pool.end(); }`);
    for (const revision of [null, 'a'.repeat(40)]) {
      if (revision) environment.ROADCONQUEST_SERVER_REVISION = revision;
      await execute(process.execPath, [join(archive, 'tools/build-function.mjs')], { env: environment });
      const { stdout } = await execute(process.execPath, [inspect], { env: environment });
      assert.deepEqual(JSON.parse(stdout), { ok: true, api_version: 1, revision });
    }
  } finally { await rm(temporary, { recursive: true, force: true }); }
});
