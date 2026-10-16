import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import pg from "pg";
import { GRID_ID, LATITUDE_STEP, LONGITUDE_STEP, cellFor } from "../src/cells.mjs";
import { createCompetition } from "../src/competition.mjs";

const connectionString = process.env.DATABASE_URL;
const dataset = GRID_ID;
const legacyDataset = "a".repeat(64);
const dataVersion = "2026-10-01T00:00:00Z";
const config = { dataset, dataVersion, cloudProject: "123456789", checkpointKey: "K".repeat(43) };

test("top fifty ranks preserve ties across the cutoff and exclude hidden or ineligible accounts before limiting", { skip: !connectionString }, async () => {
  const pool = new pg.Pool({ connectionString });
  try {
    await pool.query(await readFile(new URL('../schema.sql', import.meta.url), 'utf8'));
    await pool.query('TRUNCATE users CASCADE');
    await pool.query(`INSERT INTO users(username_display,username_key,password_salt,password_hash,leaderboard_visible,leaderboard_eligible)
      SELECT 'Rank_'||lpad(g::text,3,'0'),'rank_'||lpad(g::text,3,'0'),$1,$2,g>2,g<>3
      FROM generate_series(1,80) g`, [Buffer.alloc(16),Buffer.alloc(64)]);
    await pool.query(`INSERT INTO cell_scores(user_id,dataset,cells)
      SELECT id,$1,CASE WHEN username_key<='rank_010' THEN 300 WHEN username_key<='rank_060' THEN 200 ELSE 100 END FROM users`, [dataset]);
    const all = (await pool.query(`SELECT u.username_display AS username,u.username_key,s.cells,u.leaderboard_visible,u.leaderboard_eligible
      FROM users u JOIN cell_scores s ON s.user_id=u.id WHERE s.dataset=$1`, [dataset])).rows;
    const eligible = all.filter(row => row.leaderboard_visible && row.leaderboard_eligible).sort((a,b) =>
      Number(b.cells)-Number(a.cells) || a.username_key.localeCompare(b.username_key));
    const expected = eligible.slice(0,50).map(row => ({ username: row.username, cells: Number(row.cells),
      rank: 1+eligible.filter(other => Number(other.cells)>Number(row.cells)).length }));
    const service = await createCompetition(pool,config,{ verify: async () => {} });
    const actual = (await service.leaderboard()).entries;
    assert.equal(actual.length,50);
    assert.equal(actual.at(-1).rank,8);
    assert.deepEqual(actual,expected);
  } finally { await pool.end(); }
});

function fixes() {
  const now = Date.now();
  const cell = cellFor(40, -75);
  const lat = -90 + (cell.row + 0.5) * LATITUDE_STEP;
  const lon = -180 + (cell.column + 0.5) * LONGITUDE_STEP;
  return [
    { lat, lon, accuracy: 3, speed: 10, time: now - 5000, elapsed: 10000, mock: false },
    { lat, lon: lon + 0.0005, accuracy: 3, speed: 10, time: now, elapsed: 15000, mock: false }
  ];
}

