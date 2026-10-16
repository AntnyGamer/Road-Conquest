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
    const { bucket, limit, ipLimit, costUnits = 1, spacingMs = 250 } = await request.json();
    if (!/^[a-f0-9]{64}$/.test(bucket) || !Number.isInteger(limit) || limit < 1 || limit > 9600 ||
        !Number.isInteger(ipLimit) || ipLimit < 1 || ipLimit > limit ||
        !Number.isInteger(costUnits) || costUnits < 1 || costUnits > 40 ||
        ![250, 1050].includes(spacingMs)) return json({ error: 'Invalid budget' }, 400);
    const day = new Date().toISOString().slice(0, 10);
    // No await between reads and writes: reservations cannot interleave.
    this.sql.exec('DELETE FROM usage WHERE day < ?', day);
    const used = key => [...this.sql.exec('SELECT used FROM usage WHERE day = ? AND bucket = ?', day, key)][0]?.used || 0;
    // Pre-upgrade counters are in whole credits. Count those reservations conservatively
    // alongside quarter-credit units, so a mid-day deployment cannot reset the budget.
    if (used('all') * 4 + used('units:all') + costUnits > limit ||
        used(bucket) * 4 + used('units:' + bucket) + costUnits > ipLimit) {
      return json({ error: 'Daily map allowance exhausted' }, 429,
        { 'Retry-After': String(Math.ceil((DAY - Date.now() % DAY) / 1000)) });
    }
    // Geoapify uses four/second; LocationIQ gets one slot per 1.05 seconds,
    // below both its two/second and 60/minute free limits. Separate objects
    // keep each provider's reservations and pacing independent.
    const slot = Math.max(Date.now(), used('next-slot'));
    if (slot - Date.now() > 10_000) return json({ error: 'Map service busy' }, 503, { 'Retry-After': '10' });
    for (const key of ['units:all', 'units:' + bucket]) this.sql.exec(
      'INSERT INTO usage(day,bucket,used) VALUES(?,?,?) ON CONFLICT(day,bucket) DO UPDATE SET used = used + excluded.used',
      day, key, costUnits);
    this.sql.exec('INSERT INTO usage(day,bucket,used) VALUES(?,?,?) ON CONFLICT(day,bucket) DO UPDATE SET used = excluded.used', day, 'next-slot', slot + spacingMs);
    return json({ allowed: true, waitMs: Math.max(0, slot - Date.now()) });
  }
}

function validWaypoints(body) {
  return body?.mode === 'drive' && Array.isArray(body.waypoints) && body.waypoints.length >= 2 &&
    body.waypoints.length <= 200 && body.waypoints.every(p =>
      Array.isArray(p.location) && p.location.length === 2 && p.location.every(Number.isFinite) &&
      Math.abs(p.location[0]) <= 180 && Math.abs(p.location[1]) <= 90 &&
      typeof p.timestamp === 'string' && Number.isFinite(Date.parse(p.timestamp)) &&
      (p.bearing === undefined || Number.isFinite(p.bearing) && p.bearing >= 0 && p.bearing < 360));
}

