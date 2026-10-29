import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash, createPrivateKey, createPublicKey, generateKeyPairSync, verify } from 'node:crypto';
import { mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import { generateBackupKey } from '../tools/generate-backup-key.mjs';
import { checkBackupConfig } from '../tools/check-backup-config.mjs';
import { backupMessage, createBackups } from '../src/backups.mjs';

const execute = promisify(execFile);
const credentials = JSON.stringify({ type: 'service_account', client_email: 'offline-test@example.iam.gserviceaccount.com',
  private_key: generateKeyPairSync('rsa', { modulusLength: 2048 }).privateKey.export({ type: 'pkcs8', format: 'pem' }) });
const base = { NODE_ENV: 'production', REQUIRE_HTTPS: '1', TRUST_PROXY: '1', APPLY_SCHEMA_ON_STARTUP: '0',
  PLAY_SERVICE_ACCOUNT_JSON: credentials, PLAY_CERTIFICATES: Buffer.alloc(32, 1).toString('base64url'),
  PLAY_VERSION_CODES: '90,91', PLAY_CLOUD_PROJECT_NUMBER: '123456789' };

test('generated PKCS#8 P-256 keys sign valid portable backups, survive rotation and cannot be overwritten', async () => {
  const parent = await mkdtemp(join(tmpdir(), 'roadconquest-backup-keys-'));
  try {
    const target = join(parent, 'first');
    const entry = await generateBackupKey(target);
    const pemPath = join(target, 'backup-signing-private.pem');
    const pem = await readFile(pemPath, 'utf8');
    assert.match(pem, /^-----BEGIN PRIVATE KEY-----/);
    const key = createPrivateKey(pem);
    assert.equal(key.asymmetricKeyDetails.namedCurve, 'prime256v1');
    const der = createPublicKey(key).export({ type: 'spki', format: 'der' });
    assert.deepEqual(entry, { id: createHash('sha256').update(der).digest('base64url'), spki: der.toString('base64url') });
    assert.deepEqual(JSON.parse(await readFile(join(target, 'backup-verification-key.json'), 'utf8')), entry);
    if (process.platform !== 'win32') {
      assert.equal((await stat(target)).mode & 0o777, 0o700);
      assert.equal((await stat(pemPath)).mode & 0o777, 0o600);
    }
    await assert.rejects(generateBackupKey(target), { code: 'EEXIST' });
    assert.equal(await readFile(pemPath, 'utf8'), pem);
    const service = await createBackups({ ...base, BACKUP_SIGNING_PRIVATE_KEY: pem }, { verify: async () => {} });
    const hash = createHash('sha256').update('offline round trip').digest('base64url');
    const seal = await service.sign({ hash, integrity_token: 'offline-test-integrity-token' });
    assert.equal(verify('sha256', backupMessage(hash), createPublicKey(key), Buffer.from(seal.signature, 'base64url')), true);
    const next = join(parent, 'next');
    await generateBackupKey(next);
    const env = { ...base, BACKUP_SIGNING_PRIVATE_KEY: await readFile(join(next, 'backup-signing-private.pem'), 'utf8'),
      BACKUP_VERIFICATION_KEYS_JSON: JSON.stringify([entry.spki]) };
    const status = await checkBackupConfig(env, 91);
    assert.equal(status.available, true);
    assert.equal(status.verification_key_ids.length, 2);
    assert.ok(status.verification_key_ids.includes(seal.key_id));
    const retired = (await createBackups({ BACKUP_VERIFICATION_KEYS_JSON: env.BACKUP_VERIFICATION_KEYS_JSON })).status();
    assert.equal(retired.available, false);
    assert.equal(retired.keys[0].id, seal.key_id);
    assert.equal(verify('sha256', backupMessage(hash), createPublicKey({ key: Buffer.from(retired.keys[0].spki, 'base64url'),
      format: 'der', type: 'spki' }), Buffer.from(seal.signature, 'base64url')), true);
  } finally { await rm(parent, { recursive: true, force: true }); }
});

test('offline release preflight accepts existing credentials and rejects stale versions, unsafe startup and invalid keys', async () => {
  const pem = generateKeyPairSync('ec', { namedCurve: 'prime256v1' }).privateKey.export({ type: 'pkcs8', format: 'pem' });
  const env = { ...base, BACKUP_SIGNING_PRIVATE_KEY: pem, FORUM_ENABLED: '1', VERIFIED_EVIDENCE_KEY: 'unchanged' };
  const status = await checkBackupConfig(env, '91');
  assert.equal(status.version_code, 91);
  for (const version of [90, 92, 1000000]) {
    assert.equal((await checkBackupConfig({ ...env, PLAY_VERSION_CODES: '90' }, version)).version_code, version);
  }
  await assert.rejects(checkBackupConfig(env, 89), /90 or newer/);
  assert.equal(status.cloud_project, base.PLAY_CLOUD_PROJECT_NUMBER);
  assert.doesNotMatch(JSON.stringify(status), /PRIVATE KEY|service_account|unchanged/);
  for (const change of [
    { BACKUP_SIGNING_PRIVATE_KEY: '' }, { BACKUP_SIGNING_PRIVATE_KEY: 'invalid-private-material' },
    { BACKUP_SIGNING_PRIVATE_KEY: credentials }, { PLAY_CERTIFICATES: 'wrong' },
    { PLAY_CLOUD_PROJECT_NUMBER: '' }, { PLAY_SERVICE_ACCOUNT_JSON: '{private-material' },
    { PLAY_SERVICE_ACCOUNT_JSON: JSON.stringify({ type: 'service_account', client_email: 'test', private_key: pem }) },
    { BACKUP_VERIFICATION_KEYS_JSON: '[private-material' }, { NODE_ENV: 'development' }, { APPLY_SCHEMA_ON_STARTUP: '1' },
    { REQUIRE_HTTPS: '0' }, { TRUST_PROXY: '0' }
  ]) {
    await assert.rejects(checkBackupConfig({ ...env, ...change }, 91), error => {
      assert.doesNotMatch(error.message, /private-material|BEGIN PRIVATE|serviceaccount\.com/);
      return true;
    });
  }
  const parent = await mkdtemp(join(tmpdir(), 'roadconquest-backup-config-'));
  try {
    const file = join(parent, 'service-account.json');
    await writeFile(file, credentials, { mode: 0o600 });
    assert.equal((await checkBackupConfig({ ...env, PLAY_SERVICE_ACCOUNT_JSON: '', PLAY_SERVICE_ACCOUNT_FILE: file }, 91)).available, true);
  } finally { await rm(parent, { recursive: true, force: true }); }
});

test('backup preparation CLIs never print private material on success or malformed-secret failures', async () => {
  const parent = await mkdtemp(join(tmpdir(), 'roadconquest-backup-cli-'));
  try {
    const target = join(parent, 'key');
    const generated = await execute(process.execPath, [fileURLToPath(new URL('../tools/generate-backup-key.mjs', import.meta.url)), target]);
    assert.match(generated.stdout, /Public key ID: [A-Za-z0-9_-]{43}/);
    assert.doesNotMatch(generated.stdout + generated.stderr, /BEGIN PRIVATE/);
    const env = { ...process.env, ...base, BACKUP_SIGNING_PRIVATE_KEY: await readFile(join(target, 'backup-signing-private.pem'), 'utf8') };
    const tool = new URL('../tools/check-backup-config.mjs', import.meta.url);
    const checked = await execute(process.execPath, [fileURLToPath(tool), '91'], { env });
    assert.equal(JSON.parse(checked.stdout).available, true);
    await assert.rejects(execute(process.execPath, [fileURLToPath(tool), '91'], {
      env: { ...env, PLAY_SERVICE_ACCOUNT_JSON: '{secret-sentinel' }
    }), error => {
      assert.equal(error.code, 1);
      assert.doesNotMatch(error.stdout + error.stderr, /secret-sentinel|BEGIN PRIVATE/);
      return true;
    });
  } finally { await rm(parent, { recursive: true, force: true }); }
});
