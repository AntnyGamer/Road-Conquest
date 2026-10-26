import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { createForum, moderationPenalty } from '../src/forum.mjs';
import { purchaseCodec } from '../src/forum-billing.mjs';
import { EvidenceError } from '../src/scoring.mjs';
import { forumDatabase } from './helpers/forum-database.mjs';

const database = Boolean(process.env.DATABASE_URL || process.env.FORUM_PGLITE_MODULE);
const options = { pepper: 'forum-test-pepper-'.repeat(4), moderate: async () => ({ flagged: false, model: 'test' }),
  verifyPurchase: async () => true, verifyWebhook: async () => true };
const body = (text = 'A discovery', gif_url = null) => ({ text, gif_url, request_id: randomUUID(), rules_version: 1 });
async function fixture(work) {
  const pool = await forumDatabase();
  try {
    await pool.query(await readFile(new URL('../schema.sql',import.meta.url),'utf8'));
    await pool.query(await readFile(new URL('../forum-schema.sql',import.meta.url),'utf8'));
    await pool.query('TRUNCATE users CASCADE');
    const users = (await pool.query(`INSERT INTO users(username_display,username_key,password_salt,password_hash)
      SELECT 'Forum_'||g,'forum_'||g,$1,$2 FROM generate_series(0,3) g ORDER BY g RETURNING id,username_display`,
      [Buffer.alloc(16),Buffer.alloc(64)])).rows;
    await work(pool,users,createForum(pool,{ ...options, adminIds: [users[3].id] }));
  } finally { await pool.end(); }
}
async function advance(pool,user) {
  await pool.query("UPDATE forum_profiles SET last_post_at=CURRENT_TIMESTAMP - INTERVAL '11 minutes' WHERE user_id=$1",[user.id]);
}

test('forum penalties are deletion, seven days, then a capped forum ban', () => {
  assert.deepEqual(moderationPenalty(0,0),{ violations:1,banned:false,muted_until:null });
  assert.equal(moderationPenalty(1,0).muted_until,'1970-01-08T00:00:00.000Z');
  assert.deepEqual(moderationPenalty(2,0),{ violations:3,banned:true,muted_until:null });
  assert.equal(moderationPenalty(3).violations,3);
});

test('malformed purchase tokens fail before any database or Play work', async () => {
  const unexpected = () => { throw Error('Invalid tokens must not start work'); };
  const forum = createForum({ query: unexpected, connect: unexpected }, { ...options, verifyPurchase: unexpected });
  for (const token of [null, 42, '', 'x'.repeat(4097), 'receipt\n', 'receipt\r']) {
    await assert.rejects(forum.purchase({ id: 1 }, token), error => error.status === 400);
  }
});

test('manual billing delivery tests log only authenticated notifications for this app without querying purchases', async () => {
  const logs = [], originalInfo = console.info;
  console.info = message => logs.push(message);
  const forum = createForum({ query() { throw Error('Test notifications must not query the database'); } },
    { ...options, verifyWebhook: async authorization => authorization === 'google-verified',
      verifyPurchase: async () => { throw Error('Test notifications must not query Play purchases'); } });
  const notification = packageName => ({ message: { messageId: 'manual-test', data:
    Buffer.from(JSON.stringify({ packageName, testNotification: { version: '1.0' } })).toString('base64') } });
  try {
    await assert.rejects(forum.webhook('fake', notification('com.roadconquest.app')), error => error.status === 401);
    await assert.rejects(forum.webhook('google-verified', notification('wrong.app')), error => error.status === 400);
    assert.deepEqual(logs, []);
    assert.deepEqual(await forum.webhook('google-verified', notification('com.roadconquest.app')), { ok: true });
    assert.deepEqual(logs, ['Forum Plus authenticated test notification received.']);
  } finally { console.info = originalInfo; }
});

test('public paging, reactions, blocks, privacy and account deletion', { skip: !database }, async () => fixture(async (pool,[a,b,c,admin],forum) => {
  assert.deepEqual((await forum.list(null)).posts,[]);
  const first = await forum.post(a,body('Beautiful coastal drive'));
  await forum.react(b,first.id,'love');
  await forum.react(b,first.id,'love');
  let page = await forum.list(b);
  assert.equal(page.posts[0].reactions.love,1);
  assert.equal(page.posts[0].my_reaction,'love');
  assert.equal(page.posts[0].username,a.username_display);
  assert.equal(page.posts[0].user_id,undefined);
  assert.equal(page.posts[0].moderation,undefined);
  await forum.react(b,first.id,null);
  assert.equal((await forum.list(b)).posts[0].reactions.love,0);
  await forum.block(b,a.username_display,true);
  assert.equal((await forum.list(b)).posts.length,0);
  assert.deepEqual((await forum.blocks(b)).accounts,[a.username_display]);
  await assert.rejects(forum.react(b,first.id,'like'),/unavailable/);
  await forum.block(b,a.username_display,false);
  await forum.report(b,first.id,'spam'); await forum.report(b,first.id,'hate');
  assert.equal(Number((await forum.reports(admin)).posts[0].reports),1);
  await assert.rejects(forum.reports(b),/Moderator/);
  for (let i=0;i<22;i++) { await advance(pool,a); await forum.post(a,body('Discovery '+i)); }
  page = await forum.list(null);
  assert.equal(page.posts.length,20); assert.ok(page.next);
  const next = await forum.list(null,page.next);
  assert.equal(next.posts.length,3);
  assert.equal(new Set([...page.posts,...next.posts].map(p=>p.id)).size,23);
  await assert.rejects(forum.list(null,'garbage'),/Invalid/);
  await forum.remove(b,first.id);
  assert.ok((await forum.list(null,page.next)).posts.some(p=>p.id===first.id));
  await forum.remove(a,first.id);
  assert.equal((await pool.query('SELECT body,gif_url FROM forum_posts WHERE id=$1',[first.id])).rows[0].body,'');
  await pool.query('DELETE FROM users WHERE id=$1',[a.id]);
  assert.equal((await forum.list(null)).posts.length,0);
  assert.equal((await pool.query('SELECT * FROM forum_reports')).rowCount,0);
}));

