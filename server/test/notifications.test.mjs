import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash, generateKeyPairSync, randomBytes, randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import pg from 'pg';
import { createFcmSender, createNotifications } from '../src/notifications.mjs';
import { createFriends } from '../src/friends.mjs';

test('FCM uses scoped OAuth, private data-only payloads, shared refresh, validation and safe invalid-token handling', async () => {
  const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const account = { project_id: 'roadconquest-9f0eb', client_email: 'test@roadconquest-9f0eb.iam.gserviceaccount.com',
    private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }) };
  let auth = 0; const sent = [];
  const sender = createFcmSender(JSON.stringify(account), async (url, init) => {
    assert.equal(init.redirect, 'error');
    if (url === 'https://oauth2.googleapis.com/token') {
      auth++;
      const jwt = new URLSearchParams(init.body).get('assertion');
      const claims = JSON.parse(Buffer.from(jwt.split('.')[1], 'base64url'));
      assert.equal(claims.scope, 'https://www.googleapis.com/auth/firebase.messaging');
      return Response.json({ access_token: 'test-access-token', expires_in: 3600 });
    }
    assert.equal(url, 'https://fcm.googleapis.com/v1/projects/roadconquest-9f0eb/messages:send');
    assert.equal(init.headers.Authorization, 'Bearer test-access-token');
    const body = JSON.parse(init.body); sent.push(body);
    assert.equal(body.message.notification, undefined);
    assert.deepEqual(Object.keys(body.message.data).sort(), ['binding', 'kind']);
    return body.message.fid === 'gone' ? Response.json({ error: { details: [{ errorCode: 'UNREGISTERED' }] } }, { status: 404 }) : Response.json({ name: 'test-message' });
  });
  const binding = randomUUID();
  assert.deepEqual(await Promise.all([sender('test', binding, true), sender('gone', binding)]), ['sent', 'unregistered']);
  assert.equal(auth, 1); assert.equal(sent[0].validate_only, true);
  assert.equal(createFcmSender(undefined), null);
  assert.throws(() => createFcmSender(JSON.stringify({ ...account, project_id: 'other' })), /Invalid/);
});

test('notification tokens are encrypted, session-scoped, bounded, revoked and removed with accounts', { skip: !process.env.DATABASE_URL }, async () => {
  const pool = new pg.Pool({ connectionString: process.env.DATABASE_URL });
  try {
    await pool.query(await readFile(new URL('../schema.sql', import.meta.url), 'utf8'));
    await pool.query('TRUNCATE users CASCADE');
    const users = (await pool.query(`INSERT INTO users(username_display,username_key,password_salt,password_hash)
      VALUES ('Pushone','pushone',$1,$2),('Pushtwo','pushtwo',$1,$2) RETURNING id`, [Buffer.alloc(16), Buffer.alloc(64)])).rows;
    for (const user of users) {
      user.session_hash = randomBytes(32);
      await pool.query("INSERT INTO sessions(token_hash,user_id,expires_at) VALUES($1,$2,CURRENT_TIMESTAMP+INTERVAL '1 day')", [user.session_hash, user.id]);
    }
    const sent = [];
    const notifications = createNotifications(pool, { pepper: 'test-pepper', sender: async (...values) => { sent.push(values); return 'sent'; } });
    const token = 'c' + 'a'.repeat(21); const binding = randomUUID();
    await notifications.register(users[1], { fid: token, binding });
    const stored = (await pool.query('SELECT * FROM notification_devices')).rows[0];
    assert.equal(stored.encrypted_token.includes(Buffer.from(token)), false);
    const friends = createFriends(pool, target => notifications.friendRequest(target));
    await friends.change(users[0], 'Pushtwo', 'request');
    await friends.change(users[0], 'Pushtwo', 'request');
    assert.deepEqual(sent, [[token, binding]]); // New requests only, after commit.
    await notifications.remove(users[0]);
    assert.equal((await pool.query('SELECT count(*) FROM notification_devices')).rows[0].count, '1');
    await assert.rejects(notifications.register(users[1], { fid: 'bad', binding }), /Invalid/);
    await notifications.remove(users[1]);
    await notifications.friendRequest(users[1].id); assert.equal(sent.length, 1);
    await notifications.register(users[1], { fid: token, binding });
    await pool.query('DELETE FROM sessions WHERE token_hash=$1', [users[1].session_hash]);
    assert.equal((await pool.query('SELECT count(*) FROM notification_devices')).rows[0].count, '0');
    await assert.rejects(notifications.register(users[1], { fid: token, binding }), /Authentication/);
    await notifications.register(users[0], { fid: token, binding });
    await pool.query('DELETE FROM users WHERE id=$1', [users[0].id]);
    assert.equal((await pool.query('SELECT count(*) FROM notification_devices')).rows[0].count, '0');
  } finally { await pool.end(); }
});

