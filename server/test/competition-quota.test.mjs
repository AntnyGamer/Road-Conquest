import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import pg from 'pg';
import { createCompetition } from '../src/competition.mjs';
import { createIntegrityVerifier } from '../src/integrity.mjs';
import { evidenceHash, EvidenceError } from '../src/scoring.mjs';
import { GRID_ID, LATITUDE_STEP, LONGITUDE_STEP, cellFor } from '../src/cells.mjs';

const config = { dataset: GRID_ID, checkpointKey: 'K'.repeat(43), packageName: 'com.roadconquest.app',
  certificates: ['A'.repeat(43)] };
const gate = () => Promise.withResolvers();
function evidence(run = { run: '00000000-0000-4000-8000-000000000001', nonce: 'N'.repeat(43), sequence: 0 }) {
  const cell = cellFor(40, -75), now = Date.now();
  const lat = -90 + (cell.row + 0.5) * LATITUDE_STEP;
  const lon = -180 + (cell.column + 0.5) * LONGITUDE_STEP;
  return JSON.stringify({ ...run, fixes: [
    { lat, lon, accuracy: 3, speed: 10, time: now - 5000, elapsed: 10000, mock: false },
    { lat, lon: lon + .0005, accuracy: 3, speed: 10, time: now, elapsed: 15000, mock: false }
  ] });
}
const preflightPool = { query: async () => ({ rows: [{ leaderboard_eligible: true, active: true }] }),
  connect: async () => { throw new Error('transaction reached'); } };

test('twenty concurrent exact submissions spend one Google decode and commit one unchanged receipt',
  { skip: !process.env.DATABASE_URL, timeout: 20_000 }, async () => {
    const pool = new pg.Pool({ connectionString: process.env.DATABASE_URL });
    const allPreflight = gate(), release = gate();
    try {
      await pool.query(await readFile(new URL('../schema.sql', import.meta.url), 'utf8'));
      await pool.query('TRUNCATE users CASCADE');
      const user = (await pool.query(`INSERT INTO users(username_display,username_key,password_salt,password_hash)
        VALUES ('Quota','quota',$1,$2) RETURNING id`, [Buffer.alloc(16), Buffer.alloc(64)])).rows[0];
      let preflights = 0, oauth = 0, decodes = 0;
      const tracked = { connect: () => pool.connect(), async query(...args) {
        const result = await pool.query(...args);
        if (args[0].includes('FROM users u LEFT JOIN cell_receipts') && ++preflights === 20) allPreflight.resolve();
        return result;
      } };
      const privateKey = generateKeyPairSync('rsa', { modulusLength: 2048 }).privateKey
        .export({ type: 'pkcs8', format: 'pem' });
      const verifier = await createIntegrityVerifier({ ...config, credentialsJson: JSON.stringify({
        type: 'service_account', client_email: 'fixture@example.iam.gserviceaccount.com', private_key: privateKey
      }) }, async (url, init) => {
        if (url === 'https://oauth2.googleapis.com/token') {
          oauth++; return Response.json({ access_token: 'fixture-access', expires_in: 3600 });
        }
        assert.equal(url, `https://playintegrity.googleapis.com/v1/${config.packageName}:decodeIntegrityToken`);
        assert.deepEqual(JSON.parse(init.body), { integrity_token: 'test-only-integrity-token' });
        decodes++; await release.promise;
        return Response.json({ tokenPayloadExternal: {
          requestDetails: { requestHash: hash, requestPackageName: config.packageName, timestampMillis: String(Date.now()) },
          appIntegrity: { appRecognitionVerdict: 'PLAY_RECOGNIZED', packageName: config.packageName,
            versionCode: '90', certificateSha256Digest: config.certificates },
          accountDetails: { appLicensingVerdict: 'LICENSED' },
          deviceIntegrity: { deviceRecognitionVerdict: ['MEETS_DEVICE_INTEGRITY', 'MEETS_STRONG_INTEGRITY'] }
        } });
      });
      const service = await createCompetition(tracked, config, { verify: verifier });
      const body = evidence(await service.begin(user)), hash = evidenceHash(body);
      const requests = Array.from({ length: 20 }, () => service.submit(user, body, 'test-only-integrity-token'));
      await allPreflight.promise;
      // All calls have passed their independent eligibility/freshness/challenge
      // reads; let every caller enter verification before releasing Google.
      await new Promise(resolve => setImmediate(resolve));
      release.resolve();
      const results = await Promise.all(requests);
      assert.equal(decodes, 1, 'duplicate in-flight work must not spend another Google decode');
      assert.equal(oauth, 1);
      for (const result of results) assert.deepEqual(result, results[0]);
      assert.equal(results[0].cells, 1);
      assert.equal(results[0].sequence, 1);
      assert.equal((await pool.query('SELECT COUNT(*)::integer n FROM cell_receipts')).rows[0].n, 1);
      assert.equal((await pool.query('SELECT sequence FROM cell_runs')).rows[0].sequence, 1);
      assert.equal((await pool.query('SELECT COUNT(*)::integer n FROM competition_cells')).rows[0].n, 1);
      assert.deepEqual(await service.submit(user, body, 'a-new-token-is-not-needed-for-a-receipt'), results[0]);
      assert.equal(decodes, 1, 'completed exact retries still use the durable receipt');
    } finally { release.resolve(); await pool.end(); }
  });

