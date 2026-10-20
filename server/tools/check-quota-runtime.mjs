// Optional local workerd audit. Supply MINIFLARE_MODULE as the installed
// miniflare entry point; no production account or provider requests are used.
import { execFileSync } from 'node:child_process';
import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';

const { Miniflare, convertV4MiniflareOptions } = await import(process.env.MINIFLARE_MODULE
  ? pathToFileURL(process.env.MINIFLARE_MODULE).href : 'miniflare');
const repo = fileURLToPath(new URL('../../', import.meta.url));
const baselineRef = 'da9bae5';
const baseline = execFileSync('git', ['show', `${baselineRef}:server/maps/budget.mjs`], { cwd: repo, encoding: 'utf8' });
const current = await readFile(new URL('../maps/budget.mjs', import.meta.url), 'utf8');
const normalized = text => text.replaceAll('\r\n', '\n');
const sourceInvariants = [];
for (const file of ['server/maps/worker.mjs', 'server/maps/config.mjs', 'server/src/integrity.mjs', 'server/src/scoring.mjs']) {
  assert.equal(normalized(await readFile(new URL('../../' + file, import.meta.url), 'utf8')),
    normalized(execFileSync('git', ['show', `${baselineRef}:${file}`], { cwd: repo, encoding: 'utf8' })), file);
  sourceInvariants.push({ file, scope: 'complete file', identical: true });
}
const afterMaintenance = source => normalized(source).slice(normalized(source).indexOf('    if (attemptId) {'));
assert.equal(afterMaintenance(current), afterMaintenance(baseline));
sourceInvariants.push({ file: 'server/maps/budget.mjs', scope: 'all reservation and finish logic after maintenance', identical: true });
const submission = source => {
  source = normalized(source);
  return source.slice(source.indexOf('    async submit('), source.indexOf('    async friendWeek('))
    .replace('await verifyBatch(user, integrityToken, hash);', 'await verify(integrityToken, hash);');
};
assert.equal(submission(await readFile(new URL('../src/competition.mjs', import.meta.url), 'utf8')),
  submission(execFileSync('git', ['show', `${baselineRef}:server/src/competition.mjs`], { cwd: repo, encoding: 'utf8' })));
sourceInvariants.push({ file: 'server/src/competition.mjs', scope: 'complete submission except its single call through the scoped pending verifier', identical: true });

