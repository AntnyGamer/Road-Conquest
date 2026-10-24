import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { createForum, moderationPenalty } from '../src/forum.mjs';
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
  assert.ok((await pool.query('SELECT * FROM users WHERE id=$1',[a.id])).rowCount);
  await assert.rejects(forum.post(a,body()),/banned/);
  await forum.appealReview(admin,a.username_display);
  assert.equal((await forum.list(a)).viewer.banned,false);
  const disabled=createForum(pool,{ ...options,moderate:null,verifyWebhook:null });
  assert.equal((await disabled.list(a)).posting_available,false);
  assert.equal((await disabled.list(a)).purchases_available,false);
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
  await advance(pool,a);
  const highlighted=await shop.post(a,body());
  assert.equal((await shop.list(null)).posts.find(p=>p.id===highlighted.id).highlighted,true);
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
