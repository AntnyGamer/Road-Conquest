import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { MapBudget } from '../maps/budget.mjs';

function store() {
  const db = new DatabaseSync(':memory:');
  let now = Date.UTC(2026, 9, 19, 12), failCommit = false;
  const queries = [];
  const ctx = { storage: {
    sql: { exec(query, ...args) {
      queries.push(query);
      const statement = db.prepare(query);
      return statement.columns().length ? statement.all(...args) : (statement.run(...args), []);
    } },
    transactionSync(fn) {
      db.exec('BEGIN');
      try {
        const result = fn();
        if (failCommit) throw new Error('injected commit failure');
        db.exec('COMMIT');
        return result;
      } catch (error) { db.exec('ROLLBACK'); throw error; }
    }
  } };
  let budget = new MapBudget(ctx, () => now);
  return { db, queries, advance: ms => { now += ms; }, fail: value => { failCommit = value; },
    sizes: () => ({ counters: budget.counterCache.size, permits: budget.permitCache.size }),
    restart: () => { budget = new MapBudget(ctx, () => now); },
    call: (action, args) => budget.fetch(new Request('https://budget/' + action, {
      method: 'POST', body: JSON.stringify(args)
    }))
  };
}
const key = i => i.toString(16).padStart(64, '0');
const args = (i, bucket = 1) => ({ attemptId: '00000000-0000-4000-8000-' + i.toString(16).padStart(12, '0'),
  bucket: key(bucket), healthKey: key(999), limit: 9600, ipLimit: 2400, costUnits: 4, spacingMs: 250 });
const snapshot = s => Object.fromEntries(['usage', 'map_permits', 'map_health'].map(table =>
  [table, s.db.prepare('SELECT * FROM ' + table + ' ORDER BY 1,2').all()]));
const response = async r => ({ status: r.status, body: await r.json(), headers: [...r.headers] });

test('tile-only traffic retains useful client counters without loading matching-attempt metadata', async t => {
  const s = store(); t.after(() => s.db.close());
  s.db.prepare('INSERT INTO usage(day,bucket,used) VALUES(?,?,?)')
    .run('2026-10-19', 'attempts:all', 2400);
  for (let pass = 0; pass < 2; pass++) for (let client = 1; client <= 200; client++) {
    const tile = { ...args(1, client), costUnits: 1 };
    delete tile.attemptId; delete tile.healthKey;
    assert.equal((await s.call('reserve', tile)).status, 200);
    s.advance(1500);
  }
  assert.equal(s.queries.filter(q => q.includes('SELECT bucket,used')).length, 200,
    'the second pass should reuse all 200 clients within the existing 512-entry bound');
  assert.equal(s.sizes().counters, 403);
  assert.equal(s.db.prepare("SELECT used FROM usage WHERE bucket='units:all'").get().used, 400);
  const before = snapshot(s);
  assert.equal((await s.call('reserve', args(1))).headers.get('X-RoadConquest-Error'), 'budget_attempts',
    'matching must still load and enforce its durable non-refundable attempt balance');
  assert.deepEqual(snapshot(s), before);
});

test('bounded quota cache avoids repeated SQLite reads for 200 active client addresses', async t => {
  const s = store(); t.after(() => s.db.close());
  const reads = () => s.queries.filter(q => q.includes('SELECT bucket,used')).length;
  for (let client = 1; client <= 200; client++) {
    assert.equal((await s.call('reserve', args(client, client))).status, 200);
    s.advance(1500);
  }
  const initial = reads();
  assert.equal(initial, 200, 'new clients still load their authoritative durable balances');
  assert.equal(s.sizes().counters, 604);
  for (let client = 1; client <= 200; client++) {
    assert.equal((await s.call('reserve', args(200 + client, client))).status, 200);
    s.advance(1500);
  }
  assert.equal(reads(), initial, 'the second pass across 200 active clients makes no quota-counter reads');
  assert.equal(s.sizes().counters, 604);
  assert.equal(s.db.prepare("SELECT used FROM usage WHERE bucket='units:all'").get().used, 1600);
});