async function run(source) {
  const script = source + `
const LegacyMapBudget = (() => {
${baseline.replace('export class MapBudget', 'class MapBudget')}
return MapBudget;
})();
export class AuditBudget {
  constructor(ctx) {
    this.ctx=ctx; this.reads=0; this.writes=0; this.statements=0;
    this.now=Date.UTC(2026,9,19,12); this.fail=false;
    this.storage={
      sql:{exec:(...args)=>{const c=ctx.storage.sql.exec(...args); const rows=[...c];
        this.reads+=c.rowsRead;this.writes+=c.rowsWritten;this.statements++;return rows;}},
      transactionSync:fn=>ctx.storage.transactionSync(()=>{const result=fn();
        if(this.fail)throw new Error('injected commit failure');return result;})
    };
    this.restart=(legacy=false)=>{this.budget=new (legacy?LegacyMapBudget:MapBudget)({storage:this.storage},()=>this.now);};
  }
  async fetch(request) {
    const path=new URL(request.url).pathname;
    const call=(action,args)=>this.budget.fetch(new Request('https://budget/'+action,{method:'POST',body:JSON.stringify(args)}));
    const durable=()=>JSON.stringify(['usage','map_permits','map_health'].map(table=>[...this.ctx.storage.sql.exec('SELECT * FROM '+table+' ORDER BY 1,2')]));
    const digest=async()=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(durable()))),x=>x.toString(16).padStart(2,'0')).join('');
    const compact=()=>[...this.ctx.storage.sql.exec('PRAGMA index_info(usage)')].length>0;
    const input=(i,bucket=1)=>({attemptId:'00000000-0000-4000-8000-'+i.toString(16).padStart(12,'0'),
      bucket:bucket.toString(16).padStart(64,'0'),healthKey:'b'.repeat(64),limit:9600,ipLimit:2400,costUnits:4,spacingMs:250});
    if(path==='/migration') {
      this.restart(true);
      this.ctx.storage.sql.exec("INSERT INTO usage(day,bucket,used) VALUES('2026-10-19','all',2400)");
      const before=durable(); this.restart();
      const denied=await call('reserve',input(1));
      const legacyBalancesPreserved=denied.status===429&&!compact()&&[...this.ctx.storage.sql.exec("SELECT used FROM usage WHERE bucket='all'")][0].used===2400;
      if(!legacyBalancesPreserved)throw new Error('Live legacy balances changed');
      this.now+=86400000; const preCleanup=durable(); this.fail=true;
      let failed=false; try{await call('reserve',input(2));}catch{failed=true;}
      this.fail=false;
      const rollbackVerified=failed&&!compact()&&durable()===preCleanup;
      if(!rollbackVerified)throw new Error('Layout cleanup rollback lost data');
      if(!(await call('reserve',input(2))).ok||!(await call('dispatch',input(2))).ok)throw new Error('Migration retry failed');
      const upgradedAfterRollover=compact(), stateDigest=await digest();
      this.restart(true);
      const replay=await call('reserve',input(2)), duplicate=await call('dispatch',input(2));
      const oldCodeCanRead=replay.ok&&duplicate.status===409;
      if(!oldCodeCanRead)throw new Error('Previous code cannot read upgraded counters');
      return Response.json({legacyBalancesPreserved,rollbackVerified,upgradedAfterRollover,oldCodeCanRead,stateDigest});
    }
    this.restart();
    const backup=path.startsWith('/backup'), repeated=path.endsWith('/repeat');
    const total=repeated?(backup?1000:600):(backup?4000:2400);
    const args=i=>({...input(i,repeated?1:i%200+1),limit:backup?4000:9600,ipLimit:backup?1000:2400,
      costUnits:backup?1:4,spacingMs:backup?1050:250});
    for(let i=1;i<=total;i++) {
      const next=args(i), reserved=await call('reserve',next);
      if(!reserved.ok)throw new Error('Reservation '+i+': '+await reserved.text());
      if(!(await call('dispatch',next)).ok)throw new Error('Dispatch '+i+' failed');
      this.now+=1500;
    }
    const firstDay={reads:this.reads,writes:this.writes,statements:this.statements,bytes:this.ctx.storage.sql.databaseSize};
    const denied=await call('reserve',args(total+1));
    const rejection={status:denied.status,body:await denied.json(),retryAfter:denied.headers.get('Retry-After')};
    const stateDigest=await digest();
    this.now+=86400000;
    const next=args(total+2);await call('reserve',next);await call('dispatch',next);
    const afterCleanup={reads:this.reads-firstDay.reads,writes:this.writes-firstDay.writes,statements:this.statements-firstDay.statements};
    const rollback=args(total+3);await call('reserve',rollback);this.fail=true;
    let failed=false;try{await call('dispatch',rollback);}catch{failed=true;}
    this.fail=false;
    const retried=await call('dispatch',rollback),duplicate=await call('dispatch',rollback);
    const rollbackVerified=failed&&retried.status===200&&duplicate.status===409;
    this.restart();
    const replay=await call('reserve',next);
    const restartVerified=replay.ok&&(await call('dispatch',next)).status===409;
    if(!rollbackVerified||!restartVerified)throw new Error('Permit eligibility changed');
    return Response.json({matches:total,firstDay,rejection,stateDigest,afterCleanup,rollbackVerified,restartVerified,finalDigest:await digest()});
  }
}
export default {fetch:(request,env)=>env.AUDIT.get(env.AUDIT.idFromName(new URL(request.url).pathname)).fetch(request)};
`;
  const mf = new Miniflare(convertV4MiniflareOptions({ modules: true, script, compatibilityDate: '2026-10-15',
    durableObjects: { AUDIT: { className: 'AuditBudget', useSQLite: true } } }));
  try {
    const results = {};
    for (const path of ['/primary', '/backup', '/primary/repeat', '/backup/repeat', '/migration']) {
      const response = await mf.dispatchFetch('https://audit.test' + path);
      if (!response.ok) throw new Error(await response.text());
      results[path] = await response.json();
    }
    return results;
  } finally { await mf.dispose(); }
}
const before = await run(baseline), after = await run(current);
for (const path of Object.keys(before)) {
  for (const key of (path === '/migration'
    ? ['legacyBalancesPreserved','rollbackVerified','oldCodeCanRead','stateDigest']
    : ['matches','rejection','stateDigest','finalDigest','rollbackVerified','restartVerified']))
    assert.deepEqual(after[path][key], before[path][key], `${path}: ${key}`);
  if (path !== '/migration') {
    assert.ok(after[path].firstDay.reads < before[path].firstDay.reads);
    assert.ok(after[path].firstDay.writes < before[path].firstDay.writes);
  }
}
assert.equal(before['/migration'].upgradedAfterRollover, false);
assert.equal(after['/migration'].upgradedAfterRollover, true);
const result = { runtime: 'local Cloudflare workerd via Miniflare 5.20261012.1-beta', baseline: baselineRef,
  sourceInvariants, before, after, limitations: 'Synthetic local SQL counters, not invoices, physical-drive throughput or a DAU/hour increase. No provider or production traffic. Existing live counters upgrade only after ordinary retention leaves the table empty.' };
await writeFile(new URL('../quota-runtime-evidence.json', import.meta.url), JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify(result, null, 2));
