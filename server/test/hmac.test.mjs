import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { createHmacSigner } from '../worker/hmac.mjs';

test('shared HMAC key imports preserve every request signature and remain bounded across rotation',async()=>{
  let imports=0;
  const subtle={importKey:(...args)=>{imports++;return crypto.subtle.importKey(...args);},sign:crypto.subtle.sign.bind(crypto.subtle)};
  const signer=createHmacSigner(2,subtle);
  const expected=(key,payload)=>createHmac('sha256',key).update(payload).digest();
  await Promise.all(Array.from({length:100},async(_,i)=>{
    const key=i%2?'first-key':'second-key',payload='GET\n/path?x='+i+'\n192.0.2.1\n1234567890123';
    assert.deepEqual(Buffer.from(await signer(key,payload)),expected(key,payload));
  }));
  assert.equal(imports,2);
  assert.deepEqual(Buffer.from(await signer('rotated-key','fresh')),expected('rotated-key','fresh'));
  await signer('second-key','again'); // Oldest evicted key must import again.
  assert.equal(imports,4);
});

test('failed HMAC imports are retried instead of poisoning the signing cache',async()=>{
  let fail=true;
  const signer=createHmacSigner(1,{importKey:(...args)=>{if(fail){fail=false;throw new Error('temporary import failure');}return crypto.subtle.importKey(...args);},
    sign:crypto.subtle.sign.bind(crypto.subtle)});
  await assert.rejects(signer('test-key','one'),/temporary/);
  assert.deepEqual(Buffer.from(await signer('test-key','two')),createHmac('sha256','test-key').update('two').digest());
});
