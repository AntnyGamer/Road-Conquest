import { pathToFileURL } from 'node:url';
import { boundedJson } from '../src/scoring.mjs';

/** Read-only release check. This never authenticates, writes data, or deploys code. */
export async function verifyDeployment({ accountRevision, ingressRevision,
  origin = 'https://roadconquest-api.22abongiovanni.workers.dev' }, fetcher = fetch) {
  const base = new URL(origin);
  if (base.protocol !== 'https:' || base.username || base.password || base.search || base.hash || base.pathname !== '/')
    throw new Error('Use an HTTPS account origin without credentials, path, query or fragment.');
  const checks = [
    { name: 'account function', path: '/health/live', expected: accountRevision },
    { name: 'account ingress', path: '/health/ingress', expected: ingressRevision }
  ];
  for (const check of checks) {
    if (typeof check.expected !== 'string' || !/^[a-f0-9]{40}$/i.test(check.expected))
      throw new Error('Supply the full intended function and ingress commit hashes.');
  }
  return Promise.all(checks.map(async check => {
    const response = await fetcher(new URL(check.path, base), {
      redirect: 'error', headers: { Accept: 'application/json' }, signal: AbortSignal.timeout(10_000)
    });
    if (!response.ok) throw new Error(`${check.name} health check returned HTTP ${response.status}.`);
    const body = await boundedJson(response, 4096);
    if (body.ok !== true || body.revision !== check.expected.toLowerCase())
      throw new Error(`${check.name} revision mismatch: expected ${check.expected.toLowerCase()}, got ${body.revision ?? 'unknown'}.`);
    return { service: check.name, revision: body.revision };
  }));
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [accountRevision, ingressRevision, origin] = process.argv.slice(2);
  console.log(JSON.stringify(await verifyDeployment({ accountRevision, ingressRevision, origin }), null, 2));
}