test('feed lookahead does not transfer an extra post and preserves tied cursors, blocks and reactions', { skip: !database }, async () => fixture(async (pool,[a,b,c]) => {
  const id = n => '00000000-0000-4000-8000-' + String(n).padStart(12,'0');
  await pool.query(`INSERT INTO forum_profiles(user_id,plus_active) VALUES($1,TRUE),($2,FALSE)`, [a.id,c.id]);
  await pool.query(`INSERT INTO forum_posts(id,user_id,request_id,content_hash,body,state,created_at)
    SELECT ('00000000-0000-4000-8000-'||lpad(g::text,12,'0'))::uuid,
      CASE WHEN g IN (26,27) THEN $2::bigint ELSE $1::bigint END,
      ('00000000-0000-4000-8000-'||lpad(g::text,12,'0'))::uuid,repeat('a',64),repeat('x',1190)||g,
      CASE WHEN g=28 THEN 'removed' WHEN g=29 THEN 'deleted' ELSE 'visible' END,
      '2026-01-01T00:00:00Z'::timestamptz
    FROM generate_series(1,29) g`, [a.id,b.id]);
  await pool.query('INSERT INTO forum_blocks(user_id,blocked_id) VALUES($1,$2)', [c.id,b.id]);
  await pool.query(`INSERT INTO forum_reactions(post_id,user_id,reaction)
    SELECT id,$1,'like' FROM forum_posts WHERE user_id=$2 AND state='visible'`, [c.id,a.id]);
  await pool.query("INSERT INTO forum_reactions(post_id,user_id,reaction) VALUES($1,$2,'love')", [id(5),b.id]);
  const transferred = [];
  const forum = createForum({ query: async (...args) => {
    const result = await pool.query(...args);
    // Inspect the database boundary before the service can trim its response.
    transferred.push(result.rows[0].posts.map(post => post.id));
    return result;
  } }, options);
  const first = await forum.list(c);
  assert.deepEqual(first.posts.map(p=>p.id), Array.from({length:20},(_,i)=>id(25-i)));
  assert.deepEqual(transferred[0], first.posts.map(p=>p.id));
  assert.ok(first.next);
  assert.ok(first.posts.every(p=>p.highlighted && p.my_reaction==='like' && p.reactions.like===1 && !p.own));
  assert.equal(first.viewer.username,c.username_display);
  const last = await forum.list(c,first.next);
  assert.deepEqual(last.posts.map(p=>p.id), [5,4,3,2,1].map(id));
  assert.deepEqual(transferred[1], last.posts.map(p=>p.id));
  assert.equal(last.posts[0].reactions.love,1);
  assert.equal(last.next,null);
  await pool.query('DELETE FROM users WHERE id=$1',[a.id]);
  const empty = await forum.list(c,first.next);
  assert.deepEqual(empty.posts,[]);
  assert.equal(empty.next,null);
}));

test('ten-minute cooldown survives service restart, deletes and concurrent submissions; retries are free', { skip: !database }, async () => fixture(async (pool,[a]) => {
  let calls=0;
  const make = () => createForum(pool,{ ...options, moderate: async () => { calls++; return { flagged:false }; } });
  const forum=make(), input=body();
  const result=await forum.post(a,input);
  assert.ok(new Date(result.next_post_at).getTime()>Date.now()+590_000);
  assert.equal((await make().post(a,input)).id,result.id);
  assert.equal(calls,1);
  await assert.rejects(make().post(a,body()),error=>error.status===429);
  assert.equal(calls,1,'Cooldown rejection must not call the moderation API');
  await forum.remove(a,result.id);
  await assert.rejects(forum.post(a,body()),error=>error.status===429);
  await advance(pool,a);
  const raced=await Promise.allSettled([make().post(a,body('One')),make().post(a,body('Two'))]);
  assert.equal(raced.filter(r=>r.status==='fulfilled').length,1);
  assert.equal(raced.find(r=>r.status==='rejected').reason.status,429);
  await assert.rejects(forum.post(a,{...input,text:'Changed'}),error=>error.status===409);
}));