test('warm matching reuses bookkeeping reads without skipping quota or dispatch writes', async t => {
  const s = store(); t.after(() => s.db.close());
  for (let i = 1; i <= 200; i++) {
    const input = args(i);
    assert.equal((await s.call('reserve', input)).status, 200);
    assert.equal((await s.call('dispatch', input)).status, 200);
    s.advance(1500);
  }
  const reads = s.queries.filter(q => q.startsWith('SELECT'));
  assert.equal(reads.filter(q => q.includes('SELECT bucket,used')).length, 1);
  assert.equal(reads.filter(q => q.includes('FROM map_permits')).length, 200,
    'new IDs still check durable replay history; just-created permits need no dispatch reread');
  assert.equal(s.db.prepare("SELECT used FROM usage WHERE bucket='units:all'").get().used, 800);
  assert.equal(s.db.prepare("SELECT used FROM usage WHERE bucket='attempts:all'").get().used, 200);
  assert.equal(s.db.prepare("SELECT COUNT(*) n FROM map_permits WHERE state='dispatched'").get().n, 200);
  // Lowered caps must apply immediately, even with cached usage.
  assert.equal((await s.call('reserve', { ...args(201), limit: 800, ipLimit: 800 })).status, 429);
});

test('known global exhaustion avoids client counter reads while retaining durable replay checks', async t => {
  const s = store(); t.after(() => s.db.close());
  for (let i = 1; i <= 300; i++) {
    assert.equal((await s.call('reserve', { ...args(i, i), limit: 1200, ipLimit: 1200 })).status, 200);
    s.advance(1500);
  }
  const before = snapshot(s), start = s.queries.length;
  // Existing clients may still be warm, while new clients have no counters.
  // Neither needs reading when the cached global budget proves denial.
  for (let i = 1; i <= 200; i++) {
    const input = { ...args(300 + i, i <= 100 ? i : 300 + i), limit: 1200, ipLimit: 1200 };
    assert.deepEqual(await response(await s.call('reserve', input)), {
      status: 429,
      body: { error: 'Daily map allowance exhausted', code: 'budget_global' },
      headers: [...Response.json({}, { headers: { 'Cache-Control': 'no-store', 'Retry-After': '42750',
        'X-RoadConquest-Error': 'budget_global' } }).headers]
    });
  }
  const queries = s.queries.slice(start);
  assert.equal(queries.filter(q => q.includes('SELECT bucket,used')).length, 0);
  assert.equal(queries.filter(q => q.includes('FROM map_permits')).length, 200,
    'unknown attempt IDs must still consult durable replay history');
  assert.ok(queries.every(q => q.startsWith('SELECT')), 'denials never change durable state');
  assert.deepEqual(snapshot(s), before);
});

test('early global denial respects exact request cost, current caps, cold state and UTC rollover', async t => {
  const s = store(); t.after(() => s.db.close());
  s.db.prepare('INSERT INTO usage(day,bucket,used) VALUES(?,?,?)').run('2026-10-19', 'all', 2);
  const input = { ...args(1, 2), limit: 9, ipLimit: 9 };
  const reads = () => s.queries.filter(q => q.includes('SELECT bucket,used')).length;
  assert.equal((await s.call('reserve', input)).headers.get('X-RoadConquest-Error'), 'budget_global');
  assert.equal(reads(), 1, 'cold state must load historical whole-credit and unit balances');
  assert.equal((await s.call('reserve', { ...input, ...args(2, 3), limit: 9, ipLimit: 9 })).status, 429);
  assert.equal(reads(), 1, 'both cached global balances prove the second denial');
  const tile = { ...input, bucket: key(4), costUnits: 1 };
  delete tile.attemptId; delete tile.healthKey;
  assert.equal((await s.call('reserve', tile)).status, 200, 'a remaining quarter-credit still admits a tile');
  assert.equal((await s.call('reserve', { ...args(3, 5), limit: 13, ipLimit: 13 })).status, 200,
    'raising the cap takes effect immediately');
  assert.equal((await s.call('reserve', { ...args(4, 6), limit: 9, ipLimit: 9 })).status, 429);
  s.restart();
  const before = reads();
  assert.equal((await s.call('reserve', { ...args(5, 7), limit: 13, ipLimit: 13 })).status, 429);
  assert.equal(reads(), before + 1, 'restart cannot trust a prior in-memory balance');
  s.advance(86_400_000);
  assert.equal((await s.call('reserve', { ...args(6, 8), limit: 9, ipLimit: 9 })).status, 200);
  assert.equal(s.db.prepare("SELECT used FROM usage WHERE bucket='units:all'").get().used, 4);
  assert.equal(s.db.prepare("SELECT COUNT(*) n FROM usage WHERE day='2026-10-19'").get().n, 0);
});

