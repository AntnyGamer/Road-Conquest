import { serviceSwitches } from './config.mjs';
import { createHmacSigner } from '../worker/hmac.mjs';
const STYLES = new Set(['osm-liberty', 'positron', 'dark-matter']);
// Allocate once per Worker instance instead of rebuilding a collection for every upstream failure.
const PROVIDER_OUTAGE_STATUS = new Set([401, 403, 429, 500, 502, 503, 504]);
const DAY = 86_400_000;
const json = (body, status = 200, extra = {}) => Response.json(body, {
  status, headers: { 'Cache-Control': 'no-store', ...extra }
});
function waitForSlot(ms, signal) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(signal.reason);
    const abort = () => { clearTimeout(timer); reject(signal.reason); };
    const timer = setTimeout(() => { signal?.removeEventListener('abort', abort); resolve(); }, ms);
    signal?.addEventListener('abort', abort, { once: true });
  });
}
function retryDelay(value) {
  if (!value) return 0;
  const seconds = /^\d+$/.test(value) ? Number(value) * 1000 : Date.parse(value) - Date.now();
  return Number.isFinite(seconds) ? Math.max(0, Math.min(DAY, seconds)) : 0;
}
class BudgetDenied extends Error {
  constructor(response) { super('Map accounting unavailable'); this.response = response; }
}

export { MapBudget } from './budget.mjs';

function waypointSeconds(body) {
  if (body?.mode !== 'drive' || !Array.isArray(body.waypoints) || body.waypoints.length < 2 || body.waypoints.length > 200) return null;
  const seconds = [];
  for (const p of body.waypoints) {
    if (!Array.isArray(p?.location) || p.location.length !== 2 || !p.location.every(Number.isFinite) ||
        Math.abs(p.location[0]) > 180 || Math.abs(p.location[1]) > 90 || typeof p.timestamp !== 'string' ||
        (p.bearing !== undefined && (!Number.isFinite(p.bearing) || p.bearing < 0 || p.bearing >= 360))) return null;
    const time = Date.parse(p.timestamp);
    if (!Number.isFinite(time)) return null;
    seconds.push(Math.floor(time / 1000));
  }
  return seconds;
}

