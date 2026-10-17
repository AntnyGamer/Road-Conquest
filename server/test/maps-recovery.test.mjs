import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { createHmac, randomUUID } from 'node:crypto';
import { MapBudget, createMapsGateway } from '../maps/worker.mjs';

const trace = { mode: 'drive', waypoints: Array.from({ length: 108 }, (_, i) => ({
  location: [-74 + i * .0001, 40], timestamp: new Date(Date.UTC(2026,0,1) + i * 4000).toISOString()
})) };
function store() {
  const db = new DatabaseSync(':memory:');
  let now = Date.UTC(2026,9,17,12), failInsert = false;
  const queries=[];
  const ctx = { storage: {
    sql: { exec(q,...args) {
      queries.push(q);
      if (failInsert && q.startsWith('INSERT INTO map_permits')) throw new Error('injected write failure');
      const s = db.prepare(q); return s.columns().length ? s.all(...args) : (s.run(...args), []);
    } },
    transactionSync(fn) {
      db.exec('BEGIN');
      try { const result = fn(); db.exec('COMMIT'); return result; }
      catch(error) { db.exec('ROLLBACK'); throw error; }
    }
  } };
  const value = { db, ctx, queries, now: () => now, advance(ms) { now += ms; }, fail(value) { failInsert = value; } };
  value.budget = new MapBudget(ctx, value.now);
  value.call = (action, args, budget = value.budget) => budget.fetch(new Request('https://budget/'+action,
    { method:'POST',body:JSON.stringify(args) }));
  value.args = () => ({ attemptId:randomUUID(), bucket:'a'.repeat(64), healthKey:'b'.repeat(64), limit:9600,ipLimit:2400,costUnits:8,spacingMs:250 });
  value.used = () => db.prepare("SELECT COALESCE(SUM(used),0) n FROM usage WHERE bucket='units:all'").get().n;
  return value;
}
function gatewayEnv(s) {
  return { GEOAPIFY_API_KEY:'synthetic-only',MAP_BUDGET:{idFromName:x=>x,get:()=>({fetch:(url,init)=>s.budget.fetch(new Request(url,init))})} };
}
const req = signal => new Request('https://maps.example/v1/mapmatching', {method:'POST',headers:{'CF-Connecting-IP':'198.51.100.1'},body:JSON.stringify(trace),signal});

test('reservation replay survives restart; dispatch and refund are at most once', async t => {
  const s=store();t.after(()=>s.db.close());const args=s.args();
  const first=await (await s.call('reserve',args)).json();
  assert.equal(first.managed,true);assert.equal(first.waitMs,0);
  assert.equal((await s.call('reserve',args,new MapBudget(s.ctx,s.now))).status,200);
  assert.equal(s.used(),8);
  assert.equal((await s.call('reserve',{...args,costUnits:4})).status,409);
  assert.equal((await s.call('dispatch',args)).status,200);
  assert.equal((await s.call('dispatch',args)).status,409);
  await s.call('cancel',args);await s.call('cancel',args);
  assert.equal(s.used(),8,'a dispatched attempt can be billable even if its reply is lost');
});

test('daily cleanup marker survives restart without rescanning all current-day permits', async t => {
  const s=store();t.after(()=>s.db.close());
  await s.call('reserve',s.args());
  const deletes=()=>s.queries.filter(q=>q.startsWith('DELETE FROM')).length;
  assert.equal(deletes(),3);
  await s.call('reserve',s.args(),new MapBudget(s.ctx,s.now));
  assert.equal(deletes(),3);
  s.advance(86_400_000);
  await s.call('reserve',s.args(),new MapBudget(s.ctx,s.now));
  assert.equal(deletes(),6);
  assert.equal(s.db.prepare('SELECT COUNT(*) n FROM map_permits').get().n,1);
});
test('cancel before dispatch refunds once and retains provider pacing', async t => {
  const s=store();t.after(()=>s.db.close());const args=s.args();
  await s.call('reserve',args);await s.call('cancel',args);await s.call('cancel',args);
  assert.equal(s.used(),0);assert.equal((await s.call('dispatch',args)).status,409);
  assert.equal((await s.call('reserve',args)).status,409);
  const next=await (await s.call('reserve',s.args())).json();
  assert.equal(next.waitMs,250);
});

