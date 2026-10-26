import test from 'node:test';
import assert from 'node:assert/strict';
import { createCipheriv, createHmac, createHash, generateKeyPairSync, randomBytes, randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import pg from 'pg';
import { createFcmSender, createNotifications } from '../src/notifications.mjs';
import { createFriends } from '../src/friends.mjs';

test('notification decryption authenticates old ciphertext without copying its 100-byte plaintext', async () => {
  const pepper = 'local-notification-buffer-test-pepper';
  const key = createHmac('sha256', pepper).update('roadconquest-fcm-token-encryption-v1').digest();
  const rows = ['first-test-fid', 'second-test-fid', 'corrupt-test-fid'].map(token => {
    const iv = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', key, iv);
    cipher.setAAD(Buffer.from('roadconquest-fcm-token-v1'));
    return { encrypted_token: Buffer.concat([iv, cipher.update(token.padEnd(100, '\0'), 'utf8'), cipher.final(), cipher.getAuthTag()]), binding: token };
  });
  rows[2].encrypted_token[rows[2].encrypted_token.length - 1] ^= 1;
  const sent = [];
  const notifications = createNotifications({ async query() { return { rows }; } }, {
    pepper, sender: async (token, binding) => { sent.push({ token, binding }); return 'sent'; }
  });
  const concatenate = Buffer.concat;
  let plaintextCopies = 0;
  Buffer.concat = function (parts, ...args) {
    if (parts.length === 2 && parts[0].length === 100 && parts[1].length === 0) plaintextCopies++;
    return concatenate.call(this, parts, ...args);
  };
  try { await notifications.friendRequest('fixture-user'); }
  finally { Buffer.concat = concatenate; }
  assert.deepEqual(sent, ['first-test-fid', 'second-test-fid'].map(token => ({ token, binding: token })));
  assert.equal(plaintextCopies, 0);
});

test('rejected notification OAuth releases its unread body without delaying failure or caching it', async () => {
  const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const account = { project_id: 'roadconquest-9f0eb', client_email: 'cleanup@roadconquest-9f0eb.iam.gserviceaccount.com',
    private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }) };
  let auth = 0, cancelled = 0, sent = 0;
  const sender = createFcmSender(JSON.stringify(account), async url => {
    if (url === 'https://oauth2.googleapis.com/token') {
      auth++;
      if (auth <= 2) return { ok: false, body: { cancel() {
        cancelled++;
        return auth === 1 ? Promise.reject(new Error('cleanup failed')) : new Promise(() => {});
      } } };
      return Response.json({ access_token: 'recovered-token', expires_in: 3600 });
    }
    sent++;
    return Response.json({ name: 'test-message' });
  });
  for (let attempt = 1; attempt <= 2; attempt++) {
    await assert.rejects(sender('test', randomUUID()), /Notification authorization unavailable/);
    assert.equal(cancelled, attempt);
    assert.equal(sent, 0);
  }
  assert.equal(await sender('test', randomUUID()), 'sent');
  assert.equal(auth, 3);
  assert.equal(sent, 1);
});

test('a delayed old-token 401 preserves a newer OAuth refresh while a current-token 401 still expires it', async () => {
  const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const account = { project_id: 'roadconquest-9f0eb', client_email: 'race@roadconquest-9f0eb.iam.gserviceaccount.com',
    private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }) };
  let now=0,auth=0,releaseOld,started;
  const oldStarted=new Promise(resolve=>{started=resolve;});
  const sender=createFcmSender(JSON.stringify(account),async(url,init)=>{
    if(url==='https://oauth2.googleapis.com/token')return Response.json({access_token:'test-token-'+(++auth),expires_in:3600});
    const fid=JSON.parse(init.body).message.fid;
    if(fid==='slow'){
      assert.equal(init.headers.Authorization,'Bearer test-token-1');started();
      return new Promise(resolve=>{releaseOld=()=>resolve(Response.json({error:{}},{status:401}));});
    }
    assert.equal(init.headers.Authorization,'Bearer test-token-'+auth);
    return fid==='reject-current'?Response.json({error:{}},{status:401}):Response.json({name:'test-message'});
  },()=>now);
  const binding=randomUUID(),slow=sender('slow',binding);
  await oldStarted;
  now=3_600_000;
  assert.equal(await sender('fresh',binding),'sent');assert.equal(auth,2);
  releaseOld();assert.equal(await slow,'unavailable');
  assert.equal(await sender('reuse',binding),'sent');assert.equal(auth,2);
  assert.equal(await sender('reject-current',binding),'unavailable');
  assert.equal(await sender('refresh-again',binding),'sent');assert.equal(auth,3);
});

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
  assert.equal(await sender('test', binding, false, 'friend_post'), 'sent');
  assert.deepEqual(sent[2].message.data, { kind: 'friend_post', binding });
  assert.equal(sent[2].message.android.collapse_key, 'friend_post');
  assert.equal(createFcmSender(undefined), null);
  assert.throws(() => createFcmSender(JSON.stringify({ ...account, project_id: 'other' })), /Invalid/);
});