test("real PostgreSQL protects simultaneous scoring, retries, derived cells, privacy, ties and account boundaries", { skip: !connectionString }, async () => {
  const pool = new pg.Pool({ connectionString });
  try {
    await pool.query(await readFile(new URL("../schema.sql", import.meta.url), "utf8"));
    await pool.query("TRUNCATE users, road_catalogs CASCADE");
    await pool.query("INSERT INTO road_catalogs(dataset,data_version) VALUES ($1,$2)", [legacyDataset, dataVersion]);
    const users = await pool.query(`INSERT INTO users(username_display,username_key,password_salt,password_hash)
      VALUES ('Alpha','alpha',$1,$2),('Beta','beta',$1,$2) RETURNING id`, [Buffer.alloc(16), Buffer.alloc(64)]);
    const [alpha, beta] = users.rows;
    let verifications = 0;
    const service = await createCompetition(pool, config, { verify: async () => { verifications++; } });
    // Old/local-looking totals remain stored but never become hardened verified scores.
    await pool.query("INSERT INTO competition_scores(user_id,dataset,distance_mm) VALUES ($1,$2,999999999999)", [alpha.id, legacyDataset]);
    await pool.query("INSERT INTO competition_roads(user_id,dataset,way_id) VALUES ($1,$2,100),($1,$2,101),($1,$2,999)", [alpha.id, legacyDataset]);
    await pool.query(`INSERT INTO competition_weeks(user_id,dataset,week_start,distance_mm,new_roads)
      VALUES ($1,$2,date_trunc('week',CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date,999999999999,999)`, [alpha.id, legacyDataset]);
    assert.deepEqual(await service.totals(alpha), { verified: true, scoring_policy: 3, dataset, cells: 0 });
    assert.deepEqual((await service.leaderboard("cells")).entries, []);
    assert.equal((await service.friendWeek(alpha, "cells")).entries[0].cells, 0);
    const challenge = await service.begin(alpha);
    const points = fixes();
    const evidence = JSON.stringify({ ...challenge, fixes: points });
    const oldEvidence = JSON.stringify({ ...challenge, sequence: 9, fixes: points });
    const { evidenceHash } = await import("../src/scoring.mjs");
    await pool.query(`INSERT INTO cell_receipts(run_id,sequence,user_id,evidence_hash,response)
      VALUES ($1,9,$2,$3,'{"miles":999999999999,"roads":999}'::jsonb)`, [challenge.run, alpha.id, evidenceHash(oldEvidence)]);
    await assert.rejects(service.submit(alpha, oldEvidence, "test"), /older scoring policy/u);
    const results = await Promise.all([service.submit(alpha, evidence, "test"), service.submit(alpha, evidence, "test")]);
    assert.deepEqual(results[0], results[1]);
    assert.equal(results[0].cells, 1);
    assert.equal(results[0].credited_cells, 1);
    assert.equal((await pool.query("SELECT sequence FROM cell_runs WHERE user_id=$1", [alpha.id])).rows[0].sequence, 1);
    assert.equal((await pool.query("SELECT count(*) FROM cell_receipts WHERE user_id=$1 AND sequence=0", [alpha.id])).rows[0].count, "1");
    assert.deepEqual(results[0].accepted_cells, [cellFor(points[0].lat, points[0].lon)]);
    assert.equal(results[0].verified, true);
    assert.equal(results[0].scoring_policy, 3);
    assert.equal((await pool.query("SELECT distance_mm FROM competition_scores WHERE user_id=$1", [alpha.id])).rows[0].distance_mm, "999999999999");
    const weekScore = (await pool.query("SELECT new_cells FROM cell_weeks WHERE user_id=$1", [alpha.id])).rows[0];
    assert.equal(Number(weekScore.new_cells), 1);
    const watermark = (await pool.query(
      "SELECT last_verified_fix FROM cell_scores WHERE user_id=$1 AND dataset=$2", [alpha.id, dataset]
    )).rows[0].last_verified_fix;
    assert.deepEqual(Object.keys(watermark).sort(), ["fingerprint", "sealed", "time", "version"]);
    assert.equal(watermark.time, points.at(-1).time);
    assert.match(watermark.fingerprint, /^[A-Za-z0-9_-]{43}$/u);
    const beforeRetry = verifications;
    assert.deepEqual(await service.submit(alpha, evidence, "same token"), results[0]);
    assert.equal(verifications, beforeRetry); // Response loss does not require decoding a replayed token.
    const realNow = Date.now;
    try {
      Date.now = () => realNow() + 180000;
      assert.deepEqual(await service.submit(alpha, evidence, "same token"), results[0]);
      assert.equal(verifications, beforeRetry);
      const staleUncommitted = JSON.stringify({ ...challenge, sequence: 1, fixes: points });
      await assert.rejects(service.submit(alpha, staleUncommitted, "test"), /stale GPS/u);
      assert.equal(verifications, beforeRetry);
      await assert.rejects(service.submit(alpha, JSON.stringify({ ...challenge,
        fixes: points.map(f => ({ ...f, speed: 12 })) }), "test"), /already used/u);
    } finally { Date.now = realNow; }
    await assert.rejects(service.submit(alpha, JSON.stringify({ ...challenge, fixes: points.map(f => ({ ...f, speed: 12 })) }), "test"), /already used/u);
    await assert.rejects(service.submit(beta, evidence, "test"), /challenge/u);
    assert.equal((await service.leaderboard("cells")).entries[0].username, "Alpha");
    await pool.query("UPDATE users SET leaderboard_visible=FALSE");
    assert.deepEqual((await service.leaderboard("cells")).entries, []);
    await pool.query("UPDATE users SET leaderboard_visible=TRUE");
    assert.equal((await service.leaderboard("cells")).entries[0].username, "Alpha");
    const nextFix = {
      ...points[1], lon: points[1].lon + 0.0005, time: points[1].time + 5000, elapsed: points[1].elapsed + 5000
    };
    const alteredBoundary = JSON.stringify({ ...challenge, sequence: 1, fixes: [
      { ...points[1], lat: points[1].lat + 0.00001 }, nextFix
    ] });
    await assert.rejects(service.submit(alpha, alteredBoundary, "test"), /Overlapping/u);
    const second = JSON.stringify({ ...challenge, sequence: 1, fixes: [points[1], nextFix] });
    const repeat = await service.submit(alpha, second, "test");
    assert.equal(repeat.cells, 1);
    assert.equal(repeat.credited_cells, 0);
    assert.equal((await pool.query("SELECT sequence FROM cell_runs WHERE user_id=$1", [alpha.id])).rows[0].sequence, 2);
    const repeatedWeek = (await pool.query("SELECT new_cells FROM cell_weeks WHERE user_id=$1", [alpha.id])).rows[0];
    assert.equal(Number(repeatedWeek.new_cells), 1);
    const betaRun = await service.begin(beta);
    await service.submit(beta, JSON.stringify({ ...betaRun, fixes: points }), "test");
    const ties = (await service.leaderboard("cells")).entries;
    assert.deepEqual(ties.map(r => [r.username, r.rank]), [["Alpha", 1], ["Beta", 1]]);
    assert.deepEqual(Object.keys(ties[0]).sort(), ["cells", "rank", "username"]);
    await pool.query("UPDATE users SET leaderboard_visible=FALSE WHERE id=$1", [alpha.id]);
    assert.deepEqual((await service.leaderboard("cells")).entries.map(r => r.username), ["Beta"]);
    await pool.query("UPDATE users SET leaderboard_eligible=FALSE WHERE id=$1", [beta.id]);
    assert.deepEqual((await service.leaderboard("cells")).entries, []);
    // A new run does not permit an old journey to count again.
    const restarted = await service.begin(alpha);
    await assert.rejects(service.submit(alpha, JSON.stringify({ ...restarted, fixes: points }), "test"), /Overlapping/u);
    const jumped = [
      { ...nextFix, lat: 0, lon: 0, time: nextFix.time + 1000, elapsed: nextFix.elapsed + 1000 },
      { ...nextFix, lat: 0, lon: 0.0005, time: nextFix.time + 4000, elapsed: nextFix.elapsed + 4000 }
    ];
    const stableTotals = await service.totals(alpha);
    await assert.rejects(service.submit(alpha, JSON.stringify({ ...restarted, fixes: jumped }), "test"), /Impossible travel/u);
    assert.deepEqual(await service.totals(alpha), stableTotals);
    assert.equal((await pool.query("SELECT sequence FROM cell_runs WHERE user_id=$1", [alpha.id])).rows[0].sequence, 0);
    // Moderation cannot be bypassed with a stale authenticated user or while attestation is in flight.
    await assert.rejects(service.begin(beta), /not eligible/u);
    const moderationRace = await createCompetition(pool, config, {
      verify: async () => { await pool.query("UPDATE users SET leaderboard_eligible=FALSE WHERE id=$1", [alpha.id]); },
    });
    await assert.rejects(moderationRace.submit(alpha, JSON.stringify({ ...restarted, fixes: jumped }), "test"), /not eligible/u);
    assert.deepEqual(await service.totals(alpha), stableTotals);
    await pool.query("UPDATE users SET leaderboard_eligible=TRUE WHERE id=$1", [alpha.id]);
    await assert.rejects(service.leaderboard("cells;DROP TABLE users"), /Choose/u);
    await assert.rejects(createCompetition(pool, { ...config, dataset: "b".repeat(64) }, {}), /grid/u);
    const untrusted = await createCompetition(pool, config, { verify: async () => { throw new Error("bad integrity"); } });
    const oldTotal = await service.totals(alpha);
    await assert.rejects(untrusted.submit(alpha, JSON.stringify({ ...restarted, fixes: points }), "bad"), /bad integrity/u);
    assert.deepEqual(await service.totals(alpha), oldTotal);
  } finally { await pool.end(); }
});
