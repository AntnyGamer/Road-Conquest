const STYLES = new Set(['osm-liberty', 'positron', 'dark-matter']);
const DAY = 86_400_000;
const json = (body, status = 200, extra = {}) => Response.json(body, {
  status, headers: { 'Cache-Control': 'no-store', ...extra }
});

// One SQLite-backed object serializes all clients' reservations. A process-local
// counter or KV would let concurrent requests exceed the free allowance.
export class MapBudget {
  constructor(ctx) {
    this.sql = ctx.storage.sql;
    this.sql.exec('CREATE TABLE IF NOT EXISTS usage (day TEXT, bucket TEXT, used INTEGER NOT NULL, PRIMARY KEY(day,bucket))');
  }
  async fetch(request) {
    const { bucket, limit, ipLimit } = await request.json();
    if (!/^[a-f0-9]{64}$/.test(bucket) || !Number.isInteger(limit) || limit < 1 || limit > 2400 ||
        !Number.isInteger(ipLimit) || ipLimit < 1 || ipLimit > limit) return json({ error: 'Invalid budget' }, 400);
    const day = new Date().toISOString().slice(0, 10);
    // No await between reads and writes: reservations cannot interleave.
    this.sql.exec('DELETE FROM usage WHERE day < ?', day);
    const used = key => [...this.sql.exec('SELECT used FROM usage WHERE day = ? AND bucket = ?', day, key)][0]?.used || 0;
    if (used('all') >= limit || used(bucket) >= ipLimit) {
      return json({ error: 'Daily map allowance exhausted' }, 429,
        { 'Retry-After': String(Math.ceil((DAY - Date.now() % DAY) / 1000)) });
    }
    // Space outgoing calls at four/second instead of treating a normal tile
    // burst as exhausted daily quota. Waiting happens in the Worker, not here.
    const slot = Math.max(Date.now(), used('next-slot'));
    if (slot - Date.now() > 10_000) return json({ error: 'Map service busy' }, 503, { 'Retry-After': '10' });
    for (const key of ['all', bucket]) this.sql.exec(
      'INSERT INTO usage(day,bucket,used) VALUES(?,?,1) ON CONFLICT(day,bucket) DO UPDATE SET used = used + 1', day, key);
    this.sql.exec('INSERT INTO usage(day,bucket,used) VALUES(?,?,?) ON CONFLICT(day,bucket) DO UPDATE SET used = excluded.used', day, 'next-slot', slot + 250);
    return json({ allowed: true, waitMs: Math.max(0, slot - Date.now()) });
  }
}

function validWaypoints(body) {
  return body?.mode === 'drive' && Array.isArray(body.waypoints) && body.waypoints.length >= 2 &&
    body.waypoints.length <= 10 && body.waypoints.every(p =>
      Array.isArray(p.location) && p.location.length === 2 && p.location.every(Number.isFinite) &&
      Math.abs(p.location[0]) <= 180 && Math.abs(p.location[1]) <= 90 &&
      typeof p.timestamp === 'string' && Number.isFinite(Date.parse(p.timestamp)) &&
      (p.bearing === undefined || Number.isFinite(p.bearing) && p.bearing >= 0 && p.bearing < 360));
}

