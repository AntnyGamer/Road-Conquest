import test from "node:test";
import assert from "node:assert/strict";
import pg from "pg";
import { readFile } from "node:fs/promises";
import { createFriends } from "../src/friends.mjs";
import { createCompetition } from "../src/competition.mjs";

test("friend status compaction preserves accepted priority, both ID orientations and username order",{skip:!process.env.DATABASE_URL},async()=>{
  const pool=new pg.Pool({connectionString:process.env.DATABASE_URL});
  try{
    await pool.query(await readFile(new URL('../schema.sql',import.meta.url),'utf8'));
    await pool.query('TRUNCATE users CASCADE');
    const users=(await pool.query(`INSERT INTO users(username_display,username_key,password_salt,password_hash)
      SELECT 'Status_'||g,'status_'||g,$1,$2 FROM generate_series(0,5) g ORDER BY g RETURNING id`,[Buffer.alloc(16),Buffer.alloc(64)])).rows;
    const owner=users[2];
    for(const [low,high,by,accepted] of [[0,2,0,false],[1,2,2,false],[2,3,2,true],[2,4,4,true]]){
      await pool.query('INSERT INTO friendships(user_low,user_high,requested_by,accepted) VALUES ($1,$2,$3,$4)',
        [users[low].id,users[high].id,users[by].id,accepted]);
    }
    const service=createFriends(pool);
    assert.deepEqual((await service.list(owner)).friends,[{username:'Status_3',status:'accepted'},
      {username:'Status_4',status:'accepted'},{username:'Status_0',status:'incoming'},{username:'Status_1',status:'outgoing'}]);
    assert.deepEqual(await service.list(users[5]),{friends:[]});
  }finally{await pool.end();}
});

test("concurrent requests preserve both users' fifty-relationship cap and notify only once", { skip: !process.env.DATABASE_URL }, async () => {
  const pool = new pg.Pool({ connectionString: process.env.DATABASE_URL });
  try {
    await pool.query(await readFile(new URL('../schema.sql', import.meta.url), 'utf8'));
    await pool.query('TRUNCATE users CASCADE');
    const users = (await pool.query(`INSERT INTO users(username_display,username_key,password_salt,password_hash)
      SELECT 'Cap_'||lpad(g::text,3,'0'),'cap_'||lpad(g::text,3,'0'),$1,$2
      FROM generate_series(0,52) g ORDER BY g RETURNING id,username_display`, [Buffer.alloc(16),Buffer.alloc(64)])).rows;
    const owner = users[0];
    await pool.query(`INSERT INTO friendships(user_low,user_high,requested_by,accepted)
      SELECT $1,id,$1,id%2=0 FROM users WHERE id=ANY($2::bigint[])`, [owner.id,users.slice(1,50).map(u=>u.id)]);
    const pushed = [];
    const service = createFriends(pool, async target => { pushed.push(String(target)); });
    const contenders = users.slice(50,52);
    const result = await Promise.allSettled(contenders.map(target => service.change(owner,target.username_display,'request')));
    assert.equal(result.filter(r=>r.status==='fulfilled').length,1);
    assert.equal(result.find(r=>r.status==='rejected').reason.status,409);
    assert.equal((await service.list(owner)).friends.length,50);
    assert.equal(pushed.length,1);
    const winner = contenders[result.findIndex(r=>r.status==='fulfilled')];
    await service.change(owner,winner.username_display,'request');
    await service.change(winner,owner.username_display,'request');
    assert.equal(pushed.length,1);
    assert.equal((await service.list(winner)).friends[0].status,'incoming');
    // A different requester must also respect the target's cap.
    await assert.rejects(service.change(users[52],owner.username_display,'request'), /limit reached/);
    assert.equal((await service.list(users[52])).friends.length,0);
    await service.change(winner,owner.username_display,'accept');
    assert.equal((await service.list(winner)).friends[0].status,'accepted');
    await service.change(owner,winner.username_display,'remove');
    await service.change(users[52],owner.username_display,'request');
    assert.equal((await service.list(owner)).friends.length,50);
    assert.equal(pushed.length,2);
  } finally { await pool.end(); }
});

