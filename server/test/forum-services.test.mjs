import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, sign } from 'node:crypto';
import { GifWriter } from 'omggif';
import { PNG } from 'pngjs';
import { createForumModerator, forumGifUrl, gifContactSheet } from '../src/forum-moderation.mjs';
import { createForumPurchaseVerifier, createForumWebhookVerifier, forumAccountBinding, purchaseCodec } from '../src/forum-billing.mjs';

const categories = Object.fromEntries(['harassment','harassment/threatening','hate','hate/threatening','sexual',
  'sexual/minors','violence/graphic','illicit/violent','self-harm/instructions'].map(k=>[k,false]));
const reply = data => new Response(JSON.stringify(data),{headers:{'Content-Type':'application/json'}});
function animation() {
  const bytes=Buffer.alloc(4096), gif=new GifWriter(bytes,2,2,{palette:[0xff0000,0x0000ff]});
  gif.addFrame(0,0,2,2,new Uint8Array([0,0,0,0]),{delay:10});
  gif.addFrame(0,0,2,2,new Uint8Array([1,1,1,1]),{delay:10});
  return bytes.subarray(0,gif.end());
}

test('GIF links reject redirects, untrusted hosts, credentials and oversized/invalid media', async () => {
  for(const url of ['http://media.giphy.com/a.gif','https://media.giphy.com.evil.test/a.gif','https://user@media.giphy.com/a.gif',
    'https://media.giphy.com:444/a.gif','https://media.giphy.com/a.html','https://127.0.0.1/a.gif']) assert.throws(()=>forumGifUrl(url));
  assert.equal(forumGifUrl('https://media.tenor.com/a.gif'),'https://media.tenor.com/a.gif');
  assert.throws(()=>gifContactSheet(Buffer.alloc(2*1024*1024+1)),/2 MB/);
  assert.throws(()=>gifContactSheet(Buffer.from('not a GIF')),/read/);
  const moderate=createForumModerator('test',async()=>new Response(null,{status:302,headers:{Location:'https://evil.test'}}));
  await assert.rejects(moderate({text:'Hello',gifUrl:'https://media.giphy.com/a.gif'}),/unavailable/);
});

test('every GIF frame enters the moderation contact sheet and the moderated bytes are fingerprinted', async () => {
  const bytes=animation(), png=PNG.sync.read(gifContactSheet(bytes));
  assert.deepEqual([...png.data.subarray(0,3)],[255,0,0]);
  assert.deepEqual([...png.data.subarray(128*4,128*4+3)],[0,0,255]);
  let calls=0;
  const moderate=createForumModerator('server-only',async(url,init)=>{
    calls++;
    if(url.startsWith('https://media.giphy.com/')) return new Response(bytes,{headers:{'Content-Type':'image/gif'}});
    assert.equal(url,'https://api.openai.com/v1/moderations');
    assert.equal(init.headers.Authorization,'Bearer server-only');
    const body=JSON.parse(init.body);
    assert.equal(body.model,'omni-moderation-latest');
    assert.equal(body.input.length,2);
    assert.ok(body.input[1].image_url.url.startsWith('data:image/png;base64,'));
    return reply({model:'test',results:[{flagged:true,categories:{...categories,hate:true}}]});
  });
  const verdict=await moderate({text:'A GIF',gifUrl:'https://media.giphy.com/a.gif'});
  assert.equal(verdict.flagged,true); assert.deepEqual(verdict.categories,['hate']);
  assert.match(verdict.gifHash,/^[a-f0-9]{64}$/); assert.equal(calls,2);
});

test('moderation outages and incomplete responses fail closed; distress and ordinary road discussion alone do not strike', async () => {
  assert.equal(createForumModerator(null),null);
  for(const response of [new Response('outage',{status:503}),reply({}),reply({model:'test',results:[{flagged:false,categories:{}}]})]) {
    await assert.rejects(createForumModerator('test',async()=>response)({text:'Hello'}),e=>e.status===503);
  }
  const moderate=createForumModerator('test',async()=>reply({model:'test',results:[{flagged:true,
    categories:{...categories,violence:true,'self-harm/intent':true}}]}));
  assert.equal((await moderate({text:'A hard day on the road'})).flagged,false);
});