test('moderation is idempotent, outages do not punish, and mute/ban affect only forum participation', { skip: !database }, async () => fixture(async (pool,[a,b,c,admin]) => {
  const fail=createForum(pool,{ ...options, moderate:async()=>{throw new Error('outage');} });
  await assert.rejects(fail.post(a,body()),/outage/);
  assert.equal((await fail.list(a)).viewer.violations,0);
  assert.equal((await fail.list(a)).viewer.next_post_at,null);
  const forum=createForum(pool,{...options,adminIds:[admin.id],moderate:async()=>({flagged:true,categories:['hate']})});
  const input=body('Removed content');
  const retries=await Promise.all(Array.from({length:12},()=>forum.post(a,input)));
  assert.equal(new Set(retries.map(p=>p.id)).size,1);
  assert.ok(retries.every(p=>p.violations===1));
  assert.equal((await forum.list(null)).posts.length,0);
  assert.equal((await pool.query('SELECT body,gif_url FROM forum_posts')).rows[0].body,'');
  await advance(pool,a);
  const second=await forum.post(a,body('Another violation'));
  assert.equal(second.violations,2);
  assert.ok(new Date(second.muted_until).getTime()>Date.now()+6.99*86400000);
  await assert.rejects(forum.post(a,body()),error=>error.status===403);
  await assert.rejects(forum.react(a,randomUUID(),'like'),error=>error.status===403);
  await pool.query("UPDATE forum_profiles SET muted_until=CURRENT_TIMESTAMP-INTERVAL '1 day',last_post_at=NULL WHERE user_id=$1",[a.id]);
  assert.equal((await forum.post(a,body('Third violation'))).banned,true);
  assert.equal((await forum.list(a)).purchases_available,false);
  assert.ok((await pool.query('SELECT * FROM users WHERE id=$1',[a.id])).rowCount);
  await assert.rejects(forum.post(a,body()),/banned/);
  await forum.appealReview(admin,a.username_display);
  assert.equal((await forum.list(a)).viewer.banned,false);
  const disabled=createForum(pool,{ ...options,moderate:null,verifyWebhook:null });
  assert.equal((await disabled.list(a)).posting_available,false);
  assert.equal((await disabled.list(a)).purchases_available,false);
  assert.equal((await createForum(pool,{...options,moderate:null}).list(b)).purchases_available,false,
    'Do not offer paid posting extras before moderation is configured');
  await assert.rejects(createForum(pool,{...options,moderate:null}).purchase(b,'receipt'),e=>e.status===503);
  await assert.rejects(disabled.post(b,body()),error=>error.status===503);
}));

test('manual removal strikes once, reports alone never strike, Plus grants require verified ownership and refunds revoke', { skip: !database }, async () => fixture(async (pool,[a,b,c,admin],forum) => {
  const post=await forum.post(a,body());
  await forum.report(b,post.id,'spam');
  assert.equal((await forum.list(a)).viewer.violations,0);
  await Promise.all([forum.review(admin,post.id,true),forum.review(admin,post.id,true)]);
  assert.equal((await forum.list(a)).viewer.violations,1);
  await assert.rejects(forum.post(b,body('', 'https://media.giphy.com/test.gif')),/Plus/);
  let active=true,checks=0;
  const shop=createForum(pool,{...options,verifyPurchase:async(token,binding)=>{checks++; assert.equal(binding.length,64); return active;}});
  assert.deepEqual(await shop.purchase(a,'receipt'),{plus:true});
  assert.equal((await shop.list(a)).viewer.plus,true);
  await assert.rejects(shop.purchase(b,'receipt'),/another account/);
  assert.equal(checks,1);
  await pool.query('UPDATE forum_profiles SET banned=TRUE WHERE user_id=$1',[b.id]);
  await assert.rejects(shop.purchase(b,'new-receipt'),e=>e.status===403);
  assert.equal(checks,1,'New banned-account purchases must be rejected before contacting Play');
  await advance(pool,a);
  const highlighted=await shop.post(a,body());
  assert.equal((await shop.list(null)).posts.find(p=>p.id===highlighted.id).highlighted,true);
  await pool.query('UPDATE forum_profiles SET banned=TRUE WHERE user_id=$1',[a.id]);
  active=false;
  const notification = {message:{messageId:'refund-1',data:Buffer.from(JSON.stringify({packageName:'com.roadconquest.app',
    voidedPurchaseNotification:{productType:2,purchaseToken:'receipt'}})).toString('base64')}};
  await shop.webhook('verified',notification);
  assert.equal((await shop.list(a)).viewer.plus,false);
  await shop.webhook('verified',notification);
  assert.equal(checks,2,'Duplicate notification must not consume another Play API request');
  const unauth=createForum(pool,{...options,verifyWebhook:async()=>false,prepare:async()=>{throw Error('must not wake database');}});
  await assert.rejects(unauth.webhook('fake',notification),error=>error.status===401);
  await pool.query('DELETE FROM users WHERE id=$1',[a.id]);
  assert.equal((await pool.query('SELECT * FROM forum_purchases')).rowCount,0);
}));

