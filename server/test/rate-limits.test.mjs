import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import pg from 'pg';
import { LOGIN_LIMIT_SQL, LOGIN_WITH_USER_SQL } from '../src/rate-limits.mjs';

test('paired login budgets preserve short circuit counts, saturation and independent window resets', { skip: !process.env.DATABASE_URL }, async () => {
  const pool = new pg.Pool({ connectionString:process.env.DATABASE_URL });
  try {
    await pool.query(await readFile(new URL('../schema.sql',import.meta.url),'utf8'));
    await pool.query('TRUNCATE auth_rate_limits');
    const args=['login-ip',Buffer.alloc(32,1),900000,2,'login-user',Buffer.alloc(32,2),900000,1];
    const reserve=async()=> (await pool.query(LOGIN_LIMIT_SQL,args)).rows[0].allowed;
    const state=async()=> (await pool.query('SELECT bucket,attempts,xmin::text AS version FROM auth_rate_limits ORDER BY bucket')).rows;
    assert.equal(await reserve(),true);
    assert.equal(await reserve(),false); // Username cap, while IP still allowed.
    assert.equal(await reserve(),false); // IP cap: username count must not advance.
    const saturated=await state();
    assert.deepEqual(saturated.map(r=>[r.bucket,r.attempts]),[['login-ip',3],['login-user',2]]);
    assert.equal(await reserve(),false);
    assert.deepEqual(await state(),saturated); // Already saturated rows are not rewritten.
    await pool.query("UPDATE auth_rate_limits SET window_started_at=CURRENT_TIMESTAMP-INTERVAL '16 minutes' WHERE bucket='login-ip'");
    assert.equal(await reserve(),false); // IP resets; username remains capped.
    assert.equal((await state())[0].attempts,1);
    await pool.query("UPDATE auth_rate_limits SET window_started_at=CURRENT_TIMESTAMP-INTERVAL '16 minutes' WHERE bucket='login-user'");
    assert.equal(await reserve(),true);
    assert.deepEqual((await state()).map(r=>r.attempts),[2,1]);
    assert.equal(await reserve(),false);
    assert.equal((await state())[1].attempts,1);
  } finally { await pool.end(); }
});

test('concurrent paired reservations cannot exceed the shared username cap', { skip: !process.env.DATABASE_URL }, async () => {
  const pool=new pg.Pool({ connectionString:process.env.DATABASE_URL,max:5 });
  try {
    await pool.query(await readFile(new URL('../schema.sql',import.meta.url),'utf8'));
    await pool.query('TRUNCATE auth_rate_limits');
    const attempts=await Promise.all(Array.from({length:100},(_,i)=>pool.query(LOGIN_LIMIT_SQL,
      ['login-ip',Buffer.alloc(32,10+i%10),900000,20,'login-user',Buffer.alloc(32,3),900000,8])));
    assert.equal(attempts.filter(r=>r.rows[0].allowed).length,8);
    assert.equal((await pool.query("SELECT attempts FROM auth_rate_limits WHERE bucket='login-user'")).rows[0].attempts,9);
    assert.equal((await pool.query("SELECT sum(attempts) FROM auth_rate_limits WHERE bucket='login-ip'")).rows[0].sum,'100');
  } finally { await pool.end(); }
});

test('one-round-trip login lookup preserves both budgets and hides denied account data', { skip: !process.env.DATABASE_URL }, async () => {
  const pool=new pg.Pool({ connectionString:process.env.DATABASE_URL });
  try {
    await pool.query(await readFile(new URL('../schema.sql',import.meta.url),'utf8'));
    await pool.query('TRUNCATE users,auth_rate_limits CASCADE');
    const salt=Buffer.alloc(16,7),hash=Buffer.alloc(64,9);
    await pool.query("INSERT INTO users(username_display,username_key,password_salt,password_hash) VALUES ('Driver','driver',$1,$2)",[salt,hash]);
    const args=['login-ip',Buffer.alloc(32,4),900000,4,'login-user',Buffer.alloc(32,5),900000,2];
    const permitted=(await pool.query(LOGIN_WITH_USER_SQL,[...args,'driver'])).rows[0];
    assert.equal(permitted.allowed,true);
    assert.equal(permitted.username_display,'Driver');
    assert.deepEqual(permitted.password_salt,salt);
    assert.deepEqual(permitted.password_hash,hash);
    const missing=(await pool.query(LOGIN_WITH_USER_SQL,[...args,'missing'])).rows[0];
    assert.equal(missing.allowed,true);assert.equal(missing.id,null);
    assert.equal(missing.password_hash,null);
    const denied=(await pool.query(LOGIN_WITH_USER_SQL,[...args,'driver'])).rows[0];
    assert.equal(denied.allowed,false);assert.equal(denied.id,null);
    assert.equal(denied.password_hash,null);
    assert.deepEqual((await pool.query('SELECT bucket,attempts FROM auth_rate_limits ORDER BY bucket')).rows,
      [{bucket:'login-ip',attempts:3},{bucket:'login-user',attempts:3}]);
    const noName=(await pool.query(LOGIN_WITH_USER_SQL,
      ['login-ip',Buffer.alloc(32,6),900000,4,'login-user',Buffer.alloc(32,7),900000,2,null])).rows[0];
    assert.equal(noName.allowed,true);assert.equal(noName.id,null);
  } finally { await pool.end(); }
});
