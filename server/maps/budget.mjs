const DAY = 86_400_000;
const USAGE_TABLE = 'CREATE TABLE IF NOT EXISTS usage (day TEXT, bucket TEXT, used INTEGER NOT NULL, PRIMARY KEY(day,bucket)) WITHOUT ROWID';
const json = (body, status = 200, extra = {}) => Response.json(body, {
  status, headers: { 'Cache-Control': 'no-store', ...extra }
});
const denied = (code, message, status, seconds) => json({ error: message, code }, status,
  { 'Retry-After': String(seconds), 'X-RoadConquest-Error': code });
const validId = id => typeof id === 'string' && /^[a-f0-9-]{36}$/.test(id);
const validKey = key => typeof key === 'string' && /^[a-f0-9]{64}$/.test(key);
function remember(cache, key, value, limit) {
  cache.delete(key);
  cache.set(key, value);
  if (cache.size > limit) cache.delete(cache.keys().next().value);
  return value;
}

/** No traces or responses are stored: only daily counters, attempt IDs and provider cooldowns. */
export class MapBudget {
  constructor(ctx, now = () => Date.now()) {
    this.sql = ctx.storage.sql;
    this.now = now;
    this.atomic = ctx.storage.transactionSync?.bind(ctx.storage) || (fn => fn());
    this.lastPrunedDay = null;
    this.healthCache = new Map();
    // The object is the sole writer of its SQLite storage. Cache only metadata
    // read/written in synchronous transactions; eviction/restart falls back to SQL.
    this.counterCache = new Map();
    this.permitCache = new Map();
    this.sql.exec(USAGE_TABLE);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS map_permits (
      id TEXT PRIMARY KEY, day TEXT NOT NULL, bucket TEXT NOT NULL, cost INTEGER NOT NULL,
      slot INTEGER NOT NULL, state TEXT NOT NULL) WITHOUT ROWID`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS map_health (
      key TEXT PRIMARY KEY, until_ms INTEGER NOT NULL, probe_id TEXT, probe_until INTEGER NOT NULL) WITHOUT ROWID`);
  }
  async fetch(request) {
    let args;
    try { args = await request.json(); } catch { return json({ error: 'Invalid budget' }, 400); }
    const action = new URL(request.url).pathname;
    try { return this.atomic(() => action === '/reserve' ? this.reserve(args) : this.finish(action, args)); }
    catch (error) {
      this.lastPrunedDay = null;
      this.healthCache.clear();
      this.counterCache.clear();
      this.permitCache.clear();
      throw error;
    }
  }
  reserve({ bucket, limit, ipLimit, costUnits = 1, spacingMs = 250, attemptId, healthKey }) {
    if (!validKey(bucket) || !Number.isInteger(limit) || limit < 1 || limit > 9600 ||
        !Number.isInteger(ipLimit) || ipLimit < 1 || ipLimit > limit ||
        !Number.isInteger(costUnits) || costUnits < 1 || costUnits > 40 ||
        ![250, 1050].includes(spacingMs) || (attemptId !== undefined && !validId(attemptId)) ||
        (healthKey !== undefined && !validKey(healthKey))) return json({ error: 'Invalid budget' }, 400);
    const now = this.now();
    const day = new Date(now).toISOString().slice(0, 10);
    if (this.lastPrunedDay !== day) {
      // A memory-only marker repeats full-table scans on each object restart.
      // Persist one zero-cost marker per UTC day in the existing counter table.
      const pruned = [...this.sql.exec("SELECT used FROM usage WHERE day = ? AND bucket = 'maintenance:pruned'", day)].length;
      if (!pruned) {
        this.sql.exec('DELETE FROM usage WHERE day < ?', day);
        this.sql.exec('DELETE FROM map_permits WHERE day < ?', day);
        this.sql.exec('DELETE FROM map_health WHERE until_ms < ? AND probe_until < ?', now - DAY, now - DAY);
        // Upgrade the original rowid layout only after normal retention leaves
        // it empty. Current-day/legacy credits are never copied, reset or lost.
        // This runs in the reservation transaction, so a failed commit restores
        // both the old table and every expired row removed above.
        if (![...this.sql.exec('SELECT 1 FROM usage LIMIT 1')].length &&
            ![...this.sql.exec('PRAGMA index_info(usage)')].length) {
          this.sql.exec('DROP TABLE usage');
          this.sql.exec(USAGE_TABLE);
        }
        this.sql.exec("INSERT INTO usage(day,bucket,used) VALUES(?,'maintenance:pruned',0)", day);
      }
      this.healthCache.clear();
      this.counterCache.clear();
      this.permitCache.clear();
      this.lastPrunedDay = day;
    }
    if (attemptId) {
      const prior = this.permit(attemptId);
      if (prior) {
        if (prior.day !== day || prior.bucket !== bucket || prior.cost !== costUnits || prior.state === 'cancelled')
          return denied('budget_conflict', 'Map attempt is no longer available', 409, 1);
        const probe = healthKey && this.health(healthKey)?.probe_id === attemptId;
        return json({ allowed: true, managed: true, probe: Boolean(probe), waitMs: Math.max(0, prior.slot - now) });
      }
    }
    let health;
    if (healthKey && attemptId) {
      health = this.health(healthKey);
      const until = Math.max(health?.until_ms || 0, health?.probe_until || 0);
      if (until > now) return denied('provider_cooldown', 'Map provider is recovering', 503, Math.ceil((until - now) / 1000));
    }
    const keys = ['all', 'units:all', bucket, 'units:' + bucket, 'next-slot'];
    // Tiles never consume matching-attempt counters. Loading their unused zero
    // balances evicts useful client credit metadata from the same bounded cache.
    if (attemptId) keys.push('attempts:all', 'attempts:' + bucket);
    const counters = new Map();
    const missing = [];
    for (const key of keys) {
      if (this.counterCache.has(key)) counters.set(key,
        remember(this.counterCache, key, this.counterCache.get(key), 512));
      else missing.push(key);
    }
    const used = key => counters.get(key) || 0;
    // Known global balances can prove rejection without loading a new/evicted
    // client's counters. Refunds invalidate units:all, forcing a fresh read.
    const globalExhausted = counters.has('all') && counters.has('units:all') &&
      used('all') * 4 + used('units:all') + costUnits > limit;
    if (missing.length && !globalExhausted) {
      const stored = new Map([...this.sql.exec(
        'SELECT bucket,used FROM usage WHERE day = ? AND bucket IN (' + missing.map(() => '?').join(',') + ')',
        day, ...missing
      )].map(row => [row.bucket, row.used]));
      for (const key of missing) counters.set(key, remember(this.counterCache, key, stored.get(key) || 0, 512));
    }
    if (used('all') * 4 + used('units:all') + costUnits > limit)
      return denied('budget_global', 'Daily map allowance exhausted', 429, Math.ceil((DAY - now % DAY) / 1000));
    if (used(bucket) * 4 + used('units:' + bucket) + costUnits > ipLimit)
      return denied('budget_client', 'Daily client map allowance exhausted', 429, Math.ceil((DAY - now % DAY) / 1000));
    // Credit refunds must not create unlimited work. Matching attempts retain a
    // separate non-refundable global/client ceiling, including cancelled work.
    const unitsPerAttempt = spacingMs === 1050 ? 1 : 4;
    if (attemptId && used('attempts:all') >= Math.floor(limit / unitsPerAttempt))
      return denied('budget_attempts', 'Daily map attempt allowance exhausted', 429, Math.ceil((DAY - now % DAY) / 1000));
    if (attemptId && used('attempts:' + bucket) >= Math.floor(ipLimit / unitsPerAttempt))
      return denied('budget_client', 'Daily client map attempt allowance exhausted', 429, Math.ceil((DAY - now % DAY) / 1000));
    const slot = Math.max(now, used('next-slot'));
    if (slot - now > 10_000) return denied('queue_busy', 'Map service busy', 503, 10);
    const values = [day, 'units:all', costUnits, day, 'units:' + bucket, costUnits,
      day, 'next-slot', slot + spacingMs];
    if (attemptId) values.push(day, 'attempts:all', 1, day, 'attempts:' + bucket, 1);
    this.sql.exec(
      'INSERT INTO usage(day,bucket,used) VALUES' + Array(values.length / 3).fill('(?,?,?)').join(',') + ' ' +
        "ON CONFLICT(day,bucket) DO UPDATE SET used = CASE WHEN excluded.bucket = 'next-slot' " +
        'THEN excluded.used ELSE used + excluded.used END',
      ...values);
    for (let i = 0; i < values.length; i += 3) {
      const key = values[i + 1], added = values[i + 2];
      remember(this.counterCache, key, key === 'next-slot' ? added : used(key) + added, 512);
    }
    if (attemptId) {
      this.sql.exec("INSERT INTO map_permits(id,day,bucket,cost,slot,state) VALUES(?,?,?,?,?,'reserved')",
        attemptId, day, bucket, costUnits, slot);
      remember(this.permitCache, attemptId, { id: attemptId, day, bucket, cost: costUnits, slot, state: 'reserved' }, 64);
      if (health) {
        this.sql.exec('UPDATE map_health SET probe_id = ?, probe_until = ? WHERE key = ?', attemptId, now + 35_000, healthKey);
        this.healthCache.delete(healthKey);
      }
    }
    return json({ allowed: true, ...(attemptId ? { managed: true, probe: Boolean(health) } : {}), waitMs: Math.max(0, slot - now) });
  }
  health(key) {
    if (!this.healthCache.has(key)) {
      if (this.healthCache.size >= 8) this.healthCache.delete(this.healthCache.keys().next().value);
      this.healthCache.set(key, [...this.sql.exec('SELECT * FROM map_health WHERE key = ?', key)][0]);
    }
    return this.healthCache.get(key);
  }
  permit(id) {
    const value = this.permitCache.get(id) || [...this.sql.exec('SELECT * FROM map_permits WHERE id = ?', id)][0];
    // Do not retain negative lookups or arbitrary caller-supplied unknown IDs.
    return value ? remember(this.permitCache, id, value, 64) : undefined;
  }
  clearProbe(sql, healthKey, attemptId) {
    // This object is the sole writer. A cached absent/different probe proves
    // the conditional write is a no-op. Cache misses retain the original SQL.
    if (this.healthCache.has(healthKey) && this.healthCache.get(healthKey)?.probe_id !== attemptId) return;
    this.sql.exec(sql, healthKey, attemptId);
    this.healthCache.delete(healthKey);
  }
  finish(action, { attemptId, healthKey, status, retryAfterMs = 0 }) {
    if (!validId(attemptId)) return json({ error: 'Invalid attempt' }, 400);
    const permit = this.permit(attemptId);
    if (!permit) return denied('budget_conflict', 'Unknown map attempt', 409, 1);
    const now = this.now();
    if (action === '/cancel') {
      if (permit.state === 'reserved') {
        this.sql.exec("UPDATE map_permits SET state = 'cancelled' WHERE id = ?", attemptId);
        this.sql.exec('UPDATE usage SET used = MAX(0,used - ?) WHERE day = ? AND bucket IN (?,?)',
          permit.cost, permit.day, 'units:all', 'units:' + permit.bucket);
        permit.state = 'cancelled';
        // A refund can concern an evicted client or a prior-day reservation.
        // Reload these exact counters rather than inferring missing balances.
        this.counterCache.delete('units:all');
        this.counterCache.delete('units:' + permit.bucket);
      }
      if (validKey(healthKey)) {
        this.clearProbe('UPDATE map_health SET probe_id = NULL, probe_until = 0 WHERE key = ? AND probe_id = ?', healthKey, attemptId);
      }
      return json({ ok: true }); // Dispatched/ambiguous attempts are never refunded.
    }
    if (action === '/dispatch') {
      if (permit.state !== 'reserved' || permit.day !== new Date(now).toISOString().slice(0, 10) ||
          now > permit.slot + 35_000)
        return denied('budget_conflict', 'Map attempt cannot be dispatched', 409, 1);
      // An outage can become known AFTER this request joined the queue. Only
      // the current recovery probe may proceed until a valid response clears it.
      const health = validKey(healthKey) ? this.health(healthKey) : null;
      if (health && (health.probe_id !== attemptId || health.probe_until <= now)) {
        const until = Math.max(health.until_ms, health.probe_until, now + 1000);
        return denied('provider_cooldown', 'Map provider is recovering', 503, Math.ceil((until - now) / 1000));
      }
      this.sql.exec("UPDATE map_permits SET state = 'dispatched' WHERE id = ?", attemptId);
      permit.state = 'dispatched';
      return json({ ok: true });
    }
    if (!validKey(healthKey) || permit.state !== 'dispatched') return json({ error: 'Invalid provider report' }, 400);
    if (action === '/success') {
      this.clearProbe('DELETE FROM map_health WHERE key = ? AND probe_id = ?', healthKey, attemptId);
      return json({ ok: true });
    }
    if (action !== '/failure' || ![401,403,429,500,502,503,504].includes(status)) return json({ error: 'Invalid provider report' }, 400);
    // A short unadvertised blip gets a two-second shared pause, not a blanket
    // minute. Explicit provider instructions are honored. Key rotation changes healthKey.
    const pause = Math.max(2000, Math.min(DAY, Number.isFinite(retryAfterMs) ? retryAfterMs : 0));
    this.sql.exec(`INSERT INTO map_health(key,until_ms,probe_id,probe_until) VALUES(?,?,NULL,0)
      ON CONFLICT(key) DO UPDATE SET until_ms = MAX(until_ms,excluded.until_ms),
        probe_until = CASE WHEN map_health.probe_id IS NULL OR map_health.probe_id = ? THEN 0 ELSE map_health.probe_until END,
        probe_id = CASE WHEN map_health.probe_id IS NULL OR map_health.probe_id = ? THEN NULL ELSE map_health.probe_id END`,
      healthKey, now + pause, attemptId, attemptId);
    this.healthCache.delete(healthKey);
    return json({ ok: true });
  }
}