test('global denial preserves replay, conflict and health precedence and applies known refunds', async t => {
  const s = store(); t.after(() => s.db.close());
  const first = { ...args(1), limit: 16, ipLimit: 16, costUnits: 8 };
  const second = { ...first, ...args(2, 2), limit: 16, ipLimit: 16, costUnits: 8 };
  assert.equal((await s.call('reserve', first)).status, 200);
  assert.equal((await s.call('reserve', second)).status, 200);
  const next = { ...args(3, 3), limit: 16, ipLimit: 16 };
  assert.equal((await s.call('reserve', next)).headers.get('X-RoadConquest-Error'), 'budget_global');
  assert.equal((await s.call('reserve', first)).status, 200, 'a valid replay cannot be denied by spent quota');
  assert.equal((await s.call('reserve', { ...first, bucket: key(4) })).headers.get('X-RoadConquest-Error'),
    'budget_conflict', 'conflict detection must precede quota rejection');
  assert.equal((await s.call('cancel', second)).status, 200);
  const reads = s.queries.filter(q => q.includes('SELECT bucket,used')).length;
  assert.equal((await s.call('reserve', next)).status, 200, 'refund invalidation must reopen available credits');
  assert.equal(s.queries.filter(q => q.includes('SELECT bucket,used')).length, reads + 1,
    'the previously denied new client still loads its own counters');
  assert.equal((await s.call('dispatch', first)).status, 200);
  assert.equal((await s.call('failure', { ...first, status: 503 })).status, 200);
  const exhausted = { ...args(4, 5), limit: 12, ipLimit: 12 };
  assert.equal((await s.call('reserve', exhausted)).headers.get('X-RoadConquest-Error'), 'provider_cooldown');
  s.advance(2000);
  assert.equal((await s.call('reserve', exhausted)).headers.get('X-RoadConquest-Error'), 'budget_global');
  assert.equal(s.db.prepare("SELECT used FROM usage WHERE bucket='attempts:all'").get().used, 3);
});

test('refunds retain exact warm balances while evicted clients still reload durable counters', async t => {
  const s = store(); t.after(() => s.db.close());
  const first = args(1), second = args(2);
  await s.call('reserve', first);
  await s.call('reserve', second);
  const reads = () => s.queries.filter(q => q.includes('SELECT bucket,used')).length;
  const start = reads();
  await s.call('cancel', first);
  await s.call('cancel', first);
  assert.equal((await s.call('reserve', { ...args(3), limit: 12, ipLimit: 12 })).status, 200);
  assert.equal(reads(), start, 'one warm refund can be applied once without discarding known balances');
  assert.equal((await s.call('reserve', { ...args(4), limit: 8, ipLimit: 8 })).headers.get('X-RoadConquest-Error'), 'budget_global');
  assert.equal(s.db.prepare("SELECT used FROM usage WHERE bucket='units:all'").get().used, 8);

  s.advance(86_400_000);
  for (let client = 1; client <= 400; client++) {
    assert.equal((await s.call('reserve', args(1000 + client, client))).status, 200);
    s.advance(1500);
  }
  const before = reads();
  await s.call('cancel', args(1001, 1));
  const current = args(2001, 300);
  assert.equal((await s.call('reserve', current)).status, 200);
  assert.equal(reads(), before, 'an evicted refund recipient does not invalidate the known global balance');
  assert.equal((await s.call('reserve', { ...args(2002, 1), ipLimit: 8 })).status, 200,
    'an evicted client reloads its refunded zero balance');
  assert.equal(reads(), before + 1);
  assert.equal((await s.call('reserve', { ...args(2003, 1), ipLimit: 4 })).headers.get('X-RoadConquest-Error'), 'budget_client');
  assert.equal(s.db.prepare("SELECT used FROM usage WHERE bucket='units:all'").get().used, 1604);
});

