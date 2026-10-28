import test from 'node:test';
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const execute = promisify(execFile);
for (const applySchema of ['0', '1']) test(`cold liveness and public pages need no database, including schema setting ${applySchema}`, async () => {
  const environment = { ...process.env, DATABASE_URL: 'postgresql://unused@127.0.0.1:1/unavailable',
    PASSWORD_PEPPER: 'synthetic-offline-function-test-pepper', NODE_ENV: 'production',
    REQUIRE_HTTPS: '1', TRUST_PROXY: '1', APPLY_SCHEMA_ON_STARTUP: applySchema, LEADERBOARDS_ENABLED: '0' };
  delete environment.FCM_SERVICE_ACCOUNT_JSON;
  delete environment.PLAY_SERVICE_ACCOUNT_JSON;
  const moduleUrl = new URL('../dist/index.mjs', import.meta.url).href;
  const { stdout } = await execute(process.execPath, ['--input-type=module', '-e', `
    import assert from 'node:assert/strict';
    const {default:api,pool}=await import(${JSON.stringify(moduleUrl)});
    let queries=0;
    const query=pool.query.bind(pool);
    pool.query=(...args)=>{queries++;return query(...args);};
    try {
      for (const [path,status] of [['/health/live',200],['/privacy',200],['/delete-account',200],
        ['/v1/competition',200],['/v1/leaderboard',200],['/v1/leaderboard?metric=invalid',400],['/missing',404],['/v1/me',401],['/v1/verified/batch',404],
        ['/v1/friends',401],['/v1/friends/weekly',401],['/v1/notifications/device',401]]) {
        const response=await api.fetch(new Request('https://offline.example'+path));
        assert.equal(response.status,status,path);
        await response.text();
      }
      const head=await api.fetch(new Request('https://offline.example/health/live',{method:'HEAD'}));
      assert.equal(head.status,200);assert.equal(await head.text(),'');
      let bodyReads=0;
      for (const [type,status] of [['application/json',413],['text/plain',415],['application/jsonp',415]]) {
        const body=new ReadableStream({pull(){bodyReads++;throw Error('Rejected request body must not be read');}}, {highWaterMark:0});
        const rejected=await api.fetch(new Request('https://offline.example/v1/signup',{
          method:'POST',headers:{'Content-Type':type,'Content-Length':'32769'},body,duplex:'half'
        }));
        assert.equal(rejected.status,status);await rejected.text();await body.cancel();
      }
      assert.equal(bodyReads,0);
      // Decode only the supplied byte range, including a UTF-8 character split
      // across chunks. These invalid credentials must fail before any DB work.
      const raw=Buffer.from(JSON.stringify({username:'x',password:'é'.repeat(8)}));
      const padded=Buffer.concat([Buffer.from([0xff]),raw,Buffer.from([0xff])]);
      const offset=padded.byteOffset+1;
      const split=raw.indexOf(0xc3)+1;
      const bodies=[[padded.subarray(1,-1)],
        [new Uint8Array(padded.buffer,offset,raw.byteLength)],
        [raw.subarray(0,split),raw.subarray(split)]];
      for (const [index,chunks] of bodies.entries()) {
        const body=new ReadableStream({start(controller){for(const chunk of chunks)controller.enqueue(chunk);controller.close();}});
        const parsed=await api.fetch(new Request('https://offline.example/v1/signup',{
          method:'POST',headers:{'Content-Type':index===1?'Application/JSON ; charset=utf-8':'application/json'},body,duplex:'half'
        }));
        assert.equal(parsed.status,400);
        assert.deepEqual(await parsed.json(),{error:'Username must be 3-24 letters, numbers, or underscores. Password must be 8-128 characters.'});
      }
      // Without strict decoding these bytes become a DIFFERENT, valid password
      // containing replacement characters and can reach account creation.
      const prefix=Buffer.from('{"username":"ValidDriver","password":"validpass');
      const malformed=Buffer.concat([prefix,Buffer.from([0xc3,0x28]),Buffer.from('"}')]);
      const wrapped=Buffer.concat([Buffer.from('!'),malformed,Buffer.from('!')]);
      for (const chunks of [[malformed],
        [new Uint8Array(wrapped.buffer,wrapped.byteOffset+1,malformed.length)],
        [malformed.subarray(0,prefix.length+1),malformed.subarray(prefix.length+1)]]) {
        const body=new ReadableStream({start(controller){for(const chunk of chunks)controller.enqueue(chunk);controller.close();}});
        const rejected=await api.fetch(new Request('https://offline.example/v1/signup',{
          method:'POST',headers:{'Content-Type':'application/json'},body,duplex:'half'
        }));
        assert.equal(rejected.status,400);
        assert.deepEqual(await rejected.json(),{error:'Request failed.'});
      }
      for (const [path,method] of [['/v1/friends','POST'],['/v1/notifications/device','POST']]) {
        const response=await api.fetch(new Request('https://offline.example'+path,
          {method,headers:{Authorization:'Bearer malformed'},body:'{}'}));
        assert.equal(response.status,401);
        await response.text();
      }
      const unavailableRanking=await api.fetch(new Request('https://offline.example/v1/leaderboard?mine=1', {
        headers:{Authorization:'Bearer '+'x'.repeat(43)}
      }));
      assert.equal(unavailableRanking.status,200);
      assert.deepEqual(await unavailableRanking.json(),{metric:'cells',dataset:null,entries:[],verification_available:false});
      assert.equal(queries,0);assert.equal(pool.totalCount,0);
      console.log(JSON.stringify({publicRequests:14,unauthenticatedPosts:2,rejectedBodies:3,parsedBodies:6,bodyReads,queries,connections:pool.totalCount}));
    } finally {await pool.end();}
  `], { env: environment, timeout: 15000 });
  assert.deepEqual(JSON.parse(stdout.trim()), { publicRequests: 14, unauthenticatedPosts:2, rejectedBodies:3, parsedBodies:6, bodyReads:0, queries: 0, connections: 0 });
});
