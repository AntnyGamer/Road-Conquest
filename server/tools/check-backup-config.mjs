import { createPrivateKey } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createBackups } from '../src/backups.mjs';
import { playIntegrityConfig } from '../src/integrity.mjs';
import { MIN_PLAY_VERSION_CODE, supportsPlayVersion } from '../src/scoring.mjs';

/** Offline only: no Google calls, database access, deployment or secret output. */
export async function checkBackupConfig(env, versionCode) {
  if (!supportsPlayVersion(String(versionCode))) {
    throw new Error('Supply the intended Android versionCode (' + MIN_PLAY_VERSION_CODE + ' or newer).');
  }
  for (const [key, value] of Object.entries({ NODE_ENV: 'production', REQUIRE_HTTPS: '1', TRUST_PROXY: '1', APPLY_SCHEMA_ON_STARTUP: '0' })) {
    if (env[key] !== value) throw new Error(key + '=' + value + ' is required for the existing production function.');
  }
  if (!env.BACKUP_SIGNING_PRIVATE_KEY) throw new Error('BACKUP_SIGNING_PRIVATE_KEY is required.');
  let config;
  try { config = playIntegrityConfig(env); }
  catch { throw new Error('Check the production PLAY_SERVICE_ACCOUNT_JSON/FILE, PLAY_CERTIFICATES and PLAY_CLOUD_PROJECT_NUMBER.'); }
  try {
    const account = JSON.parse(config.credentialsJson || await readFile(config.credentialsFile, 'utf8'));
    if (account?.type !== 'service_account' || typeof account.client_email !== 'string' || !account.client_email.trim() ||
        typeof account.private_key !== 'string') throw new Error();
    const key = createPrivateKey(account.private_key);
    if (key.asymmetricKeyType !== 'rsa' || key.asymmetricKeyDetails.modulusLength < 2048) throw new Error();
  } catch { throw new Error('The Play Integrity service-account JSON and its RSA private key must be valid.'); }
  let status;
  try { status = (await createBackups(env)).status(); }
  catch { throw new Error('Check the P-256 BACKUP_SIGNING_PRIVATE_KEY and public BACKUP_VERIFICATION_KEYS_JSON.'); }
  return { available: status.available, version_code: Number(versionCode), cloud_project: status.cloud_project,
    verification_key_ids: status.keys.map(key => key.id) };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    if (process.argv.length !== 3) throw new Error('Usage: node --env-file=<private-env-file> tools/check-backup-config.mjs <versionCode>');
    console.log(JSON.stringify(await checkBackupConfig(process.env, process.argv[2]), null, 2));
  } catch (error) {
    // Every error above is deliberately fixed text; never print parser/OpenSSL errors or env values.
    console.error(error.message);
    process.exitCode = 1;
  }
}