test('purchase verification outages preserve existing paid access and receipt state', { skip: !database }, async () => fixture(async(pool,[a])=>{
  let outage=false;
  const forum=createForum(pool,{...options,verifyPurchase:async()=>{
    if(outage)throw new EvidenceError('Purchase verification is temporarily unavailable.',503);
    return true;
  }});
  await forum.purchase(a,'receipt');
  const profileBefore=(await pool.query('SELECT plus_active,xmin::text AS version FROM forum_profiles WHERE user_id=$1',[a.id])).rows[0];
  assert.deepEqual(await createForum(pool,{...options,moderate:null}).purchase(a,'receipt'),{plus:true},
    'Temporarily disabled posting must still allow verification of an existing purchase');
  const profileAfter=(await pool.query('SELECT plus_active,xmin::text AS version FROM forum_profiles WHERE user_id=$1',[a.id])).rows[0];
  assert.deepEqual(profileAfter,profileBefore,'Repeated verified purchases do not rewrite an unchanged entitlement profile');
  const refreshed=(await pool.query('SELECT active,checked_at FROM forum_purchases')).rows[0];
  outage=true;
  await assert.rejects(forum.purchase(a,'receipt'),e=>e.status===503);
  assert.equal((await forum.list(a)).viewer.plus,true);
  assert.deepEqual((await pool.query('SELECT active,checked_at FROM forum_purchases')).rows[0],refreshed);
}));

test('repeat purchase checks retain encrypted receipts without resending them, including refund and restore',
  { skip: !database }, async () => fixture(async (pool, [a]) => {
    await pool.query('INSERT INTO forum_profiles(user_id) VALUES($1)', [a.id]);
    let binaryBytes = 0, statements = 0, preflightReads = 0, checks = 0, active = true;
    const token = 'r'.repeat(4096);
    const measured = {
      query: (...args) => { preflightReads++; return pool.query(...args); },
      async connect() {
        const db = await pool.connect();
        return { query: async (sql, parameters) => {
          statements++;
          for (const value of parameters || []) if (Buffer.isBuffer(value)) binaryBytes += value.length;
          return db.query(sql, parameters);
        }, release: () => db.release() };
      }
    };
    const forum = createForum(measured, { ...options, verifyPurchase: async value => {
      checks++;
      assert.equal(value, token);
      return active;
    } });
    assert.deepEqual(await forum.purchase(a, token), { plus: true });
    const receipt = async () => (await pool.query('SELECT encrypted_token,active,checked_at FROM forum_purchases')).rows[0];
    const original = await receipt();
    assert.equal(purchaseCodec(options.pepper).open(original.encrypted_token), token);
    assert.equal(binaryBytes, 4124, 'Only the first check sends the encrypted token and its nonce/tag');
    assert.equal(statements, 5);
    assert.equal(preflightReads, 1, 'Profile authorization and receipt versions share one read');
    for (const state of [true, false, true]) {
      active = state;
      await pool.query("UPDATE forum_purchases SET checked_at='2000-01-01T00:00:00Z'");
      binaryBytes = statements = preflightReads = 0;
      assert.deepEqual(await forum.purchase(a, token), { plus: state });
      const updated = await receipt();
      assert.deepEqual(updated.encrypted_token, original.encrypted_token);
      assert.equal(updated.active, state);
      assert.ok(new Date(updated.checked_at).getTime() > Date.parse('2000-01-01T00:00:00Z'));
      assert.equal(binaryBytes, 0, 'Stored receipts need no replacement ciphertext parameter');
      assert.equal(statements, 5, 'The apply transaction keeps five statements, separate from the read-only preflight');
      assert.equal(preflightReads, 1);
    }
    assert.equal(checks, 4, 'Every request still verifies the current state with Play');
  }));

test('slow Play checks hold no database connections or account locks', { skip: !database }, async () =>
  fixture(async (pool, [a]) => {
    await pool.query('INSERT INTO forum_profiles(user_id) VALUES($1)', [a.id]);
    let held = 0, started = 0;
    const ready = Promise.withResolvers(), gate = Promise.withResolvers();
    const measured = { query: pool.query.bind(pool), async connect() {
      const db = await pool.connect();
      held++;
      return { query: db.query.bind(db), release() { held--; db.release(); } };
    } };
    const forum = createForum(measured, { ...options, verifyPurchase: async () => {
      // Initial checks all pause together. A later conflict retry can overlap
      // another request's short transaction, but never holds its own client.
      if (started < 5) assert.equal(held, 0, 'No transaction client may be held during the external request');
      if (++started === 5) ready.resolve();
      await gate.promise;
      return true;
    } });
    const purchases = Promise.allSettled(Array.from({ length: 5 }, (_, i) => forum.purchase(a, 'receipt-' + i)));
    try {
      await Promise.race([ready.promise, purchases.then(() => { throw Error('Purchase checks did not all reach Google'); })]);
      // This must complete while all Google responses are still suspended.
      const post = await forum.post(a, body());
      assert.equal(post.removed, false);
      assert.equal(held, 0);
    } finally { gate.resolve(); }
    assert.ok((await purchases).every(result => result.status === 'fulfilled' && result.value.plus));
    assert.equal((await pool.query('SELECT token_hash FROM forum_purchases')).rowCount, 5);
  }));