test('refunds never restore global or client attempt limits and replays never spend them twice', async t => {
  const s=store();t.after(()=>s.db.close());
  const first={...s.args(),limit:8,ipLimit:4,costUnits:4};
  await s.call('reserve',first);await s.call('reserve',first);await s.call('cancel',first);
  assert.equal(s.used(),0);
  assert.equal((await s.call('reserve',{...first,attemptId:randomUUID()})).headers.get('X-RoadConquest-Error'),'budget_client');
  const second={...first,attemptId:randomUUID(),bucket:'c'.repeat(64)};
  assert.equal((await s.call('reserve',second)).status,200);await s.call('cancel',second);
  assert.equal((await s.call('reserve',{...first,attemptId:randomUUID(),bucket:'d'.repeat(64)})).headers.get('X-RoadConquest-Error'),'budget_attempts');
  assert.equal(s.db.prepare("SELECT used FROM usage WHERE bucket='attempts:all'").get().used,2);
  assert.equal(s.used(),0);
});
test('permit and debit roll back together on storage failure', async t => {
  const s=store();t.after(()=>s.db.close());const args=s.args();s.fail(true);
  await assert.rejects(s.call('reserve',args),/injected/);assert.equal(s.used(),0);
  assert.equal(s.db.prepare('SELECT COUNT(*) n FROM map_permits').get().n,0);
  s.fail(false);assert.equal((await s.call('reserve',args)).status,200);assert.equal(s.used(),8);
});
test('a queued prior-day permit cannot dispatch against the new day', async t => {
  const s=store();t.after(()=>s.db.close());const args=s.args();
  await s.call('reserve',args);s.advance(86_400_000);
  assert.equal((await s.call('dispatch',args)).status,409);
  await s.call('cancel',args);assert.equal(s.used(),0);
  assert.equal((await s.call('reserve',args)).status,200);assert.equal(s.used(),8);
});
test('aborted queued matching never reaches provider and releases its unspent reservation', async t => {
  const s=store();t.after(()=>s.db.close());const control=new AbortController();
  await s.call('reserve',s.args()); // Occupy first slot so gateway enters wait.
  let calls=0;const worker=createMapsGateway(async()=>{calls++;return Response.json({});},
    {wait:async()=>{control.abort();throw control.signal.reason;}});
  assert.equal((await worker.fetch(req(control.signal),gatewayEnv(s))).status,503);
  assert.equal(calls,0);assert.equal(s.used(),8,'only the initial unrelated reservation remains');
  assert.equal(s.db.prepare("SELECT COUNT(*) n FROM map_permits WHERE state='cancelled'").get().n,1);
});
test('lost dispatch acknowledgement is charged conservatively without a second upstream dispatch', async t => {
  const s=store();t.after(()=>s.db.close());const settings=gatewayEnv(s), original=settings.MAP_BUDGET.get;
  settings.MAP_BUDGET.get=()=>({fetch:async(url,init)=>{
    const result=await original().fetch(url,init);
    if(url.endsWith('/dispatch'))throw new Error('lost acknowledgement');return result;
  }});
  let calls=0;const worker=createMapsGateway(async()=>{calls++;return Response.json({});},{wait:async()=>{}});
  assert.equal((await worker.fetch(req(),settings)).status,503);
  assert.equal(calls,0);assert.equal(s.used(),8);
  assert.equal(s.db.prepare('SELECT state FROM map_permits').get().state,'dispatched');
});

test('lost reservation acknowledgement releases work proven never dispatched', async t => {
  const s=store();t.after(()=>s.db.close());const settings=gatewayEnv(s), original=settings.MAP_BUDGET.get;
  settings.MAP_BUDGET.get=()=>({fetch:async(url,init)=>{
    const result=await original().fetch(url,init);
    if(url.endsWith('/reserve'))throw new Error('lost acknowledgement');return result;
  }});
  let calls=0;const worker=createMapsGateway(async()=>{calls++;return Response.json({});});
  assert.equal((await worker.fetch(req(),settings)).status,503);
  assert.equal(calls,0);assert.equal(s.used(),0);
  assert.equal(s.db.prepare('SELECT state FROM map_permits').get().state,'cancelled');
});

