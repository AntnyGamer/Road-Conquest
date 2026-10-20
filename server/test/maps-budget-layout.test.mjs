import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { MapBudget } from '../maps/budget.mjs';

const day = '2026-10-19', bucket = 'a'.repeat(64);
const legacyTable = 'CREATE TABLE usage(day TEXT,bucket TEXT,used INTEGER NOT NULL,PRIMARY KEY(day,bucket))';
function store(legacy = false, seed = []) {
  const db = new DatabaseSync(':memory:');
  if (legacy) {
    db.exec(legacyTable);
    const insert = db.prepare('INSERT INTO usage VALUES(?,?,?)');
    for (const row of seed) insert.run(...row);
  }
  let now = Date.UTC(2026, 9, 19, 12), fail = false;
  const ctx = { storage: { sql: { exec(query, ...args) {
    const statement = db.prepare(query);
    return statement.columns().length ? statement.all(...args) : (statement.run(...args), []);
  } }, transactionSync(fn) {
    db.exec('BEGIN');
    try { const result = fn(); if (fail) throw new Error('injected commit failure'); db.exec('COMMIT'); return result; }
    catch (error) { db.exec('ROLLBACK'); throw error; }
  } } };
  let budget = new MapBudget(ctx, () => now);
  return { db, compact: () => db.prepare('PRAGMA index_info(usage)').all().length > 0,
    rows: () => db.prepare('SELECT * FROM usage ORDER BY day,bucket').all(),
    advance: ms => { now += ms; }, fail: value => { fail = value; },
    restart: () => { budget = new MapBudget(ctx, () => now); },
    call: (action = 'reserve', extra = {}) => budget.fetch(new Request('https://budget/' + action, {
      method: 'POST', body: JSON.stringify({ bucket, limit: 12, ipLimit: 12, costUnits: 4, ...extra })
    })) };
}

test('new compact counter storage retains exact credit, pacing and restart behavior', async t => {
  const s = store(); t.after(() => s.db.close());
  assert.equal(s.compact(), true);
  assert.equal((await (await s.call()).json()).waitMs, 0);
  s.restart();
  assert.equal((await (await s.call()).json()).waitMs, 250);
  assert.equal((await (await s.call()).json()).waitMs, 500);
  const before = s.rows();
  assert.equal((await s.call()).headers.get('X-RoadConquest-Error'), 'budget_global');
  assert.deepEqual(s.rows(), before);
});

test('a live legacy table keeps every current-day balance and upgrades only after ordinary UTC cleanup', async t => {
  const seed = [[day, 'all', 1], [day, bucket, 1], [day, 'units:all', 4], [day, 'units:' + bucket, 4]];
  const s = store(true, seed); t.after(() => s.db.close());
  assert.equal(s.compact(), false);
  assert.deepEqual(s.rows().map(row => [row.day, row.bucket, row.used]), seed.toSorted((a,b) => a[1].localeCompare(b[1])));
  assert.equal((await s.call()).status, 200);
  assert.equal(s.compact(), false, 'today\'s historical whole-credit and quarter-credit balances cannot be reset');
  assert.equal((await s.call()).headers.get('X-RoadConquest-Error'), 'budget_global');
  s.restart();
  const before = s.rows();
  assert.equal((await s.call()).status, 429);
  assert.deepEqual(s.rows(), before);
  assert.equal(s.compact(), false);
  s.advance(86_400_000);
  assert.equal((await s.call()).status, 200);
  assert.equal(s.compact(), true);
  assert.ok(s.rows().every(row => row.day === '2026-10-20'));
  assert.equal(s.db.prepare("SELECT used FROM usage WHERE bucket='units:all'").get().used, 4);
  s.restart();
  assert.equal((await s.call()).status, 200);
});

test('failed cleanup/layout/reservation transaction restores old counters and layout before retry', async t => {
  const s = store(true, [['2026-10-18', 'units:all', 9600]]); t.after(() => s.db.close());
  const before = s.rows();
  s.fail(true);
  await assert.rejects(s.call(), /injected commit failure/);
  assert.deepEqual(s.rows(), before);
  assert.equal(s.compact(), false);
  s.fail(false);
  assert.equal((await s.call()).status, 200);
  assert.equal(s.compact(), true);
  assert.equal(s.db.prepare("SELECT used FROM usage WHERE bucket='units:all'").get().used, 4);
  assert.equal((await s.call()).status, 200);
  assert.equal((await s.call()).status, 200);
  assert.equal((await s.call()).status, 429);
});

test('unexpired or unusual legacy rows prevent an empty-table upgrade and remain intact', async t => {
  const s = store(true, [['2026-10-20', 'units:all', 8], [null, 'historical', 7]]);
  t.after(() => s.db.close());
  assert.equal((await s.call()).status, 200);
  assert.equal(s.compact(), false);
  assert.equal(s.db.prepare("SELECT used FROM usage WHERE bucket='historical'").get().used, 7);
  s.advance(86_400_000);
  assert.equal((await s.call()).status, 200);
  assert.equal((await s.call()).headers.get('X-RoadConquest-Error'), 'budget_global');
  assert.equal(s.compact(), false);
  assert.equal(s.db.prepare("SELECT used FROM usage WHERE bucket='historical'").get().used, 7);
});