for (const [initial, changed] of [[true, false], [false, true]]) {
  test(`a delayed ${initial ? 'purchase' : 'refund'} verdict cannot overwrite a newer ${changed ? 'restore' : 'refund'}`,
    { skip: !database }, async () => fixture(async (pool, [a], setup) => {
      await setup.purchase(a, 'receipt');
      if (!initial) await createForum(pool, { ...options, verifyPurchase: async () => false }).purchase(a, 'receipt');
      let checks = 0;
      const started = Promise.withResolvers(), gate = Promise.withResolvers();
      const delayed = createForum(pool, { ...options, verifyPurchase: async () => {
        if (++checks === 1) { started.resolve(); await gate.promise; return initial; }
        return changed;
      } });
      const pending = delayed.purchase(a, 'receipt');
      try {
        await started.promise;
        // A different service instance handles the newer authenticated event.
        const current = createForum(pool, { ...options, verifyPurchase: async () => changed });
        await current.webhook('verified', billingNotification(randomUUID(), 'receipt', changed ? 'purchase' : 'refund'));
      } finally { gate.resolve(); }
      assert.deepEqual(await pending, { plus: changed });
      assert.equal(checks, 2, 'The stale verdict must be discarded and verified again after releasing the lock');
      assert.equal((await setup.list(a)).viewer.plus, changed);
    }));
}

for (const existingProfile of [false, true]) {
  test(`a delayed first purchase cannot overwrite a newer inactive check with ${existingProfile ? 'an existing' : 'a new'} profile`,
    { skip: !database }, async () => fixture(async (pool, [a], setup) => {
      if (existingProfile) await pool.query('INSERT INTO forum_profiles(user_id) VALUES($1)', [a.id]);
      let checks = 0;
      const started = Promise.withResolvers(), gate = Promise.withResolvers();
      const delayed = createForum(pool, { ...options, verifyPurchase: async () => {
        if (++checks === 1) { started.resolve(); await gate.promise; return true; }
        return false;
      } });
      const pending = delayed.purchase(a, 'first-receipt');
      try {
        await started.promise;
        assert.deepEqual(await createForum(pool, { ...options, verifyPurchase: async () => false })
          .purchase(a, 'first-receipt'), { plus: false });
      } finally { gate.resolve(); }
      assert.deepEqual(await pending, { plus: false });
      assert.equal(checks, 2);
      assert.equal((await setup.list(a)).viewer.plus, false);
      assert.equal((await pool.query('SELECT token_hash FROM forum_purchases')).rowCount, 0,
        'An unverified or inactive first receipt must not reserve an owner');
    }));
}

test('new-purchase account restrictions are rechecked after Play responds', { skip: !database }, async () =>
  fixture(async (pool, [a]) => {
    await pool.query('INSERT INTO forum_profiles(user_id) VALUES($1)', [a.id]);
    const started = Promise.withResolvers(), gate = Promise.withResolvers();
    const forum = createForum(pool, { ...options, verifyPurchase: async () => {
      started.resolve(); await gate.promise; return true;
    } });
    const pending = assert.rejects(forum.purchase(a, 'receipt'), error => error.status === 403);
    try {
      await started.promise;
      await pool.query('UPDATE forum_profiles SET banned=TRUE WHERE user_id=$1', [a.id]);
    } finally { gate.resolve(); }
    await pending;
    assert.equal((await pool.query('SELECT token_hash FROM forum_purchases')).rowCount, 0);
    assert.equal((await forum.list(a)).viewer.plus, false);
  }));

test('repeated receipt changes bound retries and preserve the last committed entitlement', { skip: !database }, async () =>
  fixture(async (pool, [a], setup) => {
    await setup.purchase(a, 'receipt');
    let checks = 0;
    const refund = createForum(pool, { ...options, verifyPurchase: async () => false });
    const conflicted = createForum(pool, { ...options, verifyPurchase: async () => {
      checks++;
      await refund.purchase(a, 'receipt');
      return true;
    } });
    await assert.rejects(conflicted.purchase(a, 'receipt'), error => error.status === 503);
    assert.equal(checks, 3);
    assert.equal((await setup.list(a)).viewer.plus, false);
    assert.deepEqual(await setup.purchase(a, 'receipt'), { plus: true }, 'A later independent restore remains retryable');
  }));

test('account deletion during Play verification cannot recreate forum data', { skip: !database }, async () =>
  fixture(async (pool, [a], setup) => {
    await setup.purchase(a, 'receipt');
    const started = Promise.withResolvers(), gate = Promise.withResolvers();
    const forum = createForum(pool, { ...options, verifyPurchase: async () => {
      started.resolve(); await gate.promise; return true;
    } });
    const pending = assert.rejects(forum.purchase(a, 'receipt'), error => error.status === 401);
    try {
      await started.promise;
      await pool.query('DELETE FROM users WHERE id=$1', [a.id]);
    } finally { gate.resolve(); }
    await pending;
    assert.equal((await pool.query('SELECT user_id FROM forum_profiles WHERE user_id=$1', [a.id])).rowCount, 0);
    assert.equal((await pool.query('SELECT user_id FROM forum_purchases WHERE user_id=$1', [a.id])).rowCount, 0);
  }));

test('overlapping first claims cannot grant a receipt to two accounts', { skip: !database }, async () =>
  fixture(async (pool, [a, b], setup) => {
    const started = Promise.withResolvers(), gate = Promise.withResolvers();
    let checks = 0;
    const verifier = async () => { if (++checks === 2) started.resolve(); await gate.promise; return true; };
    const first = createForum(pool, { ...options, verifyPurchase: verifier });
    const second = createForum(pool, { ...options, verifyPurchase: verifier });
    const pending = Promise.allSettled([first.purchase(a, 'receipt'), second.purchase(b, 'receipt')]);
    await started.promise;
    gate.resolve();
    const results = await pending;
    assert.equal(results.filter(r => r.status === 'fulfilled' && r.value.plus).length, 1);
    assert.equal(results.filter(r => r.status === 'rejected' && r.reason.status === 403).length, 1);
    const profiles = await pool.query('SELECT user_id FROM forum_profiles WHERE plus_active');
    assert.equal(profiles.rowCount, 1);
    assert.equal((await setup.list({ id: profiles.rows[0].user_id })).viewer.plus, true);
  }));