test('an outage learned during queueing blocks dispatch and refunds only the queued request', async t => {
  const s=store();t.after(()=>s.db.close());const previous=s.args();
  await s.call('reserve',previous);await s.call('dispatch',previous);
  let calls=0;const worker=createMapsGateway(async()=>{calls++;return Response.json({});},
    {wait:async()=>{
      const queued=s.db.prepare("SELECT id FROM map_permits WHERE state='reserved'").get();
      const key=createHmac('sha256','synthetic-only').update('health:matching').digest('hex');
      await s.call('failure',{...previous,healthKey:key,status:503});
      assert.ok(queued.id);
    }});
  const blocked=await worker.fetch(req(),gatewayEnv(s));
  assert.equal(blocked.status,503);assert.equal(blocked.headers.get('Retry-After'),'2');
  assert.equal(blocked.headers.get('X-RoadConquest-Error'),'provider_cooldown');
  assert.equal(calls,0);assert.equal(s.used(),8);
  assert.equal(s.db.prepare("SELECT COUNT(*) n FROM map_permits WHERE state='cancelled'").get().n,1);
});
test('upstream cancellation signal is propagated and dispatched requests are not refunded', async t => {
  const s=store();t.after(()=>s.db.close());const control=new AbortController();
  const worker=createMapsGateway(async(_url,init)=>{
    control.abort();assert.equal(init.signal.aborted,true);throw control.signal.reason;
  },{wait:async()=>{}});
  assert.equal((await worker.fetch(req(control.signal),gatewayEnv(s))).status,503);
  assert.equal(s.used(),8);
});
test('short shared outage pause recovers immediately at the next permitted probe', async t => {
  const s=store();t.after(()=>s.db.close());let calls=0;
  const worker=createMapsGateway(async()=>{calls++;return calls===1?new Response('',{status:503}):Response.json({type:'FeatureCollection',features:[]});},{wait:async()=>{}});
  const settings=gatewayEnv(s);
  assert.equal((await worker.fetch(req(),settings)).status,502);
  const blocked=await worker.fetch(req(),settings);assert.equal(blocked.status,503);
  assert.equal(blocked.headers.get('X-RoadConquest-Error'),'provider_cooldown');
  assert.equal(s.used(),8);s.advance(2000);
  assert.equal((await worker.fetch(req(),settings)).status,200);
  assert.equal(calls,2);assert.equal(s.used(),16);
  assert.equal(s.db.prepare('SELECT COUNT(*) n FROM map_health').get().n,0);
});

test('200 staggered callers protect allowance during failure without withholding recovered work', async t => {
  for (const recovering of [false,true]) {
    const s=store();t.after(()=>s.db.close());const started=s.now();let calls=0, successes=0;
    const worker=createMapsGateway(async()=>{
      calls++;
      return recovering && s.now()-started>=10_000
        ? Response.json({type:'FeatureCollection',features:[]}) : new Response('',{status:503});
    },{wait:async()=>{}});
    for(let i=0;i<200;i++) {
      const request=req();request.headers.set('CF-Connecting-IP','198.51.100.'+(i+1));
      const response=await worker.fetch(request,gatewayEnv(s));
      if(response.ok)successes++;
      await response.body?.cancel();s.advance(1000);
    }
    assert.equal(calls,recovering?195:100);
    assert.equal(successes,recovering?190:0);
    assert.equal(s.used(),calls*8,'each 108-point dispatch conservatively keeps two credits');
  }
});
test('only one recovery probe is admitted across object restarts; abandoned probe lease expires', async t => {
  const s=store();t.after(()=>s.db.close());const args=s.args();
  await s.call('reserve',args);await s.call('dispatch',args);await s.call('failure',{...args,status:503});
  s.advance(2000);const probe=s.args();
  assert.equal((await (await s.call('reserve',probe)).json()).probe,true);
  assert.equal((await s.call('reserve',s.args(),new MapBudget(s.ctx,s.now))).status,503);
  assert.equal(s.used(),16);s.advance(35000);
  assert.equal((await s.call('reserve',s.args())).status,200);
  assert.equal((await s.call('dispatch',probe)).status,503,'an expired probe must not resume beside its replacement');
  await s.call('cancel',probe);assert.equal(s.used(),16);
});

