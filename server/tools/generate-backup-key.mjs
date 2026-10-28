import { createHash, generateKeyPairSync } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';

/** Create a new private directory; never overwrite or print an existing key. */
export async function generateBackupKey(directory) {
  const target = resolve(directory);
  await mkdir(target, { mode: 0o700 });
  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const der = publicKey.export({ type: 'spki', format: 'der' });
  const entry = { id: createHash('sha256').update(der).digest('base64url'), spki: der.toString('base64url') };
  await writeFile(join(target, 'backup-signing-private.pem'), privateKey.export({ type: 'pkcs8', format: 'pem' }),
    { flag: 'wx', mode: 0o600, flush: true });
  await writeFile(join(target, 'backup-verification-key.json'), JSON.stringify(entry, null, 2) + '\n',
    { flag: 'wx', mode: 0o600, flush: true });
  return entry;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  if (process.argv.length !== 3) {
    console.error('Usage: node tools/generate-backup-key.mjs <new-private-directory>');
    process.exitCode = 1;
  } else {
    try {
      const entry = await generateBackupKey(process.argv[2]);
      console.log('Backup key created. Public key ID: ' + entry.id);
    } catch {
      console.error('Key generation failed. Use a new directory under a private, writable parent; inspect any partial output securely.');
      process.exitCode = 1;
    }
  }
}
