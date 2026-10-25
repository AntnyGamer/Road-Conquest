import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import pg from 'pg';
import { LOGIN_LIMIT_SQL, LOGIN_WITH_USER_SQL, RATE_LIMIT_SESSION_SQL, RATE_LIMIT_PROFILE_SQL, RATE_LIMIT_LOGOUT_SQL } from '../src/rate-limits.mjs';

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

test('single-query session and IP budget preserve expiry, denial and window accounting', { skip: !process.env.DATABASE_URL }, async () => {
  const pool=new pg.Pool({connectionString:process.env.DATABASE_URL});
  try {
    await pool.query(await readFile(new URL('../schema.sql',import.meta.url),'utf8'));
    await pool.query('TRUNCATE auth_rate_limits, sessions, users CASCADE');
    const user=(await pool.query(`INSERT INTO users(username_display,username_key,password_salt,password_hash)
      VALUES ('SessionUser','sessionuser',$1,$2) RETURNING id`,[Buffer.alloc(16),Buffer.alloc(64)])).rows[0];
    const valid=Buffer.alloc(32,51), forged=Buffer.alloc(32,52);
    await pool.query("INSERT INTO sessions(token_hash,user_id,expires_at) VALUES($1,$2,CURRENT_TIMESTAMP+INTERVAL '1 day')", [valid,user.id]);
    const bucket=['friends-ip',Buffer.alloc(32,53),60000,2];
    let queries=0;
    async function check(hash) { queries++; return (await pool.query(RATE_LIMIT_SESSION_SQL,[...bucket,hash])).rows[0]; }
    const good=await check(valid);
    assert.equal(good.allowed,true);assert.equal(good.id,user.id);
    const missing=await check(forged);
    assert.equal(missing.allowed,true);assert.equal(missing.id,null);
    const limited=await check(valid);
    assert.equal(limited.allowed,false);assert.equal(limited.id,null);
    assert.equal(queries,3);
    assert.equal((await pool.query("SELECT attempts FROM auth_rate_limits WHERE bucket='friends-ip'")).rows[0].attempts,3);
    await pool.query("UPDATE auth_rate_limits SET window_started_at=CURRENT_TIMESTAMP-INTERVAL '2 minutes'");
    await pool.query("UPDATE sessions SET created_at=CURRENT_TIMESTAMP-INTERVAL '2 days', expires_at=CURRENT_TIMESTAMP-INTERVAL '1 day' WHERE token_hash=$1",[valid]);
    const expired=await check(valid);
    assert.equal(expired.allowed,true);assert.equal(expired.id,null);
  } finally { await pool.end(); }
});

test('profile reads and logout revocations share bounded single-statement IP budgets', { skip: !process.env.DATABASE_URL }, async () => {
  const pool = new pg.Pool({ connectionString: process.env.DATABASE_URL });
  try {
    await pool.query(await readFile(new URL('../schema.sql', import.meta.url), 'utf8'));
    await pool.query('TRUNCATE auth_rate_limits, sessions, users CASCADE');
    const { rows: [user] } = await pool.query(`INSERT INTO users(username_display,username_key,password_salt,password_hash)
      VALUES ('BudgetDriver','budgetdriver',$1,$2) RETURNING id`, [Buffer.alloc(16), Buffer.alloc(64)]);
    const token = Buffer.alloc(32, 24), invalidToken = Buffer.alloc(32, 25);
    await pool.query("INSERT INTO sessions(token_hash,user_id,expires_at) VALUES($1,$2,CURRENT_TIMESTAMP+INTERVAL '1 day')", [token, user.id]);

    const profileParams = ['profile-ip', Buffer.alloc(32, 26), 60_000, 2];
    const read = async hash => (await pool.query(RATE_LIMIT_PROFILE_SQL, [...profileParams, hash])).rows[0];
    assert.deepEqual(await read(token), { allowed: true, username_display: 'BudgetDriver', leaderboard_visible: true });
    assert.deepEqual(await read(invalidToken), { allowed: true, username_display: null, leaderboard_visible: null });
    assert.deepEqual(await read(token), { allowed: false, username_display: null, leaderboard_visible: null });
    const saturated = (await pool.query("SELECT attempts,xmin::text AS version FROM auth_rate_limits WHERE bucket='profile-ip'")).rows[0];
    assert.deepEqual(await read(token), { allowed: false, username_display: null, leaderboard_visible: null });
    assert.deepEqual((await pool.query("SELECT attempts,xmin::text AS version FROM auth_rate_limits WHERE bucket='profile-ip'")).rows[0], saturated);
    await pool.query("UPDATE auth_rate_limits SET window_started_at=CURRENT_TIMESTAMP-INTERVAL '2 minutes' WHERE bucket='profile-ip'");
    assert.equal((await read(token)).username_display, 'BudgetDriver');
    await pool.query("UPDATE sessions SET created_at=CURRENT_TIMESTAMP-INTERVAL '2 days', expires_at=CURRENT_TIMESTAMP-INTERVAL '1 day' WHERE token_hash=$1", [token]);
    assert.equal((await read(token)).username_display, null);
    await pool.query("UPDATE sessions SET expires_at=CURRENT_TIMESTAMP+INTERVAL '1 day' WHERE token_hash=$1", [token]);

    const logoutParams = ['logout-ip', Buffer.alloc(32, 27), 60_000, 1];
    const logout = async hash => (await pool.query(RATE_LIMIT_LOGOUT_SQL, [...logoutParams, hash])).rows[0];
    assert.deepEqual(await logout(invalidToken), { allowed: true, revoked: false });
    assert.deepEqual(await logout(token), { allowed: false, revoked: true },
      'A real active session must be revocable even after other clients exhaust a shared IP budget');
    assert.equal((await pool.query('SELECT 1 FROM sessions WHERE token_hash=$1', [token])).rowCount, 0);
    assert.deepEqual(await logout(invalidToken), { allowed: false, revoked: false });
    await pool.query("UPDATE auth_rate_limits SET window_started_at=CURRENT_TIMESTAMP-INTERVAL '2 minutes' WHERE bucket='logout-ip'");
    await pool.query("INSERT INTO sessions(token_hash,user_id,expires_at) VALUES($1,$2,CURRENT_TIMESTAMP+INTERVAL '1 day')", [token, user.id]);
    assert.deepEqual(await logout(token), { allowed: true, revoked: true });
    assert.equal((await pool.query('SELECT 1 FROM sessions WHERE token_hash=$1', [token])).rowCount, 0);
  } finally { await pool.end(); }
});