export function createMapsGateway(fetchUpstream = fetch) {
  return { async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === '/health' && request.method === 'GET') return json({ ok: true, configured: Boolean(env.GEOAPIFY_API_KEY) });
    if (!env.GEOAPIFY_API_KEY || !env.MAP_BUDGET) return json({ error: 'Map service is not configured' }, 503);
    let target;
    let payload;
    if (url.pathname === '/v1/mapmatching' && request.method === 'POST') {
      if (Number(request.headers.get('Content-Length')) > 16_384) return json({ error: 'Request too large' }, 413);
      const reader = request.body?.getReader();
      if (!reader) return json({ error: 'Missing trace' }, 400);
      let raw = '';
      let length = 0;
      const decoder = new TextDecoder();
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        length += value.byteLength;
        if (length > 16_384) { await reader.cancel(); return json({ error: 'Request too large' }, 413); }
        raw += decoder.decode(value, { stream: true });
      }
      raw += decoder.decode();
      try { payload = JSON.parse(raw); } catch { return json({ error: 'Invalid JSON' }, 400); }
      if (!validWaypoints(payload)) return json({ error: 'Invalid driving trace' }, 400);
      // Forward only the documented contract, never caller-selected URLs or options.
      payload = { mode: 'drive', waypoints: payload.waypoints.map(p => ({ location: p.location, timestamp: p.timestamp,
        ...(p.bearing === undefined ? {} : { bearing: p.bearing }) })) };
      target = new URL('https://api.geoapify.com/v1/mapmatching');
    } else {
      const tile = url.pathname.match(/^\/v1\/tile\/([a-z-]+)\/(\d{1,2})\/(\d{1,7})\/(\d{1,7})\.png$/);
      if (request.method !== 'GET' || !tile || !STYLES.has(tile[1])) return json({ error: 'Not found' }, 404);
      const [, style, z, x, y] = tile;
      if (+z > 19 || +x >= 2 ** +z || +y >= 2 ** +z) return json({ error: 'Invalid tile' }, 400);
      target = new URL(`https://maps.geoapify.com/v1/tile/${style}/${z}/${x}/${y}.png`);
    }
    const ip = request.headers.get('CF-Connecting-IP');
    if (!ip || request.headers.has('CF-Worker') || ip === '2a06:98c0:3600::103') return json({ error: 'Direct client required' }, 403);
    const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(env.GEOAPIFY_API_KEY),
      { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
    const bytes = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(new Date().toISOString().slice(0, 10) + '\n' + ip));
    const bucket = [...new Uint8Array(bytes)].map(x => x.toString(16).padStart(2, '0')).join('');
    try {
      const budget = env.MAP_BUDGET.get(env.MAP_BUDGET.idFromName('geoapify-free-allowance'));
      const limit = Math.max(1, Math.min(2400, Math.floor(Number(env.DAILY_CREDIT_CAP) || 2400)));
      const permit = await budget.fetch('https://budget/reserve', { method: 'POST', body: JSON.stringify({ bucket,
        limit, ipLimit: Math.min(600, limit) }) });
      if (!permit.ok) return permit;
      const { waitMs = 0 } = await permit.json();
      if (waitMs > 0) await new Promise(resolve => setTimeout(resolve, Math.min(waitMs, 10_000)));
      target.searchParams.set('apiKey', env.GEOAPIFY_API_KEY);
      const response = await fetchUpstream(target, { method: payload ? 'POST' : 'GET', redirect: 'manual',
        headers: { Accept: payload ? 'application/json' : 'image/png', ...(payload ? { 'Content-Type': 'application/json' } : {}) },
        ...(payload ? { body: JSON.stringify(payload) } : {}), signal: AbortSignal.timeout(20_000) });
      if (!response.ok) {
        await response.body?.cancel();
        const retry = response.headers.get('Retry-After');
        // Provider errors never echo URLs, keys, traces or upstream error bodies.
        return json({ error: 'Map provider unavailable' }, response.status === 429 ? 429 : response.status === 401 || response.status === 403 ? 403 : 502,
          { 'Retry-After': retry || (response.status === 429 ? '3600' : '60') });
      }
      const type = response.headers.get('Content-Type') || '';
      if (!type.includes(payload ? 'application/json' : 'image/png')) {
        await response.body?.cancel(); return json({ error: 'Invalid provider response' }, 502);
      }
      return new Response(response.body, { headers: { 'Content-Type': type,
        'Cache-Control': payload ? 'no-store' : 'public, max-age=86400',
        'X-Content-Type-Options': 'nosniff' } });
    } catch { return json({ error: 'Map service temporarily unavailable' }, 503, { 'Retry-After': '60' }); }
  } };
}
export default createMapsGateway();