test('malformed push errors are unavailable and never treated as invalid registrations', async () => {
  const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const account = { project_id: 'roadconquest-9f0eb', client_email: 'errors@roadconquest-9f0eb.iam.gserviceaccount.com',
    private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }) };
  let error, auth = 0;
  const sender = createFcmSender(JSON.stringify(account), async url => {
    if (url === 'https://oauth2.googleapis.com/token') {
      auth++;
      return Response.json({ access_token: 'test-access', expires_in: 3600 });
    }
    return Response.json(error, { status: 503 });
  });
  for (const body of [null, {}, { error: { details: {} } }, { error: { details: [null, 1, {}] } }]) {
    error = body;
    assert.equal(await sender('test', randomUUID()), 'unavailable');
  }
  error = { error: { details: [null, { errorCode: 'UNREGISTERED' }] } };
  assert.equal(await sender('test', randomUUID()), 'unregistered');
  assert.equal(auth, 1, 'Malformed delivery errors do not discard a valid OAuth credential');
});

test('null and empty push OAuth replies do not cache credentials or send notifications', async () => {
  const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const account = { project_id: 'roadconquest-9f0eb', client_email: 'oauth@roadconquest-9f0eb.iam.gserviceaccount.com',
    private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }) };
  for (const invalid of [null, { access_token: '', expires_in: 3600 }]) {
    let auth = 0, sends = 0;
    const sender = createFcmSender(JSON.stringify(account), async url => {
      if (url === 'https://oauth2.googleapis.com/token') return Response.json(++auth === 1
        ? invalid : { access_token: 'recovered', expires_in: 3600 });
      sends++;
      return Response.json({ name: 'test-message' });
    });
    await assert.rejects(sender('test', randomUUID()), /Invalid notification authorization/);
    assert.equal(sends, 0);
    assert.equal(await sender('test', randomUUID()), 'sent');
    assert.equal(auth, 2);
  }
});

test('oversized push authorization is not cached and later authorization can recover', async () => {
  const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const account = { project_id: 'roadconquest-9f0eb', client_email: 'bounded@roadconquest-9f0eb.iam.gserviceaccount.com',
    private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }) };
  let auth = 0, sends = 0;
  const sender = createFcmSender(JSON.stringify(account), async url => {
    if (url === 'https://oauth2.googleapis.com/token') return Response.json({ access_token: 'access-' + (++auth),
      expires_in: 3600, ...(auth === 1 ? { padding: 'x'.repeat(32768) } : {}) });
    sends++;
    return Response.json({ name: 'test-message' });
  });
  await assert.rejects(sender('test', randomUUID()), /too large/);
  assert.equal(sends, 0);
  assert.equal(await sender('test', randomUUID()), 'sent');
  assert.equal(auth, 2);
  assert.equal(sends, 1);
});

test('oversized push error bodies stop before reads and cannot revoke registrations', async () => {
  const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const account = { project_id: 'roadconquest-9f0eb', client_email: 'bounded-errors@roadconquest-9f0eb.iam.gserviceaccount.com',
    private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }) };
  let reads = 0, cancelled = 0;
  const sender = createFcmSender(JSON.stringify(account), async url => {
    if (url === 'https://oauth2.googleapis.com/token') return Response.json({ access_token: 'access', expires_in: 3600 });
    return new Response(new ReadableStream({
      pull() { reads++; throw Error('Announced oversize must be rejected before download'); },
      cancel() { cancelled++; }
    }, { highWaterMark: 0 }), { status: 404, headers: { 'Content-Length': '32769' } });
  });
  assert.equal(await sender('test', randomUUID()), 'unavailable');
  assert.equal(reads, 0);
  assert.equal(cancelled, 1);
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

