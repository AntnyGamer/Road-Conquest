import test from "node:test";
import assert from "node:assert/strict";
import { createHash, generateKeyPairSync, randomUUID } from "node:crypto";
import { createIntegrityVerifier } from "../src/integrity.mjs";
import { createFetchHandler } from "../src/fetch-adapter.mjs";
import { backupMessage } from "../src/backups.mjs";

const privateKey = generateKeyPairSync("rsa", { modulusLength: 2048 }).privateKey
  .export({ type: "pkcs8", format: "pem" });
const config = {
  credentialsJson: JSON.stringify({ type: "service_account", client_email: "verifier@example.iam.gserviceaccount.com", private_key: privateKey }),
  packageName: "com.roadconquest.app", certificates: ["A".repeat(43)]
};

test('scoring and backup signing share OAuth credentials but independently verify every bound request', async () => {
  const environment = {
    DATABASE_URL: 'postgresql://unused@127.0.0.1:1/unavailable', PASSWORD_PEPPER: 'shared-integrity-test-pepper-'.repeat(2),
    NODE_ENV: 'production', REQUIRE_HTTPS: '1', TRUST_PROXY: '1', APPLY_SCHEMA_ON_STARTUP: '0',
    LEADERBOARDS_ENABLED: '1', FORUM_ENABLED: '0', FCM_SERVICE_ACCOUNT_JSON: '',
    PLAY_SERVICE_ACCOUNT_JSON: config.credentialsJson, PLAY_CERTIFICATES: config.certificates.join(','),
    PLAY_VERSION_CODES: '71', PLAY_CLOUD_PROJECT_NUMBER: '123456789',
    VERIFIED_EVIDENCE_KEY: Buffer.alloc(32, 3).toString('base64url'), BACKUP_VERIFICATION_KEYS_JSON: '[]',
    BACKUP_SIGNING_PRIVATE_KEY: generateKeyPairSync('ec', { namedCurve: 'prime256v1' }).privateKey
      .export({ type: 'pkcs8', format: 'pem' })
  };
  const previous = Object.fromEntries(Object.keys(environment).map(key => [key, process.env[key]]));
  const fetcher = globalThis.fetch;
  const hash = createHash('sha256').update('signed backup').digest('base64url');
  const requestHash = createHash('sha256').update(backupMessage(hash)).digest('base64url');
  let oauth = 0, pool;
  const decoded = [];
  try {
    Object.assign(process.env, environment);
    globalThis.fetch = async (url, init) => {
      if (url === 'https://oauth2.googleapis.com/token') {
        oauth++;
        return Response.json({ access_token: 'shared-access', expires_in: 3600 });
      }
      assert.equal(url, 'https://playintegrity.googleapis.com/v1/com.roadconquest.app:decodeIntegrityToken');
      assert.equal(init.headers.Authorization, 'Bearer shared-access');
      const token = JSON.parse(init.body).integrity_token;
      decoded.push(token);
      return Response.json({ tokenPayloadExternal: {
        requestDetails: { requestHash: token.startsWith('backup-') ? requestHash : 'wrong-bound-hash',
          requestPackageName: config.packageName, timestampMillis: String(Date.now()) },
        appIntegrity: { appRecognitionVerdict: 'PLAY_RECOGNIZED', packageName: config.packageName,
          versionCode: '90', certificateSha256Digest: config.certificates },
        accountDetails: { appLicensingVerdict: 'LICENSED' },
        deviceIntegrity: { deviceRecognitionVerdict: ['MEETS_DEVICE_INTEGRITY', 'MEETS_STRONG_INTEGRITY'] }
      } });
    };
    const application = await import('../src/application.mjs?shared-integrity-test');
    pool = application.pool;
    pool.query = async sql => ({ rows: sql.includes('LEFT JOIN cell_receipts')
      ? [{ leaderboard_eligible: true, active: true }]
      : [{ allowed: true, id: '1' }], rowCount: 1 });
    pool.connect = () => { throw Error('A mismatched attestation must not start a score transaction'); };
    const api = createFetchHandler(application.serve, { rateLimitIpSignatureKey: '' });
    const call = (path, body) => api(new Request('https://integrity.example' + path, {
      method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + 'T'.repeat(43) },
      body: JSON.stringify(body)
    }));
    const now = Date.now();
    const evidence = JSON.stringify({ run: randomUUID(), nonce: 'N'.repeat(43), sequence: 0,
      fixes: [0, 1].map(index => ({ lat: 40 + index * 0.0003, lon: -74, accuracy: 3, speed: 8,
        mock: false, time: now - 4000 + index * 3000, elapsed: 10000 + index * 3000 })) });
    const [backup, score] = await Promise.all([
      call('/v1/backups/sign', { hash, integrity_token: 'backup-integrity-token-example' }),
      call('/v1/verified/batch', { evidence, integrity_token: 'score-integrity-token-example' })
    ]);
    assert.equal(backup.status, 200);
    assert.equal((await backup.json()).kind, 'server-p256');
    assert.equal(score.status, 403, 'The scoring request must check its own attested fingerprint');
    const changed = await call('/v1/backups/sign', {
      hash: createHash('sha256').update('changed backup').digest('base64url'),
      integrity_token: 'backup-integrity-token-example'
    });
    assert.equal(changed.status, 403, 'A prior valid backup verdict must not be reused for changed contents');
    assert.equal(oauth, 1, 'Both features and the retry use one credential refresh');
    assert.deepEqual(decoded.sort(), ['backup-integrity-token-example', 'backup-integrity-token-example', 'score-integrity-token-example']);
  } finally {
    globalThis.fetch = fetcher;
    for (const [key, value] of Object.entries(previous)) {
      if (value === undefined) delete process.env[key]; else process.env[key] = value;
    }
    if (pool) await pool.end();
  }
});