test('a cross-account insert after the locked lookup cannot grant an unowned receipt',
  { skip: !process.env.DATABASE_URL }, async () => fixture(async (pool, [a, b], setup) => {
    let inserted = false;
    const raced = { query: pool.query.bind(pool), async connect() {
      const db = await pool.connect();
      return { async query(sql, parameters) {
        const result = await db.query(sql, parameters);
        if (!inserted && sql.startsWith('SELECT user_id,xmin::text') && !result.rowCount) {
          inserted = true;
          // The caller holds only a's profile lock. b can insert this token on
          // another connection before a reaches INSERT ... ON CONFLICT.
          await setup.purchase(b, 'receipt');
        }
        return result;
      }, release: () => db.release() };
    } };
    await assert.rejects(createForum(raced, options).purchase(a, 'receipt'), error => error.status === 403);
    assert.equal(inserted, true);
    assert.equal((await setup.list(a)).viewer.plus, false);
    assert.equal((await setup.list(b)).viewer.plus, true);
    assert.equal((await pool.query('SELECT user_id FROM forum_purchases')).rows[0].user_id, b.id);
  }));

test('existing profiles skip redundant insert and billing notifications still prune old IDs', { skip: !database }, async () => fixture(async (pool,[a],forum) => {
  const input=body('A first post');
  const first=await forum.post(a,input);
  const query=pool.query.bind(pool);
  let profileInserts=0, billingWrites=0, webhookLookups=0;
  pool.query=(sql,...args)=>{
    if (typeof sql==='string' && sql.includes('INSERT INTO forum_profiles')) profileInserts++;
    if (typeof sql==='string' && sql.includes('forum_billing_events') && sql.includes('DELETE')) billingWrites++;
    if (typeof sql==='string' && sql.includes('forum_billing_events') && sql.includes('forum_purchases')
      && !sql.includes('INSERT INTO forum_billing_events')) webhookLookups++;
    return query(sql,...args);
  };
  try {
    assert.equal((await forum.post(a,input)).id,first.id);
    assert.equal(profileInserts,0,'Already-created profiles do not issue an INSERT on retry');
    await query("INSERT INTO forum_billing_events(id,created_at) VALUES('expired-id',CURRENT_TIMESTAMP-INTERVAL '31 days')");
    const notification={ message:{ messageId:'fresh-id', data:Buffer.from(JSON.stringify({
      packageName:'com.roadconquest.app',oneTimeProductNotification:{
        sku:'forum_plus_lifetime',purchaseToken:'unrecognized-receipt'
      }
    })).toString('base64') } };
    assert.deepEqual(await forum.webhook('verified',notification),{ok:true});
    assert.equal(webhookLookups,1,'Unseen events check deduplication and purchase ownership in one query');
    assert.equal(billingWrites,1,'Event insert and cleanup happen in one query');
    assert.deepEqual((await query("SELECT id FROM forum_billing_events WHERE id IN ('expired-id','fresh-id') ORDER BY id")).rows.map(x=>x.id),['fresh-id']);
    await forum.webhook('verified',notification);
    assert.equal(webhookLookups,2,'Duplicate events need only one lookup without checking ownership separately');
    assert.equal(billingWrites,1,'Duplicate events skip maintenance and Play verification');
  } finally { pool.query=query; }
}));

test('moderation report batches fit the Android response bound and advance after review', { skip: !database }, async () => fixture(async(pool,[a,b,c,admin])=>{
  const forum=createForum(pool,{...options,adminIds:[admin.id],moderate:async()=>({flagged:false,gifHash:'a'.repeat(64)})});
  await forum.purchase(a,'receipt');
  const text='\u2615'.repeat(1200),url='https://media.giphy.com/'+'a'.repeat(970)+'.gif';
  for(let i=0;i<50;i++) {
    await advance(pool,a);
    const post=await forum.post(a,body(text,url));
    await forum.report(b,post.id,'other');
  }
  const reviewed=new Set();
  for(const expected of [20,20,10]) {
    const batch=await forum.reports(admin);
    assert.equal(batch.posts.length,expected);
    assert.ok(Buffer.byteLength(JSON.stringify(batch))<192*1024,'Every batch must fit AccountClient.forumRequest');
    for(const post of batch.posts) {
      assert.ok(!reviewed.has(post.id)); reviewed.add(post.id);
      await forum.review(admin,post.id,false);
    }
  }
  assert.equal((await forum.reports(admin)).posts.length,0);
}));

