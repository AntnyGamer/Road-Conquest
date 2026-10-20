import { randomBytes, randomUUID } from "node:crypto";
import { createIntegrityVerifier } from "./integrity.mjs";
import { EvidenceError, evidenceHash, parseEvidence, requireFreshFixes } from "./scoring.mjs";

import { GRID_ID, scoreCells } from "./cells.mjs";
import { createCheckpointCodec } from "./checkpoints.mjs";

export function competitionConfig(env) {
  if (env.LEADERBOARDS_ENABLED !== "1") return null;
  const config = {
    dataset: GRID_ID, credentialsFile: env.PLAY_SERVICE_ACCOUNT_FILE, credentialsJson: env.PLAY_SERVICE_ACCOUNT_JSON,
    packageName: "com.roadconquest.app", certificates: (env.PLAY_CERTIFICATES || "").split(",").filter(Boolean),
    versions: (env.PLAY_VERSION_CODES || "").split(",").filter(Boolean),
    cloudProject: env.PLAY_CLOUD_PROJECT_NUMBER,
    checkpointKey: env.VERIFIED_EVIDENCE_KEY
  };
  if ((!config.credentialsFile && !config.credentialsJson) ||
      !config.certificates.length || config.certificates.some(c => !/^[A-Za-z0-9_-]{43}$/u.test(c)) ||
      !config.versions.length || config.versions.some(v => !/^[1-9][0-9]*$/u.test(v) || !Number.isSafeInteger(Number(v)) || Number(v) < 71) ||
      !/^[1-9][0-9]{5,14}$/u.test(config.cloudProject || "") ||
      typeof config.checkpointKey !== "string" || !/^[A-Za-z0-9_-]{43}$/u.test(config.checkpointKey) ||
      Buffer.from(config.checkpointKey, "base64url").length !== 32) {
    throw new Error("Leaderboards require checkpoint encryption and production Play Integrity configuration");
  }
  return config;
}