test("invalid Google tokens are permanent failures while credential, quota and server outages remain retryable", async () => {
  for (const [upstream, expected] of [[400, 403], [401, 503], [403, 503], [429, 503], [500, 503]]) {
    const verify = await createIntegrityVerifier(config, async url => url === "https://oauth2.googleapis.com/token"
      ? Response.json({ access_token: "test-only-access-token", expires_in: 3600 })
      : Response.json({ error: {} }, { status: upstream }));
    await assert.rejects(verify("test-only-integrity-token", "hash"), error => error.status === expected);
  }
  const badCredentials = await createIntegrityVerifier(config, async () => Response.json({}, { status: 400 }));
  await assert.rejects(badCredentials("test-only-integrity-token", "hash"), error => error.status === 503);
});

test("concurrent verification shares OAuth refreshes and verifies each bound Google verdict", async () => {
  let oauthCalls = 0;
  const verify = await createIntegrityVerifier(config, async url => {
    if (url === "https://oauth2.googleapis.com/token") {
      oauthCalls++;
      return Response.json({ access_token: "test-only-access-token", expires_in: 3600 });
    }
    assert.equal(url, "https://playintegrity.googleapis.com/v1/com.roadconquest.app:decodeIntegrityToken");
    return Response.json({ tokenPayloadExternal: {
      requestDetails: { requestHash: "hash", requestPackageName: config.packageName, timestampMillis: String(Date.now()) },
      appIntegrity: { appRecognitionVerdict: "PLAY_RECOGNIZED", packageName: config.packageName,
        versionCode: "90", certificateSha256Digest: config.certificates },
      accountDetails: { appLicensingVerdict: "LICENSED" },
      deviceIntegrity: { deviceRecognitionVerdict: ["MEETS_DEVICE_INTEGRITY", "MEETS_STRONG_INTEGRITY"] }
    } });
  });
  await Promise.all([verify("test-only-integrity-token", "hash"), verify("test-only-integrity-token", "hash")]);
  assert.equal(oauthCalls, 1);
  await assert.rejects(verify("test-only-integrity-token", "changed"), error => error.status === 403);
  assert.equal(oauthCalls, 1);
});

test('null and empty Integrity OAuth replies do not cache credentials or send decode requests', async () => {
  for (const invalid of [null, { access_token: '', expires_in: 3600 },
    ...[-1, 0, 119].map(expires_in => ({ access_token: 'expired', expires_in }))]) {
    let auth = 0, decodes = 0;
    const verify = await createIntegrityVerifier(config, async url => {
      if (url === 'https://oauth2.googleapis.com/token') return Response.json(++auth === 1
        ? invalid : { access_token: 'recovered', expires_in: 3600 });
      decodes++;
      return Response.json({}, { status: 400 });
    });
    await assert.rejects(verify('test-only-integrity-token', 'hash'), error => error.status === 503);
    assert.equal(decodes, 0);
    await assert.rejects(verify('test-only-integrity-token', 'hash'), error => error.status === 403);
    assert.equal(auth, 2);
    assert.equal(decodes, 1);
  }
});

