import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { sourceRevision } from '../tools/source-revision.mjs';
import { verifyDeployment } from '../tools/verify-deployment.mjs';
import { createAccountIngress } from '../worker/ingress.mjs';

const accountRevision = 'a'.repeat(40);
const ingressRevision = 'b'.repeat(40);
const intended = { accountRevision, ingressRevision };

test('ingress liveness requires no configuration, credentials, upstream fetch or signing', async () => {
  const ingress = createAccountIngress(() => { throw Error('must not contact the account function'); });
  for (const method of ['GET', 'HEAD']) {
    const response = await ingress(new Request('https://accounts.example/health/ingress', { method }));
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('Cache-Control'), 'no-store');
    assert.equal(response.headers.get('Content-Type'), 'application/json');
    if (method === 'GET') assert.deepEqual(await response.json(), { ok: true, service: 'account-ingress', revision: null });
    else assert.equal(await response.text(), '');
  }
  for (const request of [new Request('http://accounts.example/health/ingress'),
    new Request('https://accounts.example/health/ingress', { method: 'POST' })]) {
    assert.notEqual((await ingress(request)).status, 200);
  }
});

test('bundled ingress reports its build revision and ignores runtime overrides', () => {
  const moduleUrl = new URL('../dist/ingress.mjs', import.meta.url).href;
  const output = execFileSync(process.execPath, ['--input-type=module', '-e', `
    globalThis.fetch = () => { throw Error('must not contact any upstream'); };
    const { default: worker } = await import(${JSON.stringify(moduleUrl)});
    const response = await worker.fetch(new Request('https://accounts.example/health/ingress'));
    console.log(JSON.stringify(await response.json()));
  `], { encoding: 'utf8', env: { ...process.env, ROADCONQUEST_SERVER_REVISION: 'f'.repeat(40) }, timeout: 15000 });
  assert.deepEqual(JSON.parse(output), { ok: true, service: 'account-ingress', revision: sourceRevision() });
});

test('release check compares both independent intended revisions using public read-only requests', async () => {
  const requests = [];
  const result = await verifyDeployment({ accountRevision: accountRevision.toUpperCase(), ingressRevision }, (url, options) => {
    requests.push({ url: url.href, options });
    return Response.json({ ok: true, revision: url.pathname === '/health/live' ? accountRevision : ingressRevision });
  });
  assert.deepEqual(result, [ { service: 'account function', revision: accountRevision },
    { service: 'account ingress', revision: ingressRevision } ]);
  assert.equal(requests.length, 2);
  for (const { url, options } of requests) {
    assert.match(url, /^https:\/\/roadconquest-api\.22abongiovanni\.workers\.dev\/health\/(live|ingress)$/);
    assert.equal(options.method, undefined);
    assert.equal(options.redirect, 'error');
    assert.deepEqual(options.headers, { Accept: 'application/json' });
    assert.ok(options.signal instanceof AbortSignal);
  }
});

test('release check rejects unknown, mismatched or unsuccessful service revisions', async () => {
  for (const body of [{ ok: true, revision: null }, { ok: true, revision: 'c'.repeat(40) },
    { ok: false, revision: accountRevision }]) {
    await assert.rejects(verifyDeployment(intended, () => Response.json(body)), /revision mismatch/);
  }
  await assert.rejects(verifyDeployment(intended, () => new Response('', { status: 503 })), /HTTP 503/);
  await assert.rejects(verifyDeployment(intended, () => new Response('{broken')), Error);
  await assert.rejects(verifyDeployment(intended, () => Response.json({ padding: 'x'.repeat(4097) })), Error);
  await assert.rejects(verifyDeployment(intended, () => { throw Error('network unavailable'); }), /network unavailable/);
});

test('release check validates origins and full hashes before making requests', async () => {
  const neverFetch = () => { throw Error('invalid input must not fetch'); };
  for (const origin of ['http://accounts.example', 'https://user:password@accounts.example',
    'https://accounts.example/path', 'https://accounts.example?query=1', 'https://accounts.example#fragment']) {
    await assert.rejects(verifyDeployment({ ...intended, origin }, neverFetch), /HTTPS account origin/);
  }
  for (const revision of ['', null, 'abc123', 'g'.repeat(40)]) {
    await assert.rejects(verifyDeployment({ ...intended, ingressRevision: revision }, neverFetch), /full intended/);
  }
});
