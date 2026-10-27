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
    'https://media.giphy.com:444/a.gif','https://media.giphy.com/a.html','https://127.0.0.1/a.gif',
    'https://media.giphy.com/a.gif#','https://media.giphy.com/a.gif#fragment']) assert.throws(()=>forumGifUrl(url));
  assert.equal(forumGifUrl('https://media.tenor.com/a.gif'),'https://media.tenor.com/a.gif');
  assert.equal(forumGifUrl('HTTPS://MEDIA.GIPHY.COM/a.GIF'),'https://media.giphy.com/a.GIF');
  assert.throws(()=>forumGifUrl('https://media.giphy.com/'+ '\u2615'.repeat(200)+'.gif'),/GIF link/,
    'Canonical URL length must fit the stored/media response bound after percent encoding');
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

test('GIF preview preserves every pixel across transparent partial frames and both disposal modes', () => {
  const bytes = Buffer.alloc(4096);
  const gif = new GifWriter(bytes, 2, 1, { palette: [0, 0xb52c19, 0x2168d1, 0x43a65e] });
  gif.addFrame(0, 0, 2, 1, new Uint8Array([0, 1]), { transparent: 0, disposal: 1 });
  gif.addFrame(0, 0, 1, 1, new Uint8Array([2]), { disposal: 3 });
  gif.addFrame(1, 0, 1, 1, new Uint8Array([3]), { disposal: 2 });
  gif.addFrame(0, 0, 1, 1, new Uint8Array([1]), { disposal: 1 });
  gif.addFrame(0, 0, 2, 1, new Uint8Array([0, 2]), { transparent: 0 });
  const preview = PNG.sync.read(gifContactSheet(bytes.subarray(0, gif.end())));
  const white = [255, 255, 255], red = [181, 44, 25], blue = [33, 104, 209], green = [67, 166, 94];
  // Hand-composited canvases: restore frame 1's prior state, then clear frame 2's rectangle.
  const frames = [[white, red], [blue, red], [white, green], [red, white], [red, blue]];
  assert.equal(preview.width, 384);
  assert.equal(preview.height, 256);
  const expected = Buffer.alloc(384 * 256 * 4, 255);
  frames.forEach((colors, frame) => colors.forEach((color, half) => {
    const left = frame % 3 * 128 + half * 64, top = Math.floor(frame / 3) * 128 + 32;
    for (let y = top; y < top + 64; y++) for (let x = left; x < left + 64; x++) {
      const offset = (y * 384 + x) * 4;
      expected[offset] = color[0]; expected[offset + 1] = color[1]; expected[offset + 2] = color[2];
    }
  }));
  assert.deepEqual(preview.data, expected);
});

test('moderation outages and incomplete responses fail closed; distress and ordinary road discussion alone do not strike', async () => {
  assert.equal(createForumModerator(null),null);
  for(const response of [new Response('outage',{status:503}),reply({}),reply({model:'test',results:[{flagged:false,categories:{}}]})]) {
    await assert.rejects(createForumModerator('test',async()=>response)({text:'Hello'}),e=>e.status===503);
  }
  await assert.rejects(createForumModerator('test',async()=>reply(null))({text:'Hello'}),e=>e.status===503);
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
  for(const purchaseState of [1,2]) { state={...state,purchaseState}; assert.equal(await verify('receipt','bound'),false); }
  for(const purchaseState of [undefined,null,-1,3,'0']) {
    state={...state,purchaseState}; await assert.rejects(verify('receipt','bound'),e=>e.status===503);
  }
  state={...state,purchaseState:0};
  await assert.rejects(verify('receipt','wrong-account'),e=>e.status===403);
  state={...state,consumptionState:1}; await assert.rejects(verify('receipt','bound'),e=>e.status===403);
  await assert.rejects(verify('','bound'),e=>e.status===400);
});

test('a rejected cached Play credential is refreshed on retry, including acknowledgement failures', async () => {
  for (const rejectedStep of ['get','ack']) {
    let oauth=0,rejected=false;
    const verify=createForumPurchaseVerifier(credentials,async(url,init)=>{
      if(url==='https://oauth2.googleapis.com/token') return reply({access_token:'access-'+(++oauth),expires_in:3600});
      const step=url.endsWith(':acknowledge')?'ack':'get';
      if(step===rejectedStep && !rejected) { rejected=true; return new Response('{}',{status:401}); }
      assert.equal(init.headers.Authorization,'Bearer access-'+oauth);
      return step==='ack' ? new Response('{}') : reply({purchaseState:0,consumptionState:0,
        acknowledgementState:0,obfuscatedExternalAccountId:'bound'});
    });
    await assert.rejects(verify('receipt','bound'),e=>e.status===503);
    assert.equal(await verify('receipt','bound'),true);
    assert.equal(oauth,2,rejectedStep+' must invalidate its rejected credential');
  }
});