test('friend post alerts follow only newly committed visible posts and never duplicate retries', { skip: !database }, async () =>
  fixture(async (_pool, [author]) => {
    let sent = 0;
    const forum = createForum(_pool, { ...options, friendPosted: async () => { sent++; } });
    const input = body('Welcome to the community');
    const published = await forum.post(author, input);
    assert.equal(published.removed, false);
    assert.equal(sent, 1);
    assert.equal((await forum.post(author, input)).id, published.id);
    assert.equal(sent, 1, 'Client retries never resend the notification');

    await advance(_pool, author);
    const rejected = createForum(_pool, { ...options,
      moderate: async () => ({ flagged: true, categories: ['spam'] }),
      friendPosted: async () => { sent++; } });
    assert.equal((await rejected.post(author, body('Spam content'))).removed, true);
    assert.equal(sent, 1, 'Moderated-away posts never notify friends');

    await advance(_pool, author);
    const unavailable = createForum(_pool, { ...options,
      moderate: async () => { throw new Error('moderation outage'); },
      friendPosted: async () => { sent++; } });
    await assert.rejects(unavailable.post(author, body('Temporary failure')), /outage/);
    assert.equal(sent, 1);
  }));

const billingNotification = (id, token = 'receipt', type = 'purchase') => ({ message: {
  messageId: id, data: Buffer.from(JSON.stringify({ packageName: 'com.roadconquest.app',
    ...(type === 'purchase' ? { oneTimeProductNotification: {
      sku: 'forum_plus_lifetime', purchaseToken: token
    } } : { voidedPurchaseNotification: { productType: 2, purchaseToken: token } })
  })).toString('base64')
} });

test('overlapping identical billing deliveries share one lookup and Play check, while every caller authenticates',
  { skip: !database }, async () => fixture(async (pool, [a], setup) => {
    await setup.purchase(a, 'receipt');
    let checks = 0, identities = 0, lookups = 0;
    const started = Promise.withResolvers(), gate = Promise.withResolvers();
    const query = pool.query.bind(pool);
    pool.query = (sql, ...args) => {
      if (sql.includes('AS processed')) lookups++;
      return query(sql, ...args);
    };
    const forum = createForum(pool, { ...options,
      verifyWebhook: async auth => { identities++; return auth === 'verified'; },
      verifyPurchase: async () => { checks++; started.resolve(); await gate.promise; return true; }
    });
    const notification = billingNotification('same-event');
    const deliveries = Array.from({ length: 16 }, () => forum.webhook('verified', notification));
    await started.promise;
    try {
      await assert.rejects(forum.webhook('fake', notification), e => e.status === 401);
      assert.equal(checks, 1);
      assert.equal(lookups, 1);
    } finally { gate.resolve(); }
    assert.ok((await Promise.all(deliveries)).every(r => r.ok));
    assert.equal(identities, 17, 'Shared processing never shares the authorization decision');
    assert.equal(checks, 1, 'Sixteen overlapping copies require only one Google purchase check');
    assert.equal((await query("SELECT id FROM forum_billing_events WHERE id='same-event'")).rowCount, 1);
    await forum.webhook('verified', notification);
    assert.equal(lookups, 2, 'A later delivery must check the durable receipt again');
    assert.equal(checks, 1);
  }));

test('failed billing work remains retryable and distinct refund events always recheck current ownership',
  { skip: !database }, async () => fixture(async (pool, [a], setup) => {
    await setup.purchase(a, 'receipt');
    let checks = 0, active = true;
    const started = Promise.withResolvers(), gate = Promise.withResolvers();
    const forum = createForum(pool, { ...options, verifyPurchase: async () => {
      checks++;
      if (checks === 1) { started.resolve(); await gate.promise; throw new EvidenceError('Play outage', 503); }
      return active;
    } });
    const notification = billingNotification('retry-event');
    const deliveries = Promise.allSettled(Array.from({ length: 8 }, () => forum.webhook('verified', notification)));
    await started.promise;
    gate.resolve();
    assert.ok((await deliveries).every(r => r.status === 'rejected' && r.reason.status === 503));
    assert.equal(checks, 1);
    assert.equal((await pool.query("SELECT id FROM forum_billing_events WHERE id='retry-event'")).rowCount, 0);
    await forum.webhook('verified', notification);
    assert.equal(checks, 2);
    active = false;
    await forum.webhook('verified', billingNotification('refund-event', 'receipt', 'refund'));
    assert.equal(checks, 3, 'A new event for the same token cannot reuse an old purchase verdict');
    assert.equal((await forum.list(a)).viewer.plus, false);
    active = true;
    await forum.webhook('verified', billingNotification('restore-event'));
    assert.equal(checks, 4);
    assert.equal((await forum.list(a)).viewer.plus, true);
  }));

test('billing coalescing is bounded, does not combine different payloads, and keeps overflow processing', async () => {
  const started = Promise.withResolvers(), gate = Promise.withResolvers();
  let lookups = 0;
  const pool = { query: async sql => {
    if (sql.includes('AS processed')) {
      lookups++;
      if (lookups === 34) started.resolve();
      await gate.promise;
      return { rows: [{ processed: false, user_id: null }] };
    }
    return { rows: [] };
  } };
  const forum = createForum(pool, options);
  const deliveries = Array.from({ length: 32 }, (_, i) => forum.webhook('verified', billingNotification('event-' + i)));
  // Same ID but changed signed content is independent, even at capacity.
  deliveries.push(forum.webhook('verified', billingNotification('event-0', 'different-receipt')));
  deliveries.push(forum.webhook('verified', billingNotification('event-0', 'different-receipt')));
  await started.promise;
  try { assert.equal(lookups, 34); } finally { gate.resolve(); }
  assert.ok((await Promise.all(deliveries)).every(r => r.ok));
  await forum.webhook('verified', billingNotification('event-0'));
  assert.equal(lookups, 35, 'Completed operations are not held in memory');
});

