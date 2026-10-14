import test from "node:test";
import assert from "node:assert/strict";
import pg from "pg";
import { readFile } from "node:fs/promises";
import { createFriends } from "../src/friends.mjs";
import { createCompetition } from "../src/competition.mjs";

test("friend consent, private weekly scores, ties, removal, username changes and deletion use account boundaries", { skip: !process.env.DATABASE_URL }, async () => {
  const pool = new pg.Pool({ connectionString: process.env.DATABASE_URL });
  const dataset = "c".repeat(64);
  const dataVersion = "2026-10-01T00:00:00Z";
  try {
    await pool.query(await readFile(new URL("../schema.sql", import.meta.url), "utf8"));
    await pool.query("TRUNCATE users,road_catalogs CASCADE");
    await pool.query("INSERT INTO road_catalogs VALUES ($1,$2,CURRENT_TIMESTAMP)", [dataset, dataVersion]);
    await pool.query("INSERT INTO road_edges VALUES ($1,1,2,10)", [dataset]);
    const [alpha, beta, outsider] = (await pool.query(`INSERT INTO users(username_display,username_key,password_salt,password_hash)
      VALUES ('Alpha','alpha',$1,$2),('Beta','beta',$1,$2),('Outsider','outsider',$1,$2) RETURNING id`, [Buffer.alloc(16), Buffer.alloc(64)])).rows;
    const friends = createFriends(pool);
    const competition = await createCompetition(pool, { dataset, dataVersion, checkpointKey: "K".repeat(43) }, { verify: async () => {}, match: async () => {} });
    for (const user of [alpha, beta, outsider]) {
      await pool.query("INSERT INTO competition_scores(user_id,dataset) VALUES ($1,$2)", [user.id, dataset]);
      await pool.query(`INSERT INTO competition_weeks(user_id,dataset,week_start,verified_distance_mm,verified_new_roads)
        VALUES ($1,$2,date_trunc('week',CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date,100000,2)`, [user.id, dataset]);
    }
    assert.deepEqual((await competition.friendWeek(alpha, "roads")).entries.map(r => r.username), ["Alpha"]);
    await Promise.all([friends.change(alpha, "Beta", "request"), friends.change(beta, "Alpha", "request")]);
    assert.equal((await pool.query("SELECT COUNT(*) FROM friendships")).rows[0].count, "1");
    const relation = (await friends.list(alpha)).friends[0];
    assert.notEqual(relation.status, "accepted");
    const requester = relation.status === "outgoing" ? alpha : beta;
    const recipient = requester === alpha ? beta : alpha;
    await assert.rejects(friends.change(requester, recipient === beta ? "Beta" : "Alpha", "accept"), /incoming/u);
    await friends.change(recipient, requester === alpha ? "Alpha" : "Beta", "accept");
    assert.deepEqual((await competition.friendWeek(alpha, "roads")).entries.map(r => [r.username, r.rank]), [["Alpha", 1], ["Beta", 1]]);
    assert.deepEqual((await competition.friendWeek(outsider, "roads")).entries.map(r => r.username), ["Outsider"]);
    await friends.change(outsider, "Beta", "remove");
    assert.equal((await friends.list(alpha)).friends[0].status, "accepted");
    await pool.query("UPDATE users SET leaderboard_visible=FALSE WHERE id=$1", [beta.id]);
    assert.deepEqual((await competition.friendWeek(alpha, "miles")).entries.map(r => r.username), ["Alpha"]);
    await pool.query("UPDATE users SET leaderboard_visible=TRUE,username_display='Renamed',username_key='renamed' WHERE id=$1", [beta.id]);
    assert.equal((await friends.list(alpha)).friends[0].username, "Renamed");
    // Previous weeks cannot inflate this week's standings.
    await pool.query(`INSERT INTO competition_weeks(user_id,dataset,week_start,verified_distance_mm,verified_new_roads)
      VALUES ($1,$2,(date_trunc('week',CURRENT_TIMESTAMP AT TIME ZONE 'UTC') - INTERVAL '7 days')::date,999999,99)`, [alpha.id, dataset]);
    assert.equal((await competition.friendWeek(alpha, "roads")).entries[0].roads, 2);
    await assert.rejects(friends.change(alpha, "Alpha", "request"), /another/u);
    await assert.rejects(friends.change(alpha, "Renamed", "inject"), /Invalid/u);
    await assert.rejects(competition.friendWeek(alpha, "roads;DELETE"), /Choose/u);
    await friends.change(alpha, "Renamed", "remove");
    assert.deepEqual((await friends.list(beta)).friends, []);
    await friends.change(alpha, "Renamed", "request");
    await pool.query("DELETE FROM users WHERE id=$1", [beta.id]);
    assert.deepEqual((await friends.list(alpha)).friends, []);
    assert.equal((await pool.query("SELECT COUNT(*) FROM competition_weeks WHERE user_id=$1", [beta.id])).rows[0].count, "0");
  } finally { await pool.end(); }
});
