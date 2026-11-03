import { execFileSync } from 'node:child_process';
import { realpathSync } from 'node:fs';

/** Identify this source checkout, never an unrelated parent of an exported archive. */
export function sourceRevision() {
  let revision = process.env.ROADCONQUEST_SERVER_REVISION || null;
  try {
    const [checkout, head] = execFileSync('git', ['rev-parse', '--show-toplevel', 'HEAD'],
      { cwd: new URL('../', import.meta.url), encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
      .trim().split(/\r?\n/);
    if (realpathSync(checkout) === realpathSync(new URL('../../', import.meta.url))) revision = head;
  } catch { /* Source archives may supply an explicit public revision. */ }
  if (revision !== null && !/^[a-f0-9]{40}$/i.test(revision)) throw new Error('Invalid server source revision');
  return revision?.toLowerCase() ?? null;
}