const keys=generateKeyPairSync('rsa',{modulusLength:2048});
const credentials=JSON.stringify({type:'service_account',client_email:'play@example.test',private_key:keys.privateKey.export({format:'pem',type:'pkcs8'})});
test('Play verifies product/account/state, acknowledges on the server, and caches OAuth without granting pending payments', async () => {
  let state={purchaseState:0,consumptionState:0,acknowledgementState:0,obfuscatedExternalAccountId:'bound'};
  let oauth=0,acks=0;
  const verify=createForumPurchaseVerifier(credentials,async(url,init)=>{
    if(url==='https://oauth2.googleapis.com/token') { oauth++; return reply({access_token:'access',expires_in:3600}); }
    assert.ok(url.includes('/com.roadconquest.app/purchases/products/forum_plus_lifetime/tokens/'));
    assert.equal(init.headers.Authorization,'Bearer access');
    if(url.endsWith(':acknowledge')) { acks++; assert.equal(init.method,'POST'); return new Response('{}'); }
    return reply(state);
  });
  assert.equal(await verify('receipt','bound'),true); assert.equal(acks,1);
  state={...state,acknowledgementState:1};
  await verify('receipt','bound'); assert.equal(acks,1); assert.equal(oauth,1);
  for(const purchaseState of [1,2,undefined]) { state={...state,purchaseState}; assert.equal(await verify('receipt','bound'),false); }
  state={...state,purchaseState:0};
  await assert.rejects(verify('receipt','wrong-account'),e=>e.status===403);
  state={...state,consumptionState:1}; await assert.rejects(verify('receipt','bound'),e=>e.status===403);
  await assert.rejects(verify('','bound'),e=>e.status===400);
});

test('account binding is stable and separated; encrypted purchase tokens detect tampering', () => {
  const codec=purchaseCodec('pepper'), bytes=codec.seal('sensitive-token');
  assert.equal(codec.open(bytes),'sensitive-token'); assert.ok(!bytes.includes(Buffer.from('sensitive-token')));
  bytes[14]^=1; assert.throws(()=>codec.open(bytes));
  assert.equal(forumAccountBinding('pepper',42),forumAccountBinding('pepper','42'));
  assert.notEqual(forumAccountBinding('pepper',42),forumAccountBinding('pepper',43));
});

test('purchase notifications require a Google signature, trusted audience/email and valid time; signing keys are cached', async () => {
  const time=Date.now(), aud='https://accounts.example/v1/forum/play-notifications',email='push@example.test';
  const jwk={...keys.publicKey.export({format:'jwk'}),kid:'test-key',alg:'RS256'};
  let fetches=0;
  const verify=createForumWebhookVerifier(aud,email,async url=>{ fetches++; assert.equal(url,'https://www.googleapis.com/oauth2/v3/certs'); return reply({keys:[jwk]}); },()=>time);
  const claims={iss:'https://accounts.google.com',aud,email,email_verified:true,iat:Math.floor(time/1000),exp:Math.floor(time/1000)+3600};
  function jwt(changes={},key=keys.privateKey) {
    const encode=value=>Buffer.from(JSON.stringify(value)).toString('base64url');
    const content=encode({alg:'RS256',kid:'test-key'})+'.'+encode({...claims,...changes});
    return 'Bearer '+content+'.'+sign('RSA-SHA256',Buffer.from(content),key).toString('base64url');
  }
  assert.equal(await verify(jwt()),true); assert.equal(await verify(jwt()),true); assert.equal(fetches,1);
  for(const change of [{aud:'wrong'},{email:'attacker@test'},{email_verified:false},{iss:'attacker'},{exp:0},{iat:time/1000+120}]) assert.equal(await verify(jwt(change)),false);
  const other=generateKeyPairSync('rsa',{modulusLength:2048});
  assert.equal(await verify(jwt({},other.privateKey)),false);
  assert.equal(await verify('Bearer junk'),false);
});