export function createMapsGateway(fetchUpstream = fetch) {
  return { async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === '/health' && request.method === 'GET') return json({ ok: true,
      configured: Boolean(env.GEOAPIFY_API_KEY), locationiqConfigured: Boolean(env.LOCATIONIQ_API_KEY) });
    if (!env.MAP_BUDGET) return json({ error: 'Map service is not configured' }, 503);
    const locationiq = url.pathname === '/v1/locationiq/mapmatching';
    const secret = locationiq ? env.LOCATIONIQ_API_KEY : env.GEOAPIFY_API_KEY;
    if (!secret) return json({ error: 'Map service is not configured' }, 503);
    let target;
    let payload;
    if (['/v1/mapmatching', '/v1/locationiq/mapmatching'].includes(url.pathname) && request.method === 'POST') {
      if (Number(request.headers.get('Content-Length')) > 32_768) return json({ error: 'Request too large' }, 413);
      const reader = request.body?.getReader();
      if (!reader) return json({ error: 'Missing trace' }, 400);
      let raw = '';
      let length = 0;
      const decoder = new TextDecoder();
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        length += value.byteLength;
        if (length > 32_768) { await reader.cancel(); return json({ error: 'Request too large' }, 413); }
        raw += decoder.decode(value, { stream: true });
      }
      raw += decoder.decode();
      try { payload = JSON.parse(raw); } catch { return json({ error: 'Invalid JSON' }, 400); }
      if (!validWaypoints(payload)) return json({ error: 'Invalid driving trace' }, 400);
      if (locationiq && (payload.waypoints.length > 25 || payload.waypoints.some((p, index, all) =>
        !Number.isFinite(p.accuracyMeters) || p.accuracyMeters <= 0 || p.accuracyMeters > 1000 ||
        (index > 0 && Math.floor(Date.parse(p.timestamp) / 1000) <= Math.floor(Date.parse(all[index - 1].timestamp) / 1000))))) {
        return json({ error: 'Invalid fallback driving trace' }, 400);
      }
      // Forward only the documented contract, never caller-selected URLs or options.
      payload = { mode: 'drive', waypoints: payload.waypoints.map(p => ({ location: p.location, timestamp: p.timestamp,
        ...(locationiq ? { accuracyMeters: p.accuracyMeters } : p.bearing === undefined ? {} : { bearing: p.bearing }) })) };
      if (locationiq) {
        target = new URL('https://us1.locationiq.com/v1/matching/driving/' + payload.waypoints.map(p => p.location.join(',')).join(';'));
        for (const [name, value] of Object.entries({ steps: 'true', geometries: 'geojson', overview: 'false',
          timestamps: payload.waypoints.map(p => Math.floor(Date.parse(p.timestamp) / 1000)).join(';'),
          radiuses: payload.waypoints.map(p => Math.max(3, Math.min(35, p.accuracyMeters))).join(';'),
          gaps: 'split', tidy: 'false', generate_hints: 'false' })) target.searchParams.set(name, value);
      } else target = new URL('https://api.geoapify.com/v1/mapmatching');
    } else {
      const tile = url.pathname.match(/^\/v1\/tile\/([a-z-]+)\/(\d{1,2})\/(\d{1,7})\/(\d{1,7})\.png$/);
      if (request.method !== 'GET' || !tile || !STYLES.has(tile[1])) return json({ error: 'Not found' }, 404);
      const [, style, z, x, y] = tile;
      if (+z > 19 || +x >= 2 ** +z || +y >= 2 ** +z) return json({ error: 'Invalid tile' }, 400);
      target = new URL(`https://maps.geoapify.com/v1/tile/${style}/${z}/${x}/${y}.png`);
    }
    // Geoapify charges one credit per 100 matched waypoints and a quarter
    // credit per raster tile. Represent both in integer quarter-credit units.
    const costUnits = locationiq ? 1 : payload ? 4 * Math.ceil(payload.waypoints.length / 100) : 1;
    const ip = request.headers.get('CF-Connecting-IP');
    if (!ip || request.headers.has('CF-Worker') || ip === '2a06:98c0:3600::103') return json({ error: 'Direct client required' }, 403);
    const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(secret),
      { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
    const bytes = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(new Date().toISOString().slice(0, 10) + '\n' + ip));
    const bucket = [...new Uint8Array(bytes)].map(x => x.toString(16).padStart(2, '0')).join('');
    try {
      const budget = env.MAP_BUDGET.get(env.MAP_BUDGET.idFromName(locationiq ? 'locationiq-free-allowance' : 'geoapify-free-allowance'));
      const limit = locationiq
        ? Math.max(1, Math.min(4000, Math.floor(Number(env.LOCATIONIQ_DAILY_REQUEST_CAP) || 4000)))
        : Math.max(1, Math.min(2400, Math.floor(Number(env.DAILY_CREDIT_CAP) || 2400)));
      const permit = await budget.fetch('https://budget/reserve', { method: 'POST', body: JSON.stringify({ bucket,
        limit: locationiq ? limit : limit * 4, ipLimit: locationiq ? Math.min(1000, limit) : Math.min(600, limit) * 4,
        costUnits, spacingMs: locationiq ? 1050 : 250 }) });
      if (!permit.ok) return permit;
      const { waitMs = 0 } = await permit.json();
      if (waitMs > 0) await new Promise(resolve => setTimeout(resolve, Math.min(waitMs, 10_000)));
      target.searchParams.set(locationiq ? 'key' : 'apiKey', secret);
      const post = payload && !locationiq;
      const response = await fetchUpstream(target, { method: post ? 'POST' : 'GET', redirect: 'manual',
        headers: { Accept: payload ? 'application/json' : 'image/png', ...(post ? { 'Content-Type': 'application/json' } : {}) },
        ...(post ? { body: JSON.stringify(payload) } : {}), signal: AbortSignal.timeout(20_000) });
      if (!response.ok) {
        // LocationIQ documents valid NoMatch/NoSegment as HTTP 404. Do not
        // turn that into a provider outage or expose the upstream error text.
        if (locationiq && response.status === 404) {
          const reader = response.body?.getReader();
          let raw = ''; let length = 0;
          const decoder = new TextDecoder();
          while (reader) {
            const { done, value } = await reader.read();
            if (done) break;
            length += value.byteLength;
            if (length > 4096) { await reader.cancel(); break; }
            raw += decoder.decode(value, { stream: true });
          }
          try {
            const code = JSON.parse(raw + decoder.decode()).code;
            if (length <= 4096 && ['NoMatch', 'NoSegment'].includes(code)) return json({ code });
          } catch { /* An unknown 404 is an upstream failure. */ }
        } else await response.body?.cancel();
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
