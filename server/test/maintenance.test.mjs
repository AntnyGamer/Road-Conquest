import test from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import pg from "pg";
import { cleanupExpiredState } from "../src/maintenance.mjs";
import { limitedApiDatabase } from "./helpers/limited-api-role.mjs";

test("expiry cleanup and account deletion do not hold opposite table locks", {
  skip: !process.env.DATABASE_URL, timeout: 10000
}, async () => {
  const admin = new pg.Pool({ connectionString: process.env.DATABASE_URL });
  let runtime;
  let deletion;
  let cleaning;
  try {
    runtime = new pg.Pool({ connectionString: await limitedApiDatabase(admin, process.env.DATABASE_URL) });
    await admin.query("TRUNCATE users, auth_rate_limits, road_catalogs CASCADE");
    const users = (await admin.query(`INSERT INTO users(username_display,username_key,password_salt,password_hash)
      VALUES ('Expired','expired',$1,$2),('Current','current',$1,$2),('Retained','retained',$1,$2) RETURNING id`,
    [Buffer.alloc(16), Buffer.alloc(64)])).rows;
    const dataset = "a".repeat(64);
    await admin.query("INSERT INTO road_catalogs(dataset,data_version) VALUES ($1,'test')", [dataset]);
    for (const [index, user] of users.entries()) {
      const expired = index !== 1;
      const past = new Date(Date.now() - 3 * 86400000);
      const end = new Date(Date.now() + (expired ? -2 * 86400000 : 60000));
      await admin.query("INSERT INTO sessions(token_hash,user_id,created_at,expires_at) VALUES ($1,$2,$3,$4)",
        [Buffer.alloc(32, index + 1), user.id, past, end]);
      await admin.query("INSERT INTO auth_rate_limits(bucket,key_hash,window_started_at,attempts) VALUES ('test',$1,$2,1)",
        [Buffer.alloc(32, index + 1), expired ? past : new Date()]);
      for (const [prefix, grid] of [["competition", dataset], ["cell", "fog-grid-v1"]]) {
        // The table names are fixed test constants, never request input.
        await admin.query(`INSERT INTO ${prefix}_runs(user_id,id,nonce,dataset,created_at,expires_at) VALUES ($1,$2,$3,$4,$5,$6)`,
          [user.id, randomUUID(), "N".repeat(43), grid, past, end]);
        await admin.query(`INSERT INTO ${prefix}_receipts(run_id,sequence,user_id,evidence_hash,response,created_at) VALUES ($1,0,$2,$3,'{}',$4)`,
          [randomUUID(), user.id, "H".repeat(43), expired ? past : new Date()]);
      }
    }
    deletion = await admin.connect();
    await deletion.query("BEGIN");
    await deletion.query("SET LOCAL lock_timeout='2s'");
    // Pause an account deletion after locking the user and its expired session,
    // before its cascade reaches live-run rows. No timing-dependent sleeps.
    await deletion.query("SELECT 1 FROM users WHERE id=$1 FOR UPDATE", [users[0].id]);
    await deletion.query("SELECT 1 FROM sessions WHERE user_id=$1 FOR UPDATE", [users[0].id]);
    cleaning = cleanupExpiredState(runtime).then(() => null, error => error);
    let waiting = false;
    for (let attempt = 0; attempt < 100 && !waiting; attempt++) {
      waiting = (await admin.query(
        "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE $1=ANY(pg_blocking_pids(pid))) AS waiting",
        [deletion.processID])).rows[0].waiting;
      if (!waiting) await new Promise(resolve => setTimeout(resolve, 10));
    }
    assert.equal(waiting, true, "Cleanup must reach the held session before deletion continues");
    await deletion.query("DELETE FROM users WHERE id=$1", [users[0].id]);
    await deletion.query("COMMIT");
    assert.equal(await cleaning, null);
    for (const table of ["sessions", "auth_rate_limits", "competition_receipts", "competition_runs", "cell_receipts", "cell_runs"]) {
      assert.equal((await admin.query(`SELECT COUNT(*) FROM ${table}`)).rows[0].count, "1", `${table} retains current state only`);
    }
    assert.deepEqual((await admin.query("SELECT username_key FROM users ORDER BY username_key")).rows.map(row => row.username_key), ["current", "retained"]);
  } finally {
    if (deletion) { await deletion.query("ROLLBACK"); deletion.release(); }
    if (cleaning) await cleaning;
    if (runtime) await runtime.end();
    await admin.end();
  }
});