test('rollback invalidates warmed counters and mutated permits before a retry', async t => {
  const s = store(); t.after(() => s.db.close());
  const first = args(1);
  await s.call('reserve', first);
  const before = snapshot(s);
  s.fail(true);
  await assert.rejects(s.call('dispatch', first), /injected commit failure/);
  assert.deepEqual(snapshot(s), before);
  s.fail(false);
  assert.equal((await s.call('dispatch', first)).status, 200);
  assert.equal((await s.call('dispatch', first)).status, 409);
  const second = { ...args(2), limit: 8, ipLimit: 8 };
  s.fail(true);
  await assert.rejects(s.call('reserve', second), /injected commit failure/);
  s.fail(false);
  assert.equal((await s.call('reserve', second)).status, 200);
  s.fail(true);
  await assert.rejects(s.call('cancel', second), /injected commit failure/);
  s.fail(false);
  assert.equal((await s.call('reserve', { ...second, attemptId: args(3).attemptId })).status, 429);
  await s.call('cancel', second);
  assert.equal(s.db.prepare("SELECT used FROM usage WHERE bucket='units:all'").get().used, 4);
  assert.equal((await s.call('reserve', { ...second, attemptId: args(3).attemptId })).headers.get('X-RoadConquest-Error'),
    'budget_attempts', 'refunds must not restore the non-refundable attempt allowance');
});

test('eviction and restart reload durable permits, refunds and legacy counters', async t => {
  const s = store(); t.after(() => s.db.close());
  const day = '2026-10-19';
  s.db.prepare('INSERT INTO usage(day,bucket,used) VALUES(?,?,1)').run(day, 'all');
  s.db.prepare('INSERT INTO usage(day,bucket,used) VALUES(?,?,1)').run(day, key(1));
  for (let i = 1; i <= 400; i++) {
    assert.equal((await s.call('reserve', args(i, i))).status, 200);
    s.advance(1500);
  }
  assert.deepEqual(s.sizes(), { counters: 1024, permits: 64 }, 'client churn cannot grow metadata caches without bound');
  const first = args(1);
  const used = () => s.db.prepare("SELECT used FROM usage WHERE bucket='units:all'").get().used;
  assert.equal(used(), 1600);
  assert.equal((await s.call('reserve', first)).status, 200);
  assert.equal(used(), 1600);
  await s.call('cancel', first); await s.call('cancel', first);
  assert.equal(used(), 1596);
  assert.equal((await s.call('dispatch', first)).status, 409);
  assert.equal((await s.call('reserve', first)).status, 409);
  // The old whole-credit records still constrain both caps after eviction.
  assert.equal((await s.call('reserve', { ...args(401), limit: 1200, ipLimit: 1200 })).status, 429);
  assert.equal((await s.call('reserve', { ...args(402), ipLimit: 4 })).headers.get('X-RoadConquest-Error'), 'budget_client');
  s.restart();
  assert.equal((await s.call('reserve', { ...args(401), limit: 1200, ipLimit: 1200 })).status, 429);
  s.advance(86_400_000);
  assert.equal((await s.call('reserve', args(401))).status, 200);
  assert.equal(used(), 4);
  assert.equal((await s.call('cancel', first)).status, 409, 'daily pruning removes old permits from memory too');
});

test('tiles and matching retain shared credit caps, pacing and refund accounting', async t => {
  const s = store(); t.after(() => s.db.close());
  const first = { ...args(1), limit: 12, ipLimit: 12 };
  const second = { ...first, attemptId: args(2).attemptId };
  const tile = { ...first, costUnits: 1 };
  delete tile.attemptId; delete tile.healthKey;
  const reserve = async input => response(await s.call('reserve', input));
  assert.equal((await reserve(first)).body.waitMs, 0);
  assert.equal((await s.call('dispatch', first)).status, 200);
  assert.equal((await reserve(tile)).body.waitMs, 250);
  assert.equal((await reserve(second)).body.waitMs, 500);
  for (let i = 0; i < 3; i++) assert.equal((await reserve(tile)).status, 200);
  assert.equal((await reserve(tile)).body.code, 'budget_global');
  await s.call('cancel', second);
  assert.equal(s.db.prepare("SELECT used FROM usage WHERE bucket='units:all'").get().used, 8);
  assert.equal((await reserve(tile)).body.waitMs, 1500, 'cancellation never rewinds the provider queue');
  assert.equal((await reserve(first)).status, 200, 'replaying a dispatched permit cannot spend quota again');
  assert.equal((await s.call('dispatch', first)).status, 409);
  assert.equal(s.db.prepare("SELECT used FROM usage WHERE bucket='attempts:all'").get().used, 2);
});

