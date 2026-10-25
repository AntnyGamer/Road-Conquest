import test from 'node:test';
import assert from 'node:assert/strict';
import pg from 'pg';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { limitedApiDatabase } from './helpers/limited-api-role.mjs';

test('community HTTP routes preserve public reads, account authentication, persistent cooldown, safe errors and runtime grants',
  { skip: !process.env.DATABASE_URL }, async () => {
    const admin=new pg.Pool({connectionString:process.env.DATABASE_URL});
    const originalFetch=globalThis.fetch;
    let runtime;
    try {
      await admin.query(await readFile(new URL('../schema.sql',import.meta.url),'utf8'));
      await admin.query(await readFile(new URL('../forum-schema.sql',import.meta.url),'utf8'));
      await admin.query('TRUNCATE users,auth_rate_limits RESTART IDENTITY CASCADE');
      process.env.DATABASE_URL=await limitedApiDatabase(admin,process.env.DATABASE_URL);
      await admin.query(`GRANT SELECT,INSERT,UPDATE,DELETE ON forum_profiles,forum_posts,forum_reactions,
        forum_blocks,forum_reports,forum_purchases,forum_billing_events TO roadconquest_api_test`);
      process.env.FORUM_ENABLED='1'; process.env.FORUM_MODERATION_API_KEY='test-only';
      process.env.APPLY_SCHEMA_ON_STARTUP='0'; process.env.REQUIRE_HTTPS='1'; process.env.TRUST_PROXY='1';
      globalThis.fetch=async url=>{
        assert.equal(url,'https://api.openai.com/v1/moderations');
        const categories=Object.fromEntries(['harassment','harassment/threatening','hate','hate/threatening',
          'sexual','sexual/minors','violence/graphic','illicit/violent','self-harm/instructions'].map(k=>[k,false]));
        return new Response(JSON.stringify({model:'test',results:[{flagged:false,categories}]}));
      };
      const {serve,pool}=await import('../src/application.mjs'); runtime=pool;
      const {createFetchHandler}=await import('../src/fetch-adapter.mjs'); const handler=createFetchHandler(serve);
      async function call(path,method='GET',body,token) {
        const headers={'Content-Type':'application/json','X-Forwarded-Proto':'https','X-Forwarded-For':'192.0.2.1'};
        if(token)headers.Authorization='Bearer '+token;
        const response=await handler(new Request('https://accounts.test'+path,{method,headers,body:body?JSON.stringify(body):undefined}));
        assert.equal(response.headers.get('Cache-Control'),'no-store');
        return {status:response.status,body:await response.json()};
      }
      const anonymous=await call('/v1/forum/posts'); assert.equal(anonymous.status,200); assert.equal(anonymous.body.viewer,null);
      assert.equal(anonymous.body.purchases_available,false);
      assert.equal((await call('/v1/forum/posts','POST',{})).status,401);
      const signup=await call('/v1/signup','POST',{username:'ForumHttp',password:'correct horse battery staple'});
      assert.equal(signup.status,201); const token=signup.body.token;
      const input={request_id:randomUUID(),text:'A scenic drive.',gif_url:null,rules_version:1};
      const post=await call('/v1/forum/posts','POST',input,token); assert.equal(post.status,200);
      const retry=await call('/v1/forum/posts','POST',input,token); assert.equal(retry.body.id,post.body.id);
      const cooldown=await call('/v1/forum/posts','POST',{...input,request_id:randomUUID()},token);
      assert.equal(cooldown.status,429); assert.match(cooldown.body.error,/10 minutes/);
      assert.equal((await call('/v1/forum/posts')).body.posts.length,1);
      assert.ok((await call('/v1/forum/posts','GET',undefined,token)).body.viewer.next_post_at);
      assert.equal((await call('/v1/forum/purchase','POST',{purchase_token:'fake'},token)).status,503);
      // Repeated purchase verification can hold an account transaction open while
      // contacting Google Play. A separate 5/min ceiling protects that external
      // call while keeping other forum writes on their original shared budget.
      for (let index=0;index<4;index++)
        assert.equal((await call('/v1/forum/purchase','POST',{purchase_token:'fake'},token)).status,503);
      assert.equal((await call('/v1/forum/purchase','POST',{purchase_token:'fake'},token)).status,429);
      const purchaseLimit=(await admin.query("SELECT attempts FROM auth_rate_limits WHERE bucket='forum-purchase'")).rows[0];
      assert.equal(Number(purchaseLimit.attempts),6);
      // Reaction bursts have their own 30/minute ceiling without bypassing the
      // shared forum-write budget or turning duplicate reactions into new likes.
      const reactionPath='/v1/forum/posts/'+post.body.id+'/reaction';
      for (let index=0;index<30;index++) {
        const response=await call(reactionPath,'PUT',{reaction:index%2===0?'like':'love'},token);
        assert.equal(response.status,200,'Normal reactions remain available');
      }
      const tooManyReactions=await call(reactionPath,'PUT',{reaction:'celebrate'},token);
      assert.equal(tooManyReactions.status,429,'Automated reaction bursts are throttled');
      const limits=(await admin.query("SELECT bucket,attempts FROM auth_rate_limits WHERE bucket IN ('forum-write','forum-reaction') ORDER BY bucket")).rows;
      assert.deepEqual(limits.map(x=>x.bucket),['forum-reaction','forum-write']);
      assert.equal(Number(limits[0].attempts),31,'Saturated reaction attempts stop at the sentinel count');
      assert.ok(Number(limits[1].attempts)>=31,'The shared forum-write budget still applies');
      const reactionList=await call('/v1/forum/posts');
      assert.equal(reactionList.body.posts[0].reactions.love,1);
      assert.equal(reactionList.body.posts[0].reactions.celebrate,0);
      assert.equal((await call('/v1/forum/play-notifications','POST',{})).status,401);
      assert.equal((await call('/v1/forum/appeal-review','POST',{username:'ForumHttp'},token)).status,403);
      assert.equal((await call('/v1/forum/posts/'+post.body.id,'DELETE',undefined,token)).status,200);
      assert.equal((await call('/v1/forum/posts')).body.posts.length,0);
    } finally { globalThis.fetch=originalFetch; await runtime?.end(); await admin.end(); }
  });