test('clearing already cleared forum penalties does not rewrite the profile', { skip: !database }, async () =>
  fixture(async (pool, [a, , , admin], forum) => {
    await forum.post(a, body());
    await pool.query("UPDATE forum_profiles SET violations=2,muted_until=CURRENT_TIMESTAMP+INTERVAL '7 days' WHERE user_id=$1", [a.id]);
    await forum.appealReview(admin, a.username_display);
    const state = () => pool.query('SELECT violations,muted_until,banned,xmin::text AS version FROM forum_profiles WHERE user_id=$1', [a.id]);
    const cleared = (await state()).rows[0];
    assert.equal(cleared.violations, 0);
    assert.equal(cleared.muted_until, null);
    assert.equal(cleared.banned, false);
    await forum.appealReview(admin, a.username_display);
    assert.deepEqual((await state()).rows[0], cleared);
  }));

test('post, reaction and purchase mutations use fewer SQL round trips without changing their results',
  { skip: !database }, async () => fixture(async (pool, [a, b]) => {
    await pool.query('INSERT INTO forum_profiles(user_id) VALUES($1),($2)', [a.id, b.id]);
    let statements = 0;
    const profileFields = [];
    const measured = {
      query: pool.query.bind(pool),
      async connect() {
        const db = await pool.connect();
        return { query: async (...args) => {
          statements++;
          const result = await db.query(...args);
          if (args[0].includes('FROM forum_profiles') && result.rows[0]) profileFields.push(Object.keys(result.rows[0]));
          return result;
        }, release: () => db.release() };
      }
    };
    const forum = createForum(measured, options);
    const first = await forum.post(a, body());
    assert.equal(statements, 5, 'Approved post transaction: BEGIN, locked profile, receipt, combined write, COMMIT');
    statements = 0;
    await forum.react(b, first.id, 'love');
    assert.equal(statements, 4, 'Reaction transaction combines locked-post selection and mutation');
    assert.deepEqual(profileFields.at(-1), ['muted_until', 'banned'], 'Reaction authorization returns only the two fields it uses');
    assert.equal((await forum.list(b)).posts[0].my_reaction, 'love');
    statements = 0;
    await forum.purchase(a, 'first-receipt');
    assert.equal(statements, 5, 'Purchase transaction combines receipt and entitlement writes');
    assert.deepEqual(profileFields.at(-1), ['banned', 'version'], 'Purchase authorization transfers only the ban and required MVCC version');
    assert.equal((await forum.list(a)).viewer.plus, true);
    await advance(pool, a);
    statements = 0;
    const flagged = createForum(measured, { ...options, moderate: async () => ({ flagged: true }) });
    const result = await flagged.post(a, body('Flagged text'));
    assert.equal(statements, 5, 'A flagged post also records its penalty in the same combined write');
    assert.equal(result.violations, 1);
    assert.equal(result.removed, true);
  }));

test('purchase updates preserve access from other receipts and post write failures roll back cooldowns and penalties',
  { skip: !database }, async () => fixture(async (pool, [a]) => {
    const active = new Set(['one', 'two']);
    const forum = createForum(pool, { ...options, verifyPurchase: async token => active.has(token),
      moderate: async () => ({ flagged: true }) });
    await forum.purchase(a, 'one');
    await forum.purchase(a, 'two');
    active.delete('one');
    assert.equal((await forum.purchase(a, 'one')).plus, true, 'Another active receipt still grants ownership');
    active.delete('two');
    assert.equal((await forum.purchase(a, 'two')).plus, false);
    active.add('two');
    assert.equal((await forum.purchase(a, 'two')).plus, true);
    await pool.query(`CREATE OR REPLACE FUNCTION fail_forum_profile_test() RETURNS trigger LANGUAGE plpgsql AS $$
      BEGIN RAISE EXCEPTION 'forced profile failure'; END $$`);
    await pool.query('CREATE TRIGGER fail_forum_profile_test BEFORE UPDATE ON forum_profiles FOR EACH ROW EXECUTE FUNCTION fail_forum_profile_test()');
    try {
      await assert.rejects(forum.post(a, body()), /forced profile failure/);
      assert.equal((await pool.query('SELECT id FROM forum_posts WHERE user_id=$1', [a.id])).rowCount, 0);
      const profile = (await pool.query('SELECT violations,last_post_at FROM forum_profiles WHERE user_id=$1', [a.id])).rows[0];
      assert.equal(profile.violations, 0);
      assert.equal(profile.last_post_at, null);
      active.clear();
      await assert.rejects(forum.purchase(a, 'two'), /forced profile failure/);
      assert.equal((await pool.query('SELECT active FROM forum_purchases WHERE user_id=$1 AND active', [a.id])).rowCount, 1);
    } finally {
      await pool.query('DROP TRIGGER fail_forum_profile_test ON forum_profiles');
      await pool.query('DROP FUNCTION fail_forum_profile_test()');
    }
  }));