test('Integrity OAuth lifetimes never keep credentials for more than one hour', async () => {
  const originalNow = Date.now;
  let time = originalNow(), oauth = 0;
  Date.now = () => time;
  try {
    const verify = await createIntegrityVerifier(config, async url => {
      if (url === 'https://oauth2.googleapis.com/token') {
        oauth++;
        return Response.json({ access_token: 'access-' + oauth, expires_in: Number.MAX_VALUE });
      }
      return Response.json({}, { status: 400 });
    });
    await assert.rejects(verify('test-only-integrity-token', 'hash'), e => e.status === 403);
    time += 3600_000;
    await assert.rejects(verify('test-only-integrity-token', 'hash'), e => e.status === 403);
    assert.equal(oauth, 2);
  } finally { Date.now = originalNow; }
});

test('Integrity network failures, interrupted bodies and malformed decode replies remain retryable', async () => {
  for (const stage of ['oauth', 'decode', 'body', 'null', 'missing', 'array']) {
    let fail = true, decodes = 0;
    const verify = await createIntegrityVerifier(config, async url => {
      if (url === 'https://oauth2.googleapis.com/token') {
        if (fail && stage === 'oauth') throw new TypeError('connection reset');
        return Response.json({ access_token: 'access', expires_in: 3600 });
      }
      decodes++;
      if (!fail) return Response.json({}, { status: 400 });
      if (stage === 'decode') throw new DOMException('timed out', 'TimeoutError');
      if (stage === 'body') return new Response(new ReadableStream({
        start(controller) { controller.error(new TypeError('body interrupted')); }
      }));
      return Response.json(stage === 'null' ? null : stage === 'array' ? [] : {});
    });
    await assert.rejects(verify('test-only-integrity-token', 'hash'), e => e.status === 503, stage);
    fail = false;
    await assert.rejects(verify('test-only-integrity-token', 'hash'), e => e.status === 403, stage);
    assert.equal(decodes, stage === 'oauth' ? 1 : 2);
  }
});

test('rejected Integrity OAuth credentials refresh on retry without a delayed failure discarding the new token', async () => {
  let oauth = 0;
  const started = Promise.withResolvers(), delayed = Promise.withResolvers();
  const verify = await createIntegrityVerifier(config, async (url, init) => {
    if (url === 'https://oauth2.googleapis.com/token') {
      return Response.json({ access_token: 'access-' + (++oauth), expires_in: 3600 });
    }
    const token = JSON.parse(init.body).integrity_token;
    if (token === 'slow-test-integrity-token') { started.resolve(); return delayed.promise; }
    if (token === 'rejected-test-integrity-token') return Response.json({}, { status: 401 });
    assert.equal(init.headers.Authorization, 'Bearer access-2');
    return Response.json({ tokenPayloadExternal: {
      requestDetails: { requestHash: 'hash', requestPackageName: config.packageName, timestampMillis: String(Date.now()) },
      appIntegrity: { appRecognitionVerdict: 'PLAY_RECOGNIZED', packageName: config.packageName,
        versionCode: '90', certificateSha256Digest: config.certificates },
      accountDetails: { appLicensingVerdict: 'LICENSED' },
      deviceIntegrity: { deviceRecognitionVerdict: ['MEETS_DEVICE_INTEGRITY', 'MEETS_STRONG_INTEGRITY'] }
    } });
  });
  const slow = assert.rejects(verify('slow-test-integrity-token', 'hash'), e => e.status === 503);
  await started.promise;
  await assert.rejects(verify('rejected-test-integrity-token', 'hash'), e => e.status === 503);
  await verify('fresh-test-integrity-token', 'hash');
  delayed.resolve(Response.json({}, { status: 401 }));
  await slow;
  await verify('fresh-again-test-integrity-token', 'hash');
  assert.equal(oauth, 2);
});

test('invalid Integrity tokens stay permanent failures even if body cleanup fails or stalls', { timeout: 2000 }, async () => {
  for (const fail of [true, false]) {
    let cleaned = false;
    const verify = await createIntegrityVerifier(config, async url => url === 'https://oauth2.googleapis.com/token'
      ? Response.json({ access_token: 'access', expires_in: 3600 })
      : { status: 400, body: { cancel() {
        cleaned = true;
        return fail ? Promise.reject(new Error('cleanup failed')) : new Promise(() => {});
      } } });
    await assert.rejects(verify('invalid-test-integrity-token', 'hash'), e => e.status === 403);
    assert.equal(cleaned, true);
  }
});