test('null and empty Play OAuth replies stay retryable and never reach receipt verification', async () => {
  for (const invalid of [null, { access_token: '', expires_in: 3600 }]) {
    let auth = 0, checks = 0;
    const verify = createForumPurchaseVerifier(credentials, async url => {
      if (url === 'https://oauth2.googleapis.com/token') return reply(++auth === 1
        ? invalid : { access_token: 'recovered', expires_in: 3600 });
      checks++;
      return reply({ purchaseState: 0, consumptionState: 0, acknowledgementState: 1, obfuscatedExternalAccountId: 'bound' });
    });
    await assert.rejects(verify('receipt', 'bound'), error => error.status === 503);
    assert.equal(checks, 0);
    assert.equal(await verify('receipt', 'bound'), true);
    assert.equal(auth, 2);
  }
});

test('a delayed old Play credential failure preserves a newer shared credential', async () => {
  let oauth=0,release,started;
  const pending=new Promise(resolve=>{release=resolve;});
  const requested=new Promise(resolve=>{started=resolve;});
  const verify=createForumPurchaseVerifier(credentials,async url=>{
    if(url==='https://oauth2.googleapis.com/token') return reply({access_token:'access-'+(++oauth),expires_in:3600});
    if(url.endsWith('/slow')) { started(); return pending; }
    if(url.endsWith('/expired')) return new Response('{}',{status:401});
    return reply({purchaseState:0,consumptionState:0,acknowledgementState:1,obfuscatedExternalAccountId:'bound'});
  });
  const slow=verify('slow','bound');
  const rejected=assert.rejects(slow,e=>e.status===503);
  await requested;
  await assert.rejects(verify('expired','bound'),e=>e.status===503);
  assert.equal(await verify('fresh','bound'),true);
  release(new Response('{}',{status:401})); await rejected;
  assert.equal(await verify('fresh-again','bound'),true);
  assert.equal(oauth,2);
});

test('successful acknowledgement is not undone or delayed by response-body cleanup', { timeout: 2000 }, async () => {
  for(const failCleanup of [true,false]) {
    let cleaned=false;
    const verify=createForumPurchaseVerifier(credentials,async url=>{
      if(url==='https://oauth2.googleapis.com/token') return reply({access_token:'access',expires_in:3600});
      if(url.endsWith(':acknowledge')) return {ok:true,status:200,body:{cancel:()=>{
        cleaned=true;
        return failCleanup?Promise.reject(new Error('cleanup failed')):new Promise(()=>{});
      }}};
      return reply({purchaseState:0,consumptionState:0,acknowledgementState:0,obfuscatedExternalAccountId:'bound'});
    });
    assert.equal(await verify('receipt','bound'),true);
    assert.equal(cleaned,true);
  }
});

test('interrupted GIF downloads and contradictory moderation results are retryable outages', async () => {
  const unavailable=createForumModerator('test',async()=>{throw new TypeError('network unavailable');});
  await assert.rejects(unavailable({text:'A GIF',gifUrl:'https://media.giphy.com/a.gif'}),e=>e.status===503);
  const interrupted=createForumModerator('test',async()=>new Response(new ReadableStream({
    start(controller){controller.error(new Error('connection lost'));}
  }),{headers:{'Content-Type':'image/gif'}}));
  await assert.rejects(interrupted({text:'A GIF',gifUrl:'https://media.giphy.com/a.gif'}),e=>e.status===503);
  const inconsistent=createForumModerator('test',async()=>reply({model:'test',results:[{
    flagged:false,categories:{...categories,hate:true}
  }]}));
  await assert.rejects(inconsistent({text:'Hello'}),e=>e.status===503);
});