test('late failure from an older request does not cancel an active recovery probe lease', async t => {
  const s = store(); t.after(() => s.db.close());
  const first = s.args(), late = s.args();
  await s.call('reserve', first);
  await s.call('dispatch', first);
  await s.call('reserve', late);
  await s.call('dispatch', late);
  await s.call('failure', { ...first, status: 503 });
  s.advance(2_000);
  const probe = s.args();
  const reservation = await (await s.call('reserve', probe)).json();
  assert.equal(reservation.probe, true);
  await s.call('dispatch', probe);
  // An older in-flight request failed after this probe was admitted.
  await s.call('failure', { ...late, status: 503 });
  s.advance(2_000);
  const blocked = await s.call('reserve', s.args());
  assert.equal(blocked.status, 503, 'only the leased recovery probe may reach the supplier');
  assert.equal(s.db.prepare('SELECT probe_id FROM map_health').get().probe_id, probe.attemptId);
  await s.call('success', probe);
  assert.equal((await s.call('reserve', s.args())).status, 200);
});

test('a stale healthy permit cannot dispatch after its bounded lease', async t => {
  const s=store();t.after(()=>s.db.close());const args=s.args();
  await s.call('reserve',args);s.advance(35_001);
  assert.equal((await s.call('dispatch',args)).status,409);
  await s.call('cancel',args);assert.equal(s.used(),0);
});

test('network timeouts share outage state, but caller cancellation does not', async t => {
  const s=store();t.after(()=>s.db.close());let calls=0;
  const worker=createMapsGateway(async()=>{calls++;throw new Error('connection failed');});
  const settings=gatewayEnv(s);
  assert.equal((await worker.fetch(req(),settings)).status,503);
  assert.equal((await worker.fetch(req(),settings)).status,503);
  assert.equal(calls,1);assert.equal(s.used(),8);
  const other=store();t.after(()=>other.db.close());const control=new AbortController();
  const cancelled=createMapsGateway(async()=>{control.abort();throw control.signal.reason;});
  await cancelled.fetch(req(control.signal),gatewayEnv(other));
  assert.equal(other.db.prepare('SELECT COUNT(*) n FROM map_health').get().n,0);
});
test('explicit rate-limit instruction is honored and key rotation is not trapped in old cooldown', async t => {
  const s=store();t.after(()=>s.db.close());let failing=true,calls=0;
  const worker=createMapsGateway(async()=>{calls++;return failing?new Response('',{status:429,headers:{'Retry-After':'60'}}):Response.json({type:'FeatureCollection',features:[]});},{wait:async()=>{}});
  const settings=gatewayEnv(s);await worker.fetch(req(),settings);s.advance(1000);
  assert.equal((await worker.fetch(req(),settings)).status,503);assert.equal(calls,1);
  settings.GEOAPIFY_API_KEY='rotated-synthetic-key';failing=false;
  assert.equal((await worker.fetch(req(),settings)).status,200);assert.equal(calls,2);
});
test('recovering provider NoMatch and caller errors are not treated as an outage', async t => {
  const s=store();t.after(()=>s.db.close());let status=503;
  const worker=createMapsGateway(async()=>new Response('',{status}),{wait:async()=>{}});
  const settings=gatewayEnv(s);await worker.fetch(req(),settings);s.advance(2000);status=400;
  assert.equal((await worker.fetch(req(),settings)).status,502);
  assert.equal(s.db.prepare('SELECT COUNT(*) n FROM map_health').get().n,0);
  // A valid Geoapify empty response is reachable and must not trigger another provider.
  const empty=createMapsGateway(async()=>Response.json({type:'FeatureCollection',features:[]}),{wait:async()=>{}});
  assert.equal((await empty.fetch(req(),settings)).status,200);
});