export async function createCompetition(pool, config, dependencies = {}) {
  if (!config) return null;
  if (config.dataset !== GRID_ID) throw new Error("Unsupported exploration grid");
  const verify = dependencies.verify || await createIntegrityVerifier(config);
  const pendingVerifications = new Map();
  async function verifyBatch(user, token, hash) {
    // Share only an identical authenticated operation that is ALREADY in flight.
    // Never retain a completed verdict or share it across users, evidence or tokens.
    const key = evidenceHash(JSON.stringify([user.id, hash, token]));
    const existing = pendingVerifications.get(key);
    if (existing) return existing;
    const pending = verify(token, hash);
    // At capacity, keep the ordinary verification path; do not evict active work.
    if (pendingVerifications.size >= 64) return pending;
    pendingVerifications.set(key, pending);
    try { return await pending; }
    finally { pendingVerifications.delete(key); }
  }
  async function eligible(db, user, lock = false) {
    const result = await db.query(`SELECT leaderboard_eligible FROM users WHERE id=$1${lock ? " FOR UPDATE" : ""}`, [user.id]);
    if (result.rows[0]?.leaderboard_eligible !== true) throw new EvidenceError("Account is not eligible for verified competition.", 403);
  }

  function receiptResponse(row, hash) {
    if (row?.evidence_hash == null) return null;
    if (row.evidence_hash !== hash) throw new EvidenceError("Batch sequence already used.", 409);
    if (row.response?.scoring_policy !== 3 || row.response?.verified !== true) {
      throw new EvidenceError("Batch belongs to an older scoring policy.", 409);
    }
    return row.response;
  }

  async function receipt(db, user, body, hash) {
    const result = await db.query(
      "SELECT evidence_hash, response FROM cell_receipts WHERE run_id = $1 AND sequence = $2 AND user_id = $3",
      [body.run, body.sequence, user.id]
    );
    return receiptResponse(result.rows[0], hash);
  }

  async function totals(db, userId) {
    const result = await db.query(
      "SELECT cells FROM cell_scores WHERE user_id = $1 AND dataset = $2", [userId, config.dataset]
    );
    return { verified: true, scoring_policy: 3, dataset: config.dataset, cells: Number(result.rows[0]?.cells || 0) };
  }

  return {
    status: () => ({ available: true, verified: true, scoring_policy: 3, ranking_policy: "verified-only", dataset: config.dataset, cloud_project: config.cloudProject,
      eligibility: "Google Play installed, licensed app on a device meeting strong integrity. Live GPS evidence is required.",
      cells_definition: "Unique squares from the app's exploration grid, confirmed by two accurate moving GPS fixes inside the same square. Repeat visits add no credit." }),
    totals: user => totals(pool, user.id),
    async begin(user) {
      const id = randomUUID();
      const nonce = randomBytes(32).toString("base64url");
      // Authenticate first at the HTTP boundary, then check competition eligibility
      // directly in the write: no separate preflight SELECT / database round trip.
      // An ineligible (or deleted) account cannot create or renew a challenge.
      const started = await pool.query(
        `INSERT INTO cell_runs(user_id, id, nonce, dataset)
         SELECT u.id,$2,$3,$4 FROM users u
         WHERE u.id=$1 AND u.leaderboard_eligible = TRUE
         ON CONFLICT (user_id) DO UPDATE SET id = EXCLUDED.id, nonce = EXCLUDED.nonce, dataset = EXCLUDED.dataset,
           sequence = 0, created_at = CURRENT_TIMESTAMP, expires_at = CURRENT_TIMESTAMP + INTERVAL '3 minutes'`,
        [user.id, id, nonce, config.dataset]
      );
      if (!started.rowCount) throw new EvidenceError("Account is not eligible for verified competition.", 403);
      return { run: id, nonce, sequence: 0 };
    },
    async submit(user, evidence, integrityToken) {
      // Validate shape/motion before looking up a receipt, but do not age out an
      // exact committed retry. Only new credit requires fresh GPS and attestation.
      let body;
      try { body = parseEvidence(evidence, Date.now(), false); }
      catch (error) {
        // Preserve the eligibility-first error even for malformed evidence.
        await eligible(pool, user);
        throw error;
      }
      // The three read-only preflight checks share one indexed snapshot/round
      // trip. A receipt hit skips the challenge lookup altogether. The separate
      // locked checks below still guard changes while attestation is in flight.
      const checked = await pool.query(
        `SELECT u.leaderboard_eligible,r.evidence_hash,r.response,
           CASE WHEN u.leaderboard_eligible AND r.run_id IS NULL THEN EXISTS (
             SELECT 1 FROM cell_runs WHERE user_id=$1 AND id=$2 AND nonce=$4 AND sequence=$3::bigint
               AND dataset=$5 AND expires_at>CURRENT_TIMESTAMP
               AND created_at<=to_timestamp($6::double precision / 1000)+INTERVAL '10 seconds'
           ) ELSE FALSE END AS active
         FROM users u LEFT JOIN cell_receipts r
           ON u.leaderboard_eligible AND r.user_id=u.id AND r.run_id=$2 AND r.sequence=$3::bigint
         WHERE u.id=$1`,
        [user.id, body.run, body.sequence, body.nonce, config.dataset, body.fixes[0].time]
      );
      const preflight = checked.rows[0];
      if (preflight?.leaderboard_eligible !== true)
        throw new EvidenceError("Account is not eligible for verified competition.", 403);
      const hash = evidenceHash(evidence);
      // A committed retry needs no second Google decode (standard tokens have replay protection).
      const previous = receiptResponse(preflight, hash);
      if (previous) return previous;
      // No need to repeat JSON parsing, geometry validation and continuity
      // checks: the first parse already validated them. New batches must still
      // pass the exact same per-fix freshness limits as before.
      requireFreshFixes(body);
      if (!preflight.active) {
        // An already in-flight copy may have committed after the preflight snapshot.
        const committed = await receipt(pool, user, body, hash);
        if (committed) return committed;
        throw new EvidenceError("Drive challenge expired or changed.", 409);
      }
      await verifyBatch(user, integrityToken, hash);
      const cells = scoreCells(body.fixes);
      const db = await pool.connect();
      try {
        await db.query("BEGIN");
        // Serializes all score changes for the account, even from different devices/sessions.
        await eligible(db, user, true);
        const concurrentReceipt = await receipt(db, user, body, hash);
        if (concurrentReceipt) { await db.query("COMMIT"); return concurrentReceipt; }
        const runs = await db.query("SELECT id,nonce,sequence,dataset,expires_at,created_at FROM cell_runs WHERE user_id = $1 FOR UPDATE", [user.id]);
        const run = runs.rows[0];
        if (!run || run.id !== body.run || run.nonce !== body.nonce || run.sequence !== body.sequence ||
            run.dataset !== config.dataset || run.expires_at.getTime() <= Date.now() ||
            body.fixes[0].time < run.created_at.getTime() - 10000) throw new EvidenceError("Drive challenge expired or changed.", 409);
        // Ensure the score row exists and read the previous sealed checkpoint
        // in one round trip. Our user-row lock serializes updates for this account.
        // The inserted CTE returns new rows; the fallback SELECT returns existing
        // rows without writing unchanged score data.
        const old = await db.query(
          `WITH inserted AS (
            INSERT INTO cell_scores(user_id, dataset) VALUES ($1,$2)
            ON CONFLICT DO NOTHING RETURNING last_verified_fix
          )
          SELECT last_verified_fix FROM inserted
          UNION ALL
          SELECT last_verified_fix FROM cell_scores
          WHERE user_id = $1 AND dataset = $2 AND NOT EXISTS (SELECT 1 FROM inserted)`,
          [user.id, config.dataset]
        );
        const scope = `3:${user.id}:${config.dataset}`;
        const checkpoint = createCheckpointCodec(scope, config.checkpointKey);
        checkpoint.verify(old.rows[0].last_verified_fix, body.fixes[0], body.run);
        let newCells = 0;
        // An empty server-derived set cannot insert or conflict with any cell.
        // Still verify/store continuity and advance the receipt transaction below.
        if (cells.length) {
          const inserted = await db.query(
            `WITH added AS (INSERT INTO competition_cells(user_id,dataset,grid_row,grid_col)
             SELECT $1,$2,* FROM unnest($3::integer[],$4::integer[])
             ON CONFLICT DO NOTHING RETURNING 1)
             SELECT COUNT(*)::integer AS count FROM added HAVING COUNT(*)>0`,
            [user.id, config.dataset, cells.map(c => c.row), cells.map(c => c.column)]
          );
          newCells = inserted.rows[0]?.count || 0;
        }
        // Batches spanning Monday midnight belong to the week of their final fix.
        // The timestamp is already freshness-checked and bound by Play Integrity.
        let weekStart;
        if (newCells) {
          const week = new Date(body.fixes.at(-1).time);
          week.setUTCHours(0, 0, 0, 0);
          week.setUTCDate(week.getUTCDate() - (week.getUTCDay() + 6) % 7);
          weekStart = week.toISOString().slice(0, 10);
        }
        // Persist the checkpoint/total and this week's nonzero increment in one
        // statement. Zero-credit batches still advance continuity without a
        // weekly write. Both writes remain inside the same user-locked transaction.
        const updateTotal = `UPDATE cell_scores SET cells=cells+$1,last_verified_fix=$2
          WHERE user_id=$3 AND dataset=$4 RETURNING cells`;
        const scoreArgs = [newCells, JSON.stringify(checkpoint.seal(body.fixes.at(-1), body.run)),
          user.id, config.dataset];
        const updatedScore = await db.query(newCells ?
          `WITH updated AS (${updateTotal}), recorded_week AS (
            INSERT INTO cell_weeks(user_id,dataset,week_start,new_cells)
            SELECT $3,$4,$5,$1 FROM updated
            ON CONFLICT (user_id,dataset,week_start) DO UPDATE
            SET new_cells=cell_weeks.new_cells+EXCLUDED.new_cells
          ) SELECT cells FROM updated` : updateTotal,
          newCells ? [...scoreArgs, weekStart] : scoreArgs
        );
        const result = { sequence: body.sequence + 1, credited_cells: newCells, accepted_cells: cells,
          verified: true, scoring_policy: 3, dataset: config.dataset, cells: Number(updatedScore.rows[0].cells) };
        // The retry receipt is stored only when the locked drive sequence advances.
        await db.query(
          `WITH advanced AS (
            UPDATE cell_runs SET sequence = sequence + 1,
              expires_at = CURRENT_TIMESTAMP + INTERVAL '3 minutes'
            WHERE user_id = $1 RETURNING user_id
          )
          INSERT INTO cell_receipts(run_id,sequence,user_id,evidence_hash,response)
          SELECT $2,$3,advanced.user_id,$4,$5 FROM advanced`,
          [user.id, body.run, body.sequence, hash, JSON.stringify(result)]
        );
        await db.query("COMMIT");
        return result;
      } catch (error) { await db.query("ROLLBACK"); throw error; }
      finally { db.release(); }
    },
    async friendWeek(user, metric = "cells") {
      if (metric !== "cells") throw new EvidenceError("Choose cells.", 400);
      // Determine the UTC week and rank friends in the same query. LEFT JOIN
      // retains the week even when the account has no visible standings.
      const result = await pool.query(
        `WITH week AS (
          SELECT date_trunc('week', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date AS start_date
        ), participants AS (
          SELECT $1::bigint AS id
          UNION ALL
          -- The CHECK(user_low < user_high) and PK(user_low,user_high)
          -- guarantee exactly one distinct counterpart for each friendship.
          -- Neither a self-edge nor a duplicate friend can enter this result.
          SELECT CASE WHEN f.user_low=$1 THEN f.user_high ELSE f.user_low END
          FROM friendships f WHERE f.accepted AND (f.user_low=$1 OR f.user_high=$1)
        ), scores AS (
          SELECT u.username_display AS username,u.username_key,COALESCE(w.new_cells,0) AS cells
          FROM participants p JOIN users u ON u.id=p.id
          LEFT JOIN cell_weeks w ON w.user_id=u.id AND w.dataset=$2
            AND w.week_start=(SELECT start_date FROM week)
          WHERE u.leaderboard_visible AND u.leaderboard_eligible
        ), ranked AS (
          SELECT username,username_key,cells,RANK() OVER (ORDER BY cells DESC) AS rank
          FROM scores
        )
        SELECT to_char(week.start_date, 'YYYY-MM-DD') AS week_start,
          ranked.username,ranked.cells,ranked.rank
        FROM week LEFT JOIN ranked ON TRUE
        ORDER BY ranked.cells DESC NULLS LAST,ranked.username_key ASC NULLS LAST
        LIMIT 51`, [user.id, config.dataset]
      );
      return { available: true, verified: true, scoring_policy: 3, metric, dataset: config.dataset,
        week_start: result.rows[0].week_start,
        entries: result.rows.filter(r => r.username !== null)
          .map(r => ({ username: r.username, rank: Number(r.rank), cells: Number(r.cells) })) };
    },
    async leaderboard(metric = "cells", authenticatedUserId = null) {
      if (metric !== "cells") throw new EvidenceError("Choose cells.", 400);
      // Public callers keep exactly the existing query and response. A verified
      // authenticated caller gets its total as one additional scalar projection
      // in the same indexed rankings query, without a second PostgreSQL round trip.
      // One ranking definition for both public and signed-in responses.
      const leadersCte = `WITH leaders AS (
           SELECT u.username_display AS username,u.username_key,s.cells
           FROM cell_scores s JOIN users u ON u.id=s.user_id
           WHERE s.dataset=$1 AND s.cells>0 AND u.leaderboard_visible AND u.leaderboard_eligible
           ORDER BY s.cells DESC,u.username_key ASC LIMIT 50
         )`;
      const publicSql = `${leadersCte}
         SELECT username,cells,RANK() OVER (ORDER BY cells DESC) AS rank
         FROM leaders ORDER BY cells DESC,username_key ASC`;
      const withMine = authenticatedUserId != null;
      const sql = withMine ? `${leadersCte}, ranked AS (
           SELECT username,username_key,cells,RANK() OVER (ORDER BY cells DESC) AS rank
           FROM leaders
         )
         SELECT ranked.username,ranked.cells,ranked.rank,mine.cells AS mine_cells
         FROM (SELECT COALESCE((
           SELECT cells FROM cell_scores WHERE user_id=$2 AND dataset=$1
         ),0) AS cells) mine LEFT JOIN ranked ON TRUE
         ORDER BY ranked.cells DESC NULLS LAST,ranked.username_key ASC NULLS LAST`
        : publicSql;
      const result = await pool.query(sql, withMine ? [config.dataset, authenticatedUserId] : [config.dataset]);
      const ranking = { verified: true, scoring_policy: 3, metric, dataset: config.dataset,
        entries: result.rows.filter(r => r.username !== null)
          .map(r => ({ username: r.username, rank: Number(r.rank), cells: Number(r.cells) })) };
      if (withMine) ranking.mine = { verified: true, scoring_policy: 3, dataset: config.dataset,
        cells: Number(result.rows[0].mine_cells) };
      return ranking;
    }
  };
}
