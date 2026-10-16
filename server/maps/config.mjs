// Remote values can disable known services, never change URLs, quotas or scoring.
export const DEFAULT_SWITCHES = Object.freeze({
  openfreemap: true, 'esri-imagery': true, 'geoapify-tiles': true,
  'geoapify-matching': true, 'locationiq-matching': true
});
export function parseSwitches(value) {
  if (typeof value === 'string') {
    if (value.length > 4096) throw new Error('Oversized configuration');
    value = JSON.parse(value);
  }
  if (value?.version !== 1 || !value.providers || Array.isArray(value.providers)) throw new Error('Invalid configuration');
  return Object.fromEntries(Object.entries(DEFAULT_SWITCHES).map(([key, fallback]) =>
    [key, typeof value.providers[key] === 'boolean' ? value.providers[key] : fallback]));
}
const states = new WeakMap();
export async function serviceSwitches(env, now = Date.now()) {
  if (!env.SERVICE_CONFIG) return DEFAULT_SWITCHES;
  let state = states.get(env.SERVICE_CONFIG);
  if (!state) { state = { value: DEFAULT_SWITCHES, expires: 0 }; states.set(env.SERVICE_CONFIG, state); }
  if (state.expires > now) return state.value;
  if (!state.pending) state.pending = (async () => {
    try {
      const raw = await env.SERVICE_CONFIG.get('providers-v1', { cacheTtl: 300 });
      if (raw !== null) state.value = parseSwitches(raw);
    } catch { /* Preserve last known values; hard Durable Object caps still apply. */ }
    finally { state.expires = now + 60_000; state.pending = null; }
    return state.value;
  })();
  return state.pending;
}