test('GIF provider timeouts, throttling and server errors are retryable without calling moderation', async () => {
  for (const status of [408, 429, 500, 502, 503, 504]) {
    let calls = 0, reads = 0, cancelled = 0;
    const moderate = createForumModerator('test', async () => {
      calls++;
      return new Response(new ReadableStream({
        pull() { reads++; throw Error('Provider error bodies are not needed'); },
        cancel() { cancelled++; }
      }, { highWaterMark: 0 }), { status });
    });
    await assert.rejects(moderate({ text: 'A GIF', gifUrl: 'https://media.giphy.com/a.gif' }),
      error => error.status === 503 && /try again/i.test(error.message));
    assert.equal(calls, 1);
    assert.equal(reads, 0);
    assert.equal(cancelled, 1);
  }
  for (const status of [301, 403, 404, 410]) {
    const moderate = createForumModerator('test', async () => new Response(null, { status }));
    await assert.rejects(moderate({ text: 'A GIF', gifUrl: 'https://media.giphy.com/a.gif' }),
      error => error.status === 400 && /another GIF/.test(error.message));
  }
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

test('GIF moderation uses exactly the supplied byte range for single and split chunks', async () => {
  const bytes = animation(), expected = gifContactSheet(bytes).toString('base64');
  for (const split of [false, true]) {
    const padded = Buffer.concat([Buffer.from('junk'), bytes, Buffer.from('junk')]);
    const chunk = new Uint8Array(padded.buffer, padded.byteOffset + 4, bytes.length);
    const moderate = createForumModerator('test', async (url, init) => {
      if (url.startsWith('https://media.giphy.com/')) return new Response(new ReadableStream({
        start(controller) {
          if (split) { controller.enqueue(chunk.subarray(0, 7)); controller.enqueue(chunk.subarray(7)); }
          else controller.enqueue(chunk);
          controller.close();
        }
      }), { headers: { 'Content-Type': 'image/gif' } });
      assert.equal(JSON.parse(init.body).input[1].image_url.url, 'data:image/png;base64,' + expected);
      return reply({ model: 'test', results: [{ flagged: false, categories }] });
    });
    assert.equal((await moderate({ text: 'Hello', gifUrl: 'https://media.giphy.com/a.gif' })).flagged, false);
    assert.deepEqual(padded.subarray(4, -4), bytes, 'Moderation never mutates the source chunk');
  }
});

test('cached parsed Google signing keys expire with JWKS and never accept an old rotated signature', async () => {
  let now = Date.now(), pair = keys, fetches = 0;
  const audience = 'https://accounts.example/notifications', email = 'push@example.test';
  const verify = createForumWebhookVerifier(audience, email, async () => {
    fetches++;
    return reply({ keys: [{ ...pair.publicKey.export({ format: 'jwk' }), kid: 'same-id', alg: 'RS256' }] });
  }, () => now);
  function jwt(key) {
    const encode = data => Buffer.from(JSON.stringify(data)).toString('base64url');
    const text = encode({ alg: 'RS256', kid: 'same-id' }) + '.' + encode({ iss: 'https://accounts.google.com',
      aud: audience, email, email_verified: true, iat: Math.floor(now / 1000), exp: Math.floor(now / 1000) + 3600 });
    return 'Bearer ' + text + '.' + sign('RSA-SHA256', Buffer.from(text), key).toString('base64url');
  }
  assert.equal(await verify(jwt(pair.privateKey)), true);
  assert.equal(await verify(jwt(pair.privateKey)), true);
  const old = pair;
  pair = generateKeyPairSync('rsa', { modulusLength: 2048 });
  now += 300_001;
  assert.equal(await verify(jwt(pair.privateKey)), true);
  assert.equal(await verify(jwt(old.privateKey)), false);
  assert.equal(fetches, 2);
});

test('announced oversized GIFs stop before download or moderation but compressed byte counts are not confused', async () => {
  let reads = 0, cancelled = false, calls = 0;
  const moderate = createForumModerator('test', async () => {
    calls++;
    return new Response(new ReadableStream({
      pull() { reads++; throw new Error('Must not download'); },
      cancel() { cancelled = true; }
    }, { highWaterMark: 0 }), { headers: { 'Content-Type': 'image/gif', 'Content-Length': String(2 * 1024 * 1024 + 1) } });
  });
  await assert.rejects(moderate({ text: 'A GIF', gifUrl: 'https://media.giphy.com/a.gif' }), e => e.status === 400 && /2 MB/.test(e.message));
  assert.equal(reads, 0); assert.equal(cancelled, true); assert.equal(calls, 1);
  const encoded = createForumModerator('test', async url => url.startsWith('https://media.giphy.com/')
    ? new Response(animation(), { headers: { 'Content-Type': 'image/gif', 'Content-Encoding': 'gzip', 'Content-Length': String(2 * 1024 * 1024 + 1) } })
    : reply({ model: 'test', results: [{ flagged: false, categories }] }));
  assert.equal((await encoded({ text: 'A GIF', gifUrl: 'https://media.giphy.com/a.gif' })).flagged, false);
});
