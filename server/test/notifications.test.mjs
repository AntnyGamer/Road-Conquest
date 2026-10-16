import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, randomBytes, randomUUID } from 'node:crypto';
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