test('forum post alerts are sampled, consent aware, blocked and capped at one per recipient per seven days', { skip: !process.env.DATABASE_URL }, async () => {
  const pool = new pg.Pool({ connectionString: process.env.DATABASE_URL });
  try {
    await pool.query(await readFile(new URL('../schema.sql', import.meta.url), 'utf8'));
    await pool.query(await readFile(new URL('../forum-schema.sql', import.meta.url), 'utf8'));
    await pool.query('TRUNCATE users CASCADE');
    const users = (await pool.query(`INSERT INTO users(username_display,username_key,password_salt,password_hash)
      SELECT 'Weekly_'||g,'weekly_'||g,$1,$2 FROM generate_series(0,3) g ORDER BY g RETURNING id`,
      [Buffer.alloc(16), Buffer.alloc(64)])).rows;
    const [author, optedIn, optedOut, blocked] = users;
    for (const target of [optedIn, optedOut, blocked]) {
      const pair = [author.id, target.id].sort((a, b) => Number(a)-Number(b));
      await pool.query('INSERT INTO friendships(user_low,user_high,requested_by,accepted) VALUES($1,$2,$1,TRUE)', pair);
      const sessionHash = randomBytes(32), fid = randomBytes(16).toString('base64url');
      await pool.query("INSERT INTO sessions(token_hash,user_id,expires_at) VALUES($1,$2,CURRENT_TIMESTAMP+INTERVAL '1 day')",
        [sessionHash, target.id]);
      const service = createNotifications(pool, { pepper: 'weekly-friend-notification-pepper',
        sender: async () => 'sent', sampleForumPost: () => true });
      await service.register({ id: target.id, session_hash: sessionHash }, {
        fid, binding: randomUUID(), friend_requests: true, friend_posts: target !== optedOut
      });
    }
    await pool.query('INSERT INTO forum_blocks(user_id,blocked_id) VALUES($1,$2)', [blocked.id, author.id]);
    let selected = false; const sent = [];
    const service = createNotifications(pool, { pepper: 'weekly-friend-notification-pepper',
      sampleForumPost: () => selected, sender: async (fid,binding,validate,kind) => {
        sent.push({fid,binding,validate,kind}); return 'sent';
      } });
    await service.forumPost(author.id);
    assert.equal(sent.length, 0, 'Unselected posts do not cause delivery');
    selected = true;
    await service.forumPost(author.id);
    assert.equal(sent.length, 1, 'Only one opted-in, unblocked friend is eligible');
    assert.equal(sent[0].kind, 'friend_post');
    await Promise.all(Array.from({ length: 5 }, () => service.forumPost(author.id)));
    assert.equal(sent.length, 1, 'Concurrent and repeated posts retain the weekly limit');
    await pool.query("UPDATE forum_post_push SET last_sent_at=CURRENT_TIMESTAMP-INTERVAL '8 days'");
    await service.forumPost(author.id);
    assert.equal(sent.length, 2, 'Recipient can receive another alert after seven days');
    await pool.query('UPDATE notification_devices SET friend_posts_enabled=FALSE');
    await service.forumPost(author.id);
    assert.equal(sent.length, 2, 'Disabling consent stops further deliveries');
    await pool.query('DELETE FROM users WHERE id=$1',[optedIn.id]);
    assert.equal((await pool.query('SELECT COUNT(*) FROM forum_post_push')).rows[0].count, '0',
      'Account deletion erases the push throttle record');
  } finally { await pool.end(); }
});

test('accepted FCM sends do not wait for body cleanup or turn cleanup failures into delivery failures', { timeout: 2000 }, async () => {
  const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const account = { project_id: 'roadconquest-9f0eb', client_email: 'test@roadconquest-9f0eb.iam.gserviceaccount.com',
    private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }) };
  for (const fail of [true, false]) {
    let cleaned = false;
    const sender = createFcmSender(JSON.stringify(account), async url => url === 'https://oauth2.googleapis.com/token'
      ? Response.json({ access_token: 'access', expires_in: 3600 })
      : { status: 200, ok: true, body: { cancel() {
        cleaned = true;
        return fail ? Promise.reject(new Error('cleanup failed')) : new Promise(() => {});
      } } });
    assert.equal(await sender('test-fid', randomUUID()), 'sent');
    assert.equal(cleaned, true);
  }
});