test('device refresh, FID replacement and ownership transfer keep fresh encrypted session bindings', { skip: !process.env.DATABASE_URL }, async () => {
  const pool = new pg.Pool({ connectionString: process.env.DATABASE_URL });
  try {
    await pool.query(await readFile(new URL('../schema.sql', import.meta.url), 'utf8'));
    await pool.query('TRUNCATE users CASCADE');
    const users = (await pool.query(`INSERT INTO users(username_display,username_key,password_salt,password_hash)
      VALUES ('Refreshone','refreshone',$1,$2),('Refreshtwo','refreshtwo',$1,$2) RETURNING id`, [Buffer.alloc(16), Buffer.alloc(64)])).rows;
    for (const user of users) {
      user.session_hash = randomBytes(32);
      await pool.query("INSERT INTO sessions(token_hash,user_id,expires_at) VALUES($1,$2,CURRENT_TIMESTAMP+INTERVAL '1 day')", [user.session_hash,user.id]);
    }
    const sent = [];
    const service = createNotifications(pool, { pepper: 'refresh-only-test-pepper', sender: async (...args) => { sent.push(args); return 'sent'; } });
    const first = 'c' + 'x'.repeat(21), second = 'd' + 'y'.repeat(21), binding = randomUUID();
    await service.register(users[0], { fid: first, binding: randomUUID() });
    await pool.query("UPDATE notification_devices SET updated_at=CURRENT_TIMESTAMP-INTERVAL '29 days'");
    await service.register(users[0], { fid: first, binding });
    assert.equal((await pool.query("SELECT count(*) FROM notification_devices WHERE updated_at>CURRENT_TIMESTAMP-INTERVAL '1 minute' AND binding=$1", [binding])).rows[0].count, '1');
    await service.friendRequest(users[0].id);
    assert.deepEqual(sent.pop(), [first,binding]);
    await service.register(users[0], { fid: second, binding });
    assert.equal((await pool.query('SELECT count(*) FROM notification_devices')).rows[0].count, '1');
    await service.friendRequest(users[0].id);
    assert.deepEqual(sent.pop(), [second,binding]);
    // A caller cannot use another user's session to remove or replace its device.
    await assert.rejects(service.register({ ...users[1], session_hash: users[0].session_hash }, { fid: first, binding }), /Authentication/);
    await service.friendRequest(users[0].id);
    assert.deepEqual(sent.pop(), [second,binding]);
    const nextBinding = randomUUID();
    await service.register(users[1], { fid: second, binding: nextBinding });
    assert.equal((await pool.query('SELECT count(*) FROM notification_devices')).rows[0].count, '1');
    await service.friendRequest(users[0].id);
    assert.equal(sent.length, 0);
    await service.friendRequest(users[1].id);
    assert.deepEqual(sent.pop(), [second,nextBinding]);
    await pool.query('DELETE FROM sessions WHERE token_hash=$1', [users[1].session_hash]);
    assert.equal((await pool.query('SELECT count(*) FROM notification_devices')).rows[0].count, '0');
  } finally { await pool.end(); }
});

test('one-statement registration keeps session ownership and replaces old FIDs atomically', { skip: !process.env.DATABASE_URL }, async () => {
  const pool = new pg.Pool({ connectionString: process.env.DATABASE_URL });
  try {
    await pool.query(await readFile(new URL('../schema.sql', import.meta.url), 'utf8'));
    await pool.query('TRUNCATE users CASCADE');
    const users = (await pool.query(`INSERT INTO users(username_display,username_key,password_salt,password_hash)
      VALUES ('Atomicone','atomicone',$1,$2),('Atomictwo','atomictwo',$1,$2) RETURNING id`,
      [Buffer.alloc(16), Buffer.alloc(64)])).rows;
    for (const user of users) {
      user.session_hash = randomBytes(32);
      await pool.query("INSERT INTO sessions(token_hash,user_id,expires_at) VALUES ($1,$2,CURRENT_TIMESTAMP+INTERVAL '1 day')",
        [user.session_hash, user.id]);
    }
    let roundTrips = 0;
    const tracked = { query: (...args) => { roundTrips++; return pool.query(...args); } };
    const service = createNotifications(tracked, { pepper: 'atomic-registration-pepper', sender: async () => 'sent' });
    const a = randomBytes(16).toString('base64url');
    const b = randomBytes(16).toString('base64url');
    const binding = randomUUID();
    await service.register(users[0], { fid: a, binding });
    await service.register(users[0], { fid: b, binding });
    assert.equal(roundTrips, 2, 'Each registration requires one SQL statement including session verification');
    const rows = (await pool.query('SELECT session_hash,binding FROM notification_devices')).rows;
    assert.equal(rows.length, 1);
    assert.deepEqual(rows[0].session_hash, users[0].session_hash);
    assert.equal(rows[0].binding, binding);
    // An impersonating or expired session cannot wipe a legitimate registration.
    await assert.rejects(service.register({ ...users[1], session_hash: users[0].session_hash },
      { fid: a, binding }), /Authentication/);
    assert.equal((await pool.query('SELECT COUNT(*) FROM notification_devices')).rows[0].count, '1');
    await pool.query("UPDATE sessions SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE token_hash=$1",
      [users[0].session_hash]);
    await assert.rejects(service.register(users[0], { fid: a, binding }), /Authentication/);
    assert.equal((await pool.query('SELECT COUNT(*) FROM notification_devices')).rows[0].count, '1');
    await service.register(users[1], { fid: b, binding: randomUUID() });
    assert.equal(roundTrips, 5);
    assert.deepEqual((await pool.query('SELECT session_hash FROM notification_devices')).rows[0].session_hash,
      users[1].session_hash);
  } finally { await pool.end(); }
});

