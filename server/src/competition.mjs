import { randomBytes, randomUUID } from "node:crypto";
import { createIntegrityVerifier } from "./integrity.mjs";
import { EvidenceError, evidenceHash, parseEvidence } from "./scoring.mjs";

import { GRID_ID, scoreCells } from "./cells.mjs";
import { sealCheckpoint, verifyContinuity } from "./checkpoints.mjs";

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
  async function eligible(db, user, lock = false) {
    const result = await db.query(`SELECT leaderboard_eligible FROM users WHERE id=$1${lock ? " FOR UPDATE" : ""}`, [user.id]);
    if (result.rows[0]?.leaderboard_eligible !== true) throw new EvidenceError("Account is not eligible for verified competition.", 403);
  }

  async function receipt(db, user, body, hash) {
    const result = await db.query(
      "SELECT evidence_hash, response FROM cell_receipts WHERE run_id = $1 AND sequence = $2 AND user_id = $3",
      [body.run, body.sequence, user.id]
    );
    if (!result.rowCount) return null;
    if (result.rows[0].evidence_hash !== hash) throw new EvidenceError("Batch sequence already used.", 409);
    if (result.rows[0].response?.scoring_policy !== 3 || result.rows[0].response?.verified !== true) {
      throw new EvidenceError("Batch belongs to an older scoring policy.", 409);
    }
    return result.rows[0].response;
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
      await eligible(pool, user);
      const id = randomUUID();
      const nonce = randomBytes(32).toString("base64url");
      await pool.query(
        `INSERT INTO cell_runs(user_id, id, nonce, dataset) VALUES ($1,$2,$3,$4)
         ON CONFLICT (user_id) DO UPDATE SET id = EXCLUDED.id, nonce = EXCLUDED.nonce, dataset = EXCLUDED.dataset,
           sequence = 0, created_at = CURRENT_TIMESTAMP, expires_at = CURRENT_TIMESTAMP + INTERVAL '3 minutes'`,
        [user.id, id, nonce, config.dataset]
      );
      return { run: id, nonce, sequence: 0 };
    },
    async submit(user, evidence, integrityToken) {
      await eligible(pool, user);
      // Validate shape/motion before looking up a receipt, but do not age out an
      // exact committed retry. Only new credit requires fresh GPS and attestation.
      const body = parseEvidence(evidence, Date.now(), false);
      const hash = evidenceHash(evidence);
      // A committed retry needs no second Google decode (standard tokens have replay protection).
      const previous = await receipt(pool, user, body, hash);
      if (previous) return previous;
      parseEvidence(evidence);
      const active = await pool.query(
        `SELECT 1 FROM cell_runs WHERE user_id = $1 AND id = $2 AND nonce = $3 AND sequence = $4
         AND dataset = $5 AND expires_at > CURRENT_TIMESTAMP
         AND created_at <= to_timestamp($6::double precision / 1000) + INTERVAL '10 seconds'`,
        [user.id, body.run, body.nonce, body.sequence, config.dataset, body.fixes[0].time]
      );
      if (!active.rowCount) {
        // Another copy may have committed between the receipt read and challenge check.
        const committed = await receipt(pool, user, body, hash);
        if (committed) return committed;
        throw new EvidenceError("Drive challenge expired or changed.", 409);
      }
      await verify(integrityToken, hash);
      const cells = scoreCells(body.fixes);
      const db = await pool.connect();
      try {
        await db.query("BEGIN");
        // Serializes all score changes for the account, even from different devices/sessions.
        await eligible(db, user, true);
        const concurrentReceipt = await receipt(db, user, body, hash);
        if (concurrentReceipt) { await db.query("COMMIT"); return concurrentReceipt; }
        const runs = await db.query("SELECT * FROM cell_runs WHERE user_id = $1 FOR UPDATE", [user.id]);
        const run = runs.rows[0];
        if (!run || run.id !== body.run || run.nonce !== body.nonce || run.sequence !== body.sequence ||
            run.dataset !== config.dataset || run.expires_at.getTime() <= Date.now() ||
            body.fixes[0].time < run.created_at.getTime() - 10000) throw new EvidenceError("Drive challenge expired or changed.", 409);
        await db.query("INSERT INTO cell_scores(user_id, dataset) VALUES ($1,$2) ON CONFLICT DO NOTHING", [user.id, config.dataset]);
        const old = await db.query("SELECT last_verified_fix FROM cell_scores WHERE user_id = $1 AND dataset = $2", [user.id, config.dataset]);
        const scope = `3:${user.id}:${config.dataset}`;
        verifyContinuity(old.rows[0].last_verified_fix, body.fixes[0], body.run, scope, config.checkpointKey);
        const newCells = await db.query(
          `INSERT INTO competition_cells(user_id,dataset,grid_row,grid_col)
           SELECT $1,$2,* FROM unnest($3::integer[],$4::integer[])
           ON CONFLICT DO NOTHING RETURNING grid_row`,
          [user.id, config.dataset, cells.map(c => c.row), cells.map(c => c.column)]
        );
        await db.query(
          "UPDATE cell_scores SET cells = cells + $1, last_verified_fix = $2 WHERE user_id = $3 AND dataset = $4",
          [newCells.rowCount, JSON.stringify(sealCheckpoint(body.fixes.at(-1), body.run, scope, config.checkpointKey)), user.id, config.dataset]
        );
        // Batches spanning Monday midnight belong to the week of their final fix.
        // The timestamp is already freshness-checked and bound by Play Integrity.
        const week = new Date(body.fixes.at(-1).time);
        week.setUTCHours(0, 0, 0, 0);
        week.setUTCDate(week.getUTCDate() - (week.getUTCDay() + 6) % 7);
        if (newCells.rowCount) await db.query(
          `INSERT INTO cell_weeks(user_id,dataset,week_start,new_cells) VALUES ($1,$2,$3,$4)
           ON CONFLICT (user_id,dataset,week_start) DO UPDATE
           SET new_cells = cell_weeks.new_cells + EXCLUDED.new_cells`,
          [user.id, config.dataset, week.toISOString().slice(0, 10), newCells.rowCount]
        );
        await db.query("UPDATE cell_runs SET sequence = sequence + 1, expires_at = CURRENT_TIMESTAMP + INTERVAL '3 minutes' WHERE user_id = $1", [user.id]);
        const result = { sequence: body.sequence + 1, credited_cells: newCells.rowCount, accepted_cells: cells, ...await totals(db, user.id) };
        await db.query("INSERT INTO cell_receipts(run_id,sequence,user_id,evidence_hash,response) VALUES ($1,$2,$3,$4,$5)",
          [body.run, body.sequence, user.id, hash, JSON.stringify(result)]);
        await db.query("COMMIT");
        return result;
      } catch (error) { await db.query("ROLLBACK"); throw error; }
      finally { db.release(); }
    },
    async friendWeek(user, metric = "cells") {
      if (metric !== "cells") throw new EvidenceError("Choose cells.", 400);
      const week = (await pool.query("SELECT to_char(date_trunc('week', CURRENT_TIMESTAMP AT TIME ZONE 'UTC'), 'YYYY-MM-DD') AS week")).rows[0].week;
      const result = await pool.query(
        `WITH scores AS (
          SELECT u.username_display AS username,u.username_key,COALESCE(w.new_cells,0) AS cells
          FROM users u LEFT JOIN cell_weeks w ON w.user_id=u.id AND w.dataset=$2 AND w.week_start=$3::date
          WHERE u.leaderboard_visible AND u.leaderboard_eligible AND (u.id=$1 OR EXISTS (
            SELECT 1 FROM friendships f WHERE f.accepted AND
            ((f.user_low=$1 AND f.user_high=u.id) OR (f.user_high=$1 AND f.user_low=u.id))))
         ) SELECT username,cells,RANK() OVER (ORDER BY cells DESC) AS rank
           FROM scores ORDER BY cells DESC,username_key ASC LIMIT 51`, [user.id, config.dataset, week]
      );
      return { available: true, verified: true, scoring_policy: 3, metric, dataset: config.dataset, week_start: week,
        entries: result.rows.map(r => ({ username: r.username, rank: Number(r.rank), cells: Number(r.cells) })) };
    },
    async leaderboard(metric = "cells") {
      if (metric !== "cells") throw new EvidenceError("Choose cells.", 400);
      const result = await pool.query(
        `SELECT u.username_display AS username,s.cells,RANK() OVER (ORDER BY s.cells DESC) AS rank
         FROM cell_scores s JOIN users u ON u.id=s.user_id
         WHERE s.dataset=$1 AND s.cells>0 AND u.leaderboard_visible AND u.leaderboard_eligible
         ORDER BY s.cells DESC,u.username_key ASC LIMIT 50`, [config.dataset]
      );
      return { verified: true, scoring_policy: 3, metric, dataset: config.dataset,
        entries: result.rows.map(r => ({ username: r.username, rank: Number(r.rank), cells: Number(r.cells) })) };
    }
  };
}
