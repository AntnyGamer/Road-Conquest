// Local fixture only. Compare actual baseline/current code without network or a database.
import { execFileSync } from 'node:child_process';
import { createCipheriv, createHmac } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';

const baseline = process.argv[2] || '491e4e59bb7fca1aea9edc64b675eb17af3953fb';
async function previous(name) {
  let source = execFileSync('git', ['show', `${baseline}:server/src/${name}.mjs`], {encoding:'utf8'});
  const location = new URL(`../src/${name}.mjs`, import.meta.url);
  source = source.replace(/from (['"])(\.\/[^'"]+)\1/g, (_, quote, relative) =>
    `from ${quote}${new URL(relative, location).href}${quote}`);
  return import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
}
function concatProbe(run) {
  const concatenate = Buffer.concat;
  const result = {calls:0, output_bytes:0};
  Buffer.concat = function(parts, ...args) {
    const output = concatenate.call(this, parts, ...args);
    result.calls++;
    result.output_bytes += output.length;
    return output;
  };
  try { result.value = run(); } finally { Buffer.concat = concatenate; }
  return result;
}
async function probe(checkpoints, notifications, security) {
  const secret = 'K'.repeat(43), scope = '2:123:dataset';
  const run = '123e4567-e89b-12d3-a456-426614174000';
  const fix = {lat:40,lon:-75,time:1700000000000,elapsed:100000,accuracy:5,speed:10,mock:false};
  const codec = checkpoints.createCheckpointCodec(scope, secret);
  const seal = concatProbe(() => codec.seal(fix, run));
  const verify = concatProbe(() => codec.verify(seal.value, fix, run));
  delete seal.value;
  delete verify.value;

  const pepper = 'local-notification-buffer-resource-fixture';
  const key = createHmac('sha256', pepper).update('roadconquest-fcm-token-encryption-v1').digest();
  const rows = Array.from({length:5}, (_, index) => {
    const iv = Buffer.alloc(12, index + 1); // Fixture data only; production IVs remain random.
    const cipher = createCipheriv('aes-256-gcm', key, iv);
    cipher.setAAD(Buffer.from('roadconquest-fcm-token-v1'));
    return { encrypted_token:Buffer.concat([iv, cipher.update(`fixture-fid-${index}`.padEnd(100,'\0'),'utf8'), cipher.final(), cipher.getAuthTag()]), binding:String(index) };
  });
  rows[4].encrypted_token[rows[4].encrypted_token.length-1] ^= 1;
  const sent = [];
  const notifier = notifications.createNotifications({async query() { return {rows}; }},
    {pepper, sender:async (token,binding) => { sent.push({token,binding}); return 'sent'; }});
  const concatenate = Buffer.concat;
  const notification = {calls:0, output_bytes:0};
  Buffer.concat = function(parts, ...args) {
    const output = concatenate.call(this, parts, ...args);
    notification.calls++;
    notification.output_bytes += output.length;
    return output;
  };
  try { await notifier.friendRequest('fixture-account'); }
  finally { Buffer.concat = concatenate; }
  assert.deepEqual(sent, Array.from({length:4}, (_,index) => ({token:`fixture-fid-${index}`,binding:String(index)})));

  const from = Buffer.from;
  const passwords = {operations:3,copies:0, copied_bytes:0};
  Buffer.from = function(value, ...args) {
    if (Buffer.isBuffer(value) && value.length === 64) { passwords.copies++; passwords.copied_bytes+=64; }
    return from.call(this, value, ...args);
  };
  try {
    const hashed = await security.hashPassword('Unicode password 🛣️', pepper);
    assert.equal(Buffer.isBuffer(hashed.hash),true);
    assert.equal(await security.verifyPassword('Unicode password 🛣️',hashed.salt,hashed.hash,pepper),true);
    assert.equal(await security.verifyPassword('another password',hashed.salt,hashed.hash,pepper),false);
  } finally { Buffer.from = from; }
  return {checkpoint:{seal,verify},notifications:{...notification,delivered:sent.length,corrupt_dropped:1},passwords};
}
const oldModules = await Promise.all(['checkpoints','notifications','security'].map(previous));
const currentModules = await Promise.all(['checkpoints','notifications','security'].map(name => import(`../src/${name}.mjs`)));
const report = {baseline, runtime:process.version,
  before:await probe(...oldModules),after:await probe(...currentModules),
  limits:'Counts affected Buffer concatenation output bytes and explicit 64-byte copies, not slab allocations, peak heap, device battery, storage or quota consumption. Local fixtures only.'};
assert.equal(report.after.checkpoint.seal.calls,1);
assert.equal(report.after.checkpoint.verify.calls,0);
assert.equal(report.after.notifications.calls,0);
assert.equal(report.after.passwords.copies,0);
if (process.argv[3]) await writeFile(process.argv[3], JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report,null,2));