test('cached bookkeeping matches cold durable reads through mixed requests and recovery', async t => {
  const warm = store(), cold = store();
  t.after(() => { warm.db.close(); cold.db.close(); });
  let seed = 217;
  const random = n => { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return seed % n; };
  const inputs = [];
  for (let i = 1; i <= 1200; i++) {
    const elapsed = i % 251 === 0 ? 86_400_000 : random(4000);
    warm.advance(elapsed); cold.advance(elapsed);
    let action, input;
    if (inputs.length && random(3) === 0) {
      input = { ...inputs[random(inputs.length)] };
      action = ['reserve', 'dispatch', 'cancel', 'success', 'failure'][random(5)];
      if (action === 'failure') { input.status = [401, 429, 503][random(3)]; input.retryAfterMs = random(5000); }
    } else {
      action = 'reserve';
      input = { ...args(i, 1 + random(300)), limit: 400, ipLimit: 40,
        costUnits: 1 + random(8), spacingMs: random(2) ? 250 : 1050 };
      if (random(4) === 0) { delete input.attemptId; delete input.healthKey; }
      else inputs.push(input);
    }
    // Recreating the sole active instance forces authoritative reads every time.
    cold.restart();
    assert.deepEqual(await response(await warm.call(action, input)), await response(await cold.call(action, input)),
      'warm/cold response differs at operation ' + i);
    if (i % 40 === 0) assert.deepEqual(snapshot(warm), snapshot(cold), 'durable state differs at operation ' + i);
  }
  assert.deepEqual(snapshot(warm), snapshot(cold));
});

test('known recovery writes retain probe metadata through dispatch, cancellation and success', async t => {
  const s = store(); t.after(() => s.db.close());
  const healthReads = () => s.queries.filter(q => q.startsWith('SELECT') && q.includes('FROM map_health')).length;
  const failed = args(1), cancelled = args(2), recovered = args(3), next = args(4);
  await s.call('reserve', failed); await s.call('dispatch', failed);
  await s.call('failure', { ...failed, status: 503 });
  s.advance(2000);
  await s.call('reserve', cancelled);
  const reads = healthReads();
  await s.call('cancel', cancelled);
  await s.call('reserve', recovered);
  await s.call('dispatch', recovered);
  await s.call('success', recovered);
  s.advance(1500);
  await s.call('reserve', next); await s.call('dispatch', next);
  assert.equal(healthReads(), reads, 'known probe updates and clears need no immediate SQL reread');
  assert.equal(s.db.prepare('SELECT COUNT(*) n FROM map_health').get().n, 0);
  assert.equal(s.db.prepare("SELECT used FROM usage WHERE bucket='units:all'").get().used, 12);
  assert.equal(s.db.prepare("SELECT used FROM usage WHERE bucket='attempts:all'").get().used, 4);
});

test('rolled-back recovery writes reload the durable probe before retrying', async t => {
  const s = store(); t.after(() => s.db.close());
  const failed = args(1), recovered = args(2);
  await s.call('reserve', failed); await s.call('dispatch', failed);
  await s.call('failure', { ...failed, status: 503 }); s.advance(2000);
  const before = snapshot(s);
  s.fail(true);
  await assert.rejects(s.call('reserve', recovered), /injected commit failure/);
  assert.deepEqual(snapshot(s), before);
  s.fail(false);
  assert.equal((await s.call('reserve', recovered)).status, 200);
  assert.equal((await s.call('dispatch', recovered)).status, 200);
  const dispatched = snapshot(s);
  s.fail(true);
  await assert.rejects(s.call('success', recovered), /injected commit failure/);
  assert.deepEqual(snapshot(s), dispatched);
  s.fail(false);
  assert.equal((await s.call('success', recovered)).status, 200);
  assert.equal(s.db.prepare('SELECT COUNT(*) n FROM map_health').get().n, 0);
  s.restart();
  s.advance(1500);
  assert.equal((await s.call('reserve', args(3))).status, 200);
});