test('push delivery keeps event/device caps, expiry and no-device accounting in one query', {skip:!process.env.DATABASE_URL},async()=>{
  const pool=new pg.Pool({connectionString:process.env.DATABASE_URL});
  try{
    await pool.query(await readFile(new URL('../schema.sql',import.meta.url),'utf8'));
    await pool.query('TRUNCATE users,auth_rate_limits CASCADE');
    const users=(await pool.query(`INSERT INTO users(username_display,username_key,password_salt,password_hash)
      VALUES ('Gated','gated',$1,$2),('Empty','empty',$1,$2) RETURNING id`,[Buffer.alloc(16),Buffer.alloc(64)])).rows;
    const digest=id=>createHash('sha256').update(String(id)).digest();
    let queries=0;const sent=[];
    const tracked={connect:pool.connect.bind(pool),query:(...args)=>{queries++;return pool.query(...args);}};
    const service=createNotifications(tracked,{pepper:'gated-delivery-test-pepper',sender:async(token)=>{sent.push(token);return 'sent';},
      deliveryLimit:id=>['push-delivery',digest(id),300000,3]});
    const fids=[];
    for(let i=0;i<8;i++){
      const user={id:users[0].id,session_hash:randomBytes(32)},fid=randomBytes(16).toString('base64url');fids.push(fid);
      await pool.query("INSERT INTO sessions(token_hash,user_id,expires_at) VALUES ($1,$2,CURRENT_TIMESTAMP+INTERVAL '1 day')",[user.session_hash,user.id]);
      await service.register(user,{fid,binding:randomUUID()});
      if(i===6)await pool.query("UPDATE sessions SET created_at=CURRENT_TIMESTAMP-INTERVAL '2 days',expires_at=CURRENT_TIMESTAMP-INTERVAL '1 day' WHERE token_hash=$1",[user.session_hash]);
      if(i===7)await pool.query("UPDATE notification_devices SET updated_at=CURRENT_TIMESTAMP-INTERVAL '31 days' WHERE session_hash=$1",[user.session_hash]);
    }
    queries=0;
    for(let i=0;i<5;i++)await service.friendRequest(users[0].id);
    assert.equal(queries,5);assert.equal(sent.length,15); // Three events, at most five devices each.
    assert.equal(sent.some(fid=>fids.slice(6).includes(fid)),false);
    assert.equal((await pool.query("SELECT attempts FROM auth_rate_limits WHERE key_hash=$1",[digest(users[0].id)])).rows[0].attempts,4);
    await service.friendRequest(users[1].id);
    assert.equal((await pool.query("SELECT attempts FROM auth_rate_limits WHERE key_hash=$1",[digest(users[1].id)])).rows[0].attempts,1);
    assert.equal(sent.length,15); // No-device events still consume their existing reservation.
    await pool.query("UPDATE auth_rate_limits SET window_started_at=CURRENT_TIMESTAMP-INTERVAL '6 minutes'");
    await service.friendRequest(users[0].id);assert.equal(sent.length,20);
    // Removing consent or a session prevents that binding from being selected.
    await pool.query('DELETE FROM notification_devices');
    await service.friendRequest(users[0].id);assert.equal(sent.length,20);
  }finally{await pool.end();}
});

test('an invalid-token reply cannot delete a registration refreshed while delivery was in flight',{skip:!process.env.DATABASE_URL},async()=>{
  const pool=new pg.Pool({connectionString:process.env.DATABASE_URL});
  try{
    await pool.query(await readFile(new URL('../schema.sql',import.meta.url),'utf8'));
    await pool.query('TRUNCATE users CASCADE');
    const user=(await pool.query(`INSERT INTO users(username_display,username_key,password_salt,password_hash)
      VALUES ('Racing','racing',$1,$2) RETURNING id`,[Buffer.alloc(16),Buffer.alloc(64)])).rows[0];
    user.session_hash=randomBytes(32);
    await pool.query("INSERT INTO sessions(token_hash,user_id,expires_at) VALUES ($1,$2,CURRENT_TIMESTAMP+INTERVAL '1 day')",[user.session_hash,user.id]);
    const fid=randomBytes(16).toString('base64url'),binding=randomUUID();let refreshed=false;
    const service=createNotifications(pool,{pepper:'race-only-pepper',sender:async(token)=>{
      assert.equal(token,fid);
      if(!refreshed){refreshed=true;await service.register(user,{fid,binding});}
      return 'unregistered';
    }});
    await service.register(user,{fid,binding:randomUUID()});
    await service.friendRequest(user.id);
    assert.equal((await pool.query('SELECT binding FROM notification_devices')).rows[0].binding,binding);
    await service.friendRequest(user.id);
    assert.equal((await pool.query('SELECT COUNT(*) FROM notification_devices')).rows[0].count,'0');
  }finally{await pool.end();}
});