export function createMapsGateway(fetchUpstream = fetch, { wait = waitForSlot, upstreamTimeoutMs = 20_000 } = {}) {
  const sign = createHmacSigner(2); // Geoapify + LocationIQ; bounded across rotations.
  // This fixed provider scope contains no request, IP or GPS data. Reuse its
  // deterministic digest, while still signing each daily client bucket afresh.
  const healthKeys = new Map();
  const healthKeyFor = secret => {
    let pending = healthKeys.get(secret);
    if (!pending) {
      pending = sign(secret, 'health:matching')
        .then(bytes => Array.from(bytes, x => x.toString(16).padStart(2, '0')).join(''));
      healthKeys.set(secret, pending);
      if (healthKeys.size > 2) healthKeys.delete(healthKeys.keys().next().value);
      pending.catch(() => { if (healthKeys.get(secret) === pending) healthKeys.delete(secret); });
    }
    return pending;
  };
  return { async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === '/health' && ['GET', 'HEAD'].includes(request.method)) {
      const response = json({ ok: true, configured: Boolean(env.GEOAPIFY_API_KEY), locationiqConfigured: Boolean(env.LOCATIONIQ_API_KEY) });
      return request.method === 'HEAD' ? new Response(null, { headers: response.headers }) : response;
    }
    if (url.pathname === '/v1/config' && request.method === 'GET') return json({ version: 1,
      providers: await serviceSwitches(env) }, 200, { 'Cache-Control': 'public, max-age=300', 'X-Content-Type-Options': 'nosniff' });
    if (!env.MAP_BUDGET) return json({ error: 'Map service is not configured' }, 503);
    const locationiq = url.pathname === '/v1/locationiq/mapmatching';
    const secret = locationiq ? env.LOCATIONIQ_API_KEY : env.GEOAPIFY_API_KEY;
    if (!secret) return json({ error: 'Map service is not configured' }, 503);
    let target;
    let payload;
    if ((url.pathname === '/v1/mapmatching' || locationiq) && request.method === 'POST') {
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
      const seconds = waypointSeconds(payload);
      if (!seconds) return json({ error: 'Invalid driving trace' }, 400);
      if (locationiq && (payload.waypoints.length > 25 || payload.waypoints.some((p, index, all) =>
        !Number.isFinite(p.accuracyMeters) || p.accuracyMeters <= 0 || p.accuracyMeters > 1000 ||
        (index > 0 && seconds[index] <= seconds[index - 1])))) {
        return json({ error: 'Invalid fallback driving trace' }, 400);
      }
      // Forward only the documented contract, never caller-selected URLs or options.
      payload = { mode: 'drive', waypoints: payload.waypoints.map(p => ({ location: p.location, timestamp: p.timestamp,
        ...(locationiq ? { accuracyMeters: p.accuracyMeters } : p.bearing === undefined ? {} : { bearing: p.bearing }) })) };
      if (locationiq) {
        target = new URL('https://us1.locationiq.com/v1/matching/driving/' + payload.waypoints.map(p => p.location.join(',')).join(';'));
        for (const [name, value] of Object.entries({ steps: 'true', geometries: 'geojson', overview: 'false',
          timestamps: seconds.join(';'),
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
    const switches = await serviceSwitches(env);
    if (!switches[locationiq ? 'locationiq-matching' : payload ? 'geoapify-matching' : 'geoapify-tiles']) {
      return json({ error: 'Provider temporarily disabled' }, 503, { 'Retry-After': '300' });
    }
    // Geoapify charges one credit per 100 matched waypoints and a quarter
    // credit per raster tile. Represent both in integer quarter-credit units.
    const costUnits = locationiq ? 1 : payload ? 4 * Math.ceil(payload.waypoints.length / 100) : 1;
    const ip = request.headers.get('CF-Connecting-IP');
    if (!ip || request.headers.has('CF-Worker') || ip === '2a06:98c0:3600::103') return json({ error: 'Direct client required' }, 403);
    const bytes = await sign(secret, new Date().toISOString().slice(0, 10) + '\n' + ip);
    const bucket = Array.from(bytes, x => x.toString(16).padStart(2, '0')).join('');
    // Public tiles retain their existing lightweight accounting/cache path.
    const healthKey = payload ? await healthKeyFor(secret) : null;
    const attemptId = payload ? crypto.randomUUID() : null;
    let budget, managed = Boolean(payload), dispatched = false, probing = false;
    const report = async (action, extra = {}) => {
      if (!managed) return;
      const response = await budget.fetch('https://budget/' + action, { method: 'POST',
        body: JSON.stringify({ attemptId, healthKey, ...extra }) });
      if (!response.ok) throw new BudgetDenied(response);
      await response.body?.cancel();
    };
    const healthy = async () => { if (probing) await report('success').catch(() => {}); };
    try {
      budget = env.MAP_BUDGET.get(env.MAP_BUDGET.idFromName(locationiq ? 'locationiq-free-allowance' : 'geoapify-free-allowance'));
      const limit = locationiq
        ? Math.max(1, Math.min(4000, Math.floor(Number(env.LOCATIONIQ_DAILY_REQUEST_CAP) || 4000)))
        : Math.max(1, Math.min(2400, Math.floor(Number(env.DAILY_CREDIT_CAP) || 2400)));
      const permit = await budget.fetch('https://budget/reserve', { method: 'POST', body: JSON.stringify({ bucket,
        limit: locationiq ? limit : limit * 4, ipLimit: locationiq ? Math.min(1000, limit) : Math.min(600, limit) * 4,
        costUnits, spacingMs: locationiq ? 1050 : 250, ...(payload ? { attemptId, healthKey } : {}) }) });
      if (!permit.ok) return permit;
      const reservation = await permit.json();
      managed = reservation.managed === true;
      probing = reservation.probe === true;
      if (reservation.waitMs > 0) await wait(Math.min(reservation.waitMs, 10_000), request.signal);
      request.signal.throwIfAborted();
      await report('dispatch');
      // A lost dispatch acknowledgement is ambiguous: never refund or dispatch twice.
      dispatched = true;
      target.searchParams.set(locationiq ? 'key' : 'apiKey', secret);
      const post = payload && !locationiq;
      const response = await fetchUpstream(target, { method: post ? 'POST' : 'GET', redirect: 'manual',
        headers: { Accept: payload ? 'application/json' : 'image/png', ...(post ? { 'Content-Type': 'application/json' } : {}) },
        ...(post ? { body: JSON.stringify(payload) } : {}),
        signal: AbortSignal.any([request.signal, AbortSignal.timeout(upstreamTimeoutMs)]) });
      if (!response.ok) {
        const upstreamOutage = PROVIDER_OUTAGE_STATUS.has(response.status);
        if (upstreamOutage)
          await report('failure', { status: response.status, retryAfterMs: retryDelay(response.headers.get('Retry-After')) }).catch(() => {});
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
            if (length <= 4096 && ['NoMatch', 'NoSegment'].includes(code)) {
              await healthy();
              return json({ code });
            }
          } catch { /* An unknown 404 is an upstream failure. */ }
        } else await response.body?.cancel();
        if (!upstreamOutage) await healthy();
        const retry = response.headers.get('Retry-After');
        // Provider errors never echo URLs, keys, traces or upstream error bodies.
        return json({ error: 'Map provider unavailable' }, response.status === 429 ? 429 : response.status === 401 || response.status === 403 ? 403 : 502,
          { 'Retry-After': retry || (response.status === 429 ? '3600' : '60'),
            'X-RoadConquest-Error': 'provider_unavailable' });
      }
      const type = response.headers.get('Content-Type') || '';
      if (!type.includes(payload ? 'application/json' : 'image/png')) {
        await response.body?.cancel();
        await report('failure', { status: 502 }).catch(() => {});
        return json({ error: 'Invalid provider response' }, 502);
      }
      await healthy();
      return new Response(response.body, { headers: { 'Content-Type': type,
        'Cache-Control': payload ? 'no-store' : 'public, max-age=86400',
        'X-Content-Type-Options': 'nosniff' } });
    } catch (error) {
      if (!dispatched || request.signal.aborted) await report('cancel').catch(() => {});
      else if (!request.signal.aborted) await report('failure', { status: 503 }).catch(() => {});
      if (error instanceof BudgetDenied) return error.response;
      return json({ error: 'Map service temporarily unavailable' }, 503,
        { 'Retry-After': '60', 'X-RoadConquest-Error': request.signal.aborted ? 'request_cancelled' : 'provider_unavailable' });
    }
  } };
}
export default createMapsGateway();