test("friend consent, private weekly scores, ties, removal, username changes and deletion use account boundaries", { skip: !process.env.DATABASE_URL }, async () => {
  const pool = new pg.Pool({ connectionString: process.env.DATABASE_URL });
  const dataset = "fog-grid-v1";
  const dataVersion = "2026-10-01T00:00:00Z";
  try {
    await pool.query(await readFile(new URL("../schema.sql", import.meta.url), "utf8"));
    await pool.query("TRUNCATE users,road_catalogs CASCADE");
    const [alpha, beta, outsider] = (await pool.query(`INSERT INTO users(username_display,username_key,password_salt,password_hash)
      VALUES ('Alpha','alpha',$1,$2),('Beta','beta',$1,$2),('Outsider','outsider',$1,$2) RETURNING id`, [Buffer.alloc(16), Buffer.alloc(64)])).rows;
    const friends = createFriends(pool);
    const competition = await createCompetition(pool, { dataset, dataVersion, checkpointKey: "K".repeat(43) }, { verify: async () => {} });
    for (const user of [alpha, beta, outsider]) {
      await pool.query("INSERT INTO cell_scores(user_id,dataset) VALUES ($1,$2)", [user.id, dataset]);
      await pool.query(`INSERT INTO cell_weeks(user_id,dataset,week_start,new_cells)
        VALUES ($1,$2,date_trunc('week',CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date,2)`, [user.id, dataset]);
    }
    assert.deepEqual((await competition.friendWeek(alpha, "cells")).entries.map(r => r.username), ["Alpha"]);
    await Promise.all([friends.change(alpha, "Beta", "request"), friends.change(beta, "Alpha", "request")]);
    assert.equal((await pool.query("SELECT COUNT(*) FROM friendships")).rows[0].count, "1");
    const relation = (await friends.list(alpha)).friends[0];
    assert.notEqual(relation.status, "accepted");
    assert.deepEqual((await competition.friendWeek(alpha)).entries.map(r => r.username), ["Alpha"]);
    const requester = relation.status === "outgoing" ? alpha : beta;
    const recipient = requester === alpha ? beta : alpha;
    await assert.rejects(friends.change(requester, recipient === beta ? "Beta" : "Alpha", "accept"), /incoming/u);
    await friends.change(recipient, requester === alpha ? "Alpha" : "Beta", "accept");
    const pairStandings = (await competition.friendWeek(alpha, "cells")).entries;
    assert.deepEqual(pairStandings.map(r => [r.username, r.rank]), [["Alpha", 1], ["Beta", 1]]);
    assert.equal(new Set(pairStandings.map(r => r.username)).size, pairStandings.length,
      'The unique friendship schema must not produce duplicate ranked participants');
    assert.deepEqual((await competition.friendWeek(outsider, "cells")).entries.map(r => r.username), ["Outsider"]);
    await pool.query("UPDATE users SET leaderboard_visible=FALSE WHERE id=$1", [outsider.id]);
    const hiddenWeek = await competition.friendWeek(outsider, "cells");
    assert.deepEqual(hiddenWeek.entries, []);
    assert.match(hiddenWeek.week_start, /^\d{4}-\d{2}-\d{2}$/u);
    await pool.query("UPDATE users SET leaderboard_visible=TRUE WHERE id=$1", [outsider.id]);
    await friends.change(outsider, "Beta", "remove");
    assert.equal((await friends.list(alpha)).friends[0].status, "accepted");
    await pool.query("UPDATE users SET leaderboard_visible=FALSE WHERE id=$1", [beta.id]);
    assert.deepEqual((await competition.friendWeek(alpha, "cells")).entries.map(r => r.username), ["Alpha"]);
    await pool.query("UPDATE users SET leaderboard_visible=TRUE,leaderboard_eligible=FALSE WHERE id=$1", [beta.id]);
    assert.deepEqual((await competition.friendWeek(alpha)).entries.map(r => r.username), ["Alpha"]);
    await pool.query("UPDATE users SET leaderboard_eligible=TRUE WHERE id=$1", [beta.id]);
    await pool.query("UPDATE users SET leaderboard_visible=TRUE,username_display='Renamed',username_key='renamed' WHERE id=$1", [beta.id]);
    assert.equal((await friends.list(alpha)).friends[0].username, "Renamed");
    // Previous weeks cannot inflate this week's standings.
    await pool.query(`INSERT INTO cell_weeks(user_id,dataset,week_start,new_cells)
      VALUES ($1,$2,(date_trunc('week',CURRENT_TIMESTAMP AT TIME ZONE 'UTC') - INTERVAL '7 days')::date,99)`, [alpha.id, dataset]);
    assert.equal((await competition.friendWeek(alpha, "cells")).entries[0].cells, 2);
    await assert.rejects(friends.change(alpha, "Alpha", "request"), /another/u);
    await assert.rejects(friends.change(alpha, "Renamed", "inject"), /Invalid/u);
    await assert.rejects(competition.friendWeek(alpha, "cells;DELETE"), /Choose/u);
    await friends.change(alpha, "Renamed", "remove");
    assert.deepEqual((await friends.list(beta)).friends, []);
    await friends.change(alpha, "Renamed", "request");
    await pool.query("DELETE FROM users WHERE id=$1", [beta.id]);
    assert.deepEqual((await friends.list(alpha)).friends, []);
    assert.equal((await pool.query("SELECT COUNT(*) FROM cell_weeks WHERE user_id=$1", [beta.id])).rows[0].count, "0");
  } finally { await pool.end(); }
});
