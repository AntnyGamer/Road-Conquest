import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { MapBudget } from '../maps/budget.mjs';

test('healthy cancellations preserve refunds and limits without no-op health writes or rereads', async t => {
  const db = new DatabaseSync(':memory:'); t.after(() => db.close());
  const queries = [];
  let now = Date.UTC(2026, 9, 24);
  const budget = new MapBudget({ storage: { sql: { exec(sql, ...args) {
    queries.push(sql);
    const q = db.prepare(sql);
    return q.columns().length ? q.all(...args) : (q.run(...args), []);
  } } } }, () => now);
  const call = (action, body) => budget.fetch(new Request('https://budget/' + action,
    { method: 'POST', body: JSON.stringify(body) }));
  for (let i = 1; i <= 50; i++) {
    const body = { attemptId: '00000000-0000-4000-8000-' + String(i).padStart(12, '0'),
      healthKey: 'a'.repeat(64), bucket: 'b'.repeat(64), limit: 9600, ipLimit: 2400, costUnits: 4 };
    assert.equal((await call('reserve', body)).status, 200);
    assert.equal((await call('cancel', body)).status, 200);
    assert.equal((await call('cancel', body)).status, 200);
    now += 1500;
  }
  assert.equal(queries.filter(q => q.startsWith('UPDATE map_health')).length, 0);
  assert.equal(queries.filter(q => q.startsWith('SELECT * FROM map_health')).length, 1);
  assert.equal(db.prepare("SELECT used FROM usage WHERE bucket='units:all'").get().used, 0);
  assert.equal(db.prepare("SELECT used FROM usage WHERE bucket='attempts:all'").get().used, 50);
});

test('probe clearing keeps cold-cache writes and protects a different active recovery probe', () => {
  const queries = [];
  const budget = new MapBudget({ storage: { sql: { exec: (...args) => { queries.push(args); return []; } } } });
  queries.length = 0;
  const sql = 'DELETE FROM map_health WHERE key = ? AND probe_id = ?';
  budget.clearProbe(sql, 'health', 'cold');
  assert.deepEqual(queries, [[sql, 'health', 'cold']]);
  budget.healthCache.set('health', { probe_id: 'current' });
  budget.clearProbe(sql, 'health', 'old');
  assert.equal(queries.length, 1);
  assert.equal(budget.healthCache.get('health').probe_id, 'current');
  budget.clearProbe(sql, 'health', 'current');
  assert.deepEqual(queries[1], [sql, 'health', 'current']);
  assert.equal(budget.healthCache.has('health'), false);
});