test('in-flight verification is scoped to exact user, evidence and token; failures are never cached', { timeout: 5000 }, async () => {
  const release = gate(), entered = gate();
  let calls = 0, reject = true;
  const service = await createCompetition(preflightPool, config, { verify: async () => {
    if (++calls === 4) entered.resolve();
    await release.promise;
    if (reject) throw new EvidenceError('fixture verification failure', 503);
  } });
  const body = evidence(), changed = JSON.stringify({ ...JSON.parse(body), sequence: 1 });
  const outcomes = Promise.allSettled([
    service.submit({ id: 'one' }, body, 'token-a'), service.submit({ id: 'one' }, body, 'token-a'),
    service.submit({ id: 'two' }, body, 'token-a'), service.submit({ id: 'one' }, changed, 'token-a'),
    service.submit({ id: 'one' }, body, 'token-b')
  ]);
  await entered.promise; await new Promise(resolve => setImmediate(resolve)); release.resolve();
  const rejected = await outcomes;
  assert.equal(calls, 4);
  assert.ok(rejected.every(result => result.status === 'rejected' && result.reason.status === 503));
  reject = false;
  await assert.rejects(service.submit({ id: 'one' }, body, 'token-a'), /transaction reached/);
  assert.equal(calls, 5, 'a failed check must be retried normally');
  await assert.rejects(service.submit({ id: 'one' }, body, 'token-a'), /transaction reached/);
  assert.equal(calls, 6, 'a successful check is also removed immediately after settling');
});

test('shared verification still checks current eligibility separately before every score transaction',
  { skip: !process.env.DATABASE_URL, timeout: 20_000 }, async () => {
    const pool = new pg.Pool({ connectionString: process.env.DATABASE_URL });
    const allPreflight = gate(), release = gate();
    let requests = [];
    try {
      await pool.query(await readFile(new URL('../schema.sql', import.meta.url), 'utf8'));
      await pool.query('TRUNCATE users CASCADE');
      const user = (await pool.query(`INSERT INTO users(username_display,username_key,password_salt,password_hash)
        VALUES ('Moderated','moderated',$1,$2) RETURNING id`, [Buffer.alloc(16), Buffer.alloc(64)])).rows[0];
      let preflights = 0, verifications = 0;
      const tracked = { connect: () => pool.connect(), async query(...args) {
        const result = await pool.query(...args);
        if (args[0].includes('FROM users u LEFT JOIN cell_receipts') && ++preflights === 20) allPreflight.resolve();
        return result;
      } };
      const service = await createCompetition(tracked, config, { verify: async () => { verifications++; await release.promise; } });
      const body = evidence(await service.begin(user));
      requests = Array.from({ length: 20 }, () => service.submit(user, body, 'same-token'));
      const outcomes = Promise.allSettled(requests);
      await allPreflight.promise;
      await pool.query('UPDATE users SET leaderboard_eligible=FALSE WHERE id=$1', [user.id]);
      release.resolve();
      assert.ok((await outcomes).every(result => result.status === 'rejected' && result.reason.status === 403));
      assert.equal(verifications, 1);
      assert.equal((await pool.query('SELECT COUNT(*)::integer n FROM competition_cells')).rows[0].n, 0);
      assert.equal((await pool.query('SELECT COUNT(*)::integer n FROM cell_receipts')).rows[0].n, 0);
      assert.equal((await pool.query('SELECT sequence FROM cell_runs')).rows[0].sequence, 0);
    } finally { release.resolve(); await Promise.allSettled(requests); await pool.end(); }
  });

test('verification sharing stays bounded and full capacity falls back without evicting active work', { timeout: 5000 }, async () => {
  const release = gate(), filled = gate(), overflow = gate();
  let calls = 0;
  const service = await createCompetition(preflightPool, config, { verify: async () => {
    calls++;
    if (calls === 64) filled.resolve();
    if (calls === 66) overflow.resolve();
    await release.promise;
  } });
  const body = evidence();
  const requests = Array.from({ length: 64 }, (_, i) => service.submit({ id: String(i) }, body, 'token'));
  await filled.promise;
  requests.push(service.submit({ id: '0' }, body, 'token'));
  requests.push(service.submit({ id: 'overflow' }, body, 'token'), service.submit({ id: 'overflow' }, body, 'token'));
  const outcomes = Promise.allSettled(requests);
  await overflow.promise; release.resolve();
  assert.equal(calls, 66, 'the existing entry is shared; the two new calls use the ordinary bounded fallback');
  assert.ok((await outcomes).every(result => result.status === 'rejected' && /transaction reached/.test(result.reason.message)));
  await assert.rejects(service.submit({ id: '0' }, body, 'token'), /transaction reached/);
  assert.equal(calls, 67);
});
