import { createHmac, randomBytes, randomUUID } from "node:crypto";
import { createIntegrityVerifier } from "./integrity.mjs";
import { edgeKey, EvidenceError, evidenceHash, matchEvidence, parseEvidence, scoreMatch } from "./scoring.mjs";

function fixFingerprint(fix, key) {
  return createHmac("sha256", key)
    .update("roadconquest-boundary-fix\0", "utf8")
    .update(JSON.stringify([
      fix.lat, fix.lon, fix.time, fix.elapsed, fix.accuracy, fix.speed, fix.mock
    ]), "utf8")
    .digest("base64url");
}

function fixWatermark(fix, key) {
  return { time: fix.time, fingerprint: fixFingerprint(fix, key) };
}

export function competitionConfig(env) {
  if (env.LEADERBOARDS_ENABLED !== "1") return null;
  const config = {
    dataset: env.ROAD_DATASET_SHA256, dataVersion: env.OSRM_DATA_VERSION,
    osrmUrl: env.VERIFIED_OSRM_URL, credentialsFile: env.PLAY_SERVICE_ACCOUNT_FILE, credentialsJson: env.PLAY_SERVICE_ACCOUNT_JSON,
    packageName: "com.roadconquest.app", certificates: (env.PLAY_CERTIFICATES || "").split(",").filter(Boolean),
    versions: (env.PLAY_VERSION_CODES || "").split(",").filter(Boolean),
    cloudProject: env.PLAY_CLOUD_PROJECT_NUMBER,
    watermarkKey: env.PASSWORD_PEPPER
  };
  const url = config.osrmUrl ? new URL(config.osrmUrl) : null;
  if (!/^[a-f0-9]{64}$/u.test(config.dataset || "") || !config.dataVersion ||
      !url || url.protocol !== "https:" || url.username || url.password || url.search || url.hash ||
      url.pathname !== "/" || url.hostname === "router.project-osrm.org" ||
      (!config.credentialsFile && !config.credentialsJson) ||
      !config.certificates.length || config.certificates.some(c => !/^[A-Za-z0-9_-]{43}$/u.test(c)) ||
      !config.versions.length || config.versions.some(v => !/^[1-9][0-9]*$/u.test(v)) ||
      !/^[1-9][0-9]{5,14}$/u.test(config.cloudProject || "") ||
      typeof config.watermarkKey !== "string" || config.watermarkKey.length < 32) {
    throw new Error("Leaderboards require pinned roads, private HTTPS OSRM, and production Play Integrity configuration");
  }
  return config;
}

export async function createCompetition(pool, config, dependencies = {}) {
  if (!config) return null;
  const catalog = await pool.query("SELECT data_version FROM road_catalogs WHERE dataset = $1", [config.dataset]);
  const edges = await pool.query("SELECT 1 FROM road_edges WHERE dataset = $1 LIMIT 1", [config.dataset]);
  if (catalog.rows[0]?.data_version !== config.dataVersion || !edges.rowCount) throw new Error("Pinned catalog missing or mismatched");
  const verify = dependencies.verify || await createIntegrityVerifier(config);
  const match = dependencies.match || (fixes => matchEvidence(fixes, config));

  async function receipt(db, user, body, hash) {
    const result = await db.query(
      "SELECT evidence_hash, response FROM competition_receipts WHERE run_id = $1 AND sequence = $2 AND user_id = $3",
      [body.run, body.sequence, user.id]
    );
    if (!result.rowCount) return null;
    if (result.rows[0].evidence_hash !== hash) throw new EvidenceError("Batch sequence already used.", 409);
    return result.rows[0].response;
  }

  async function totals(db, userId) {
    const result = await db.query(
      `SELECT s.distance_mm, (SELECT COUNT(*) FROM competition_roads r WHERE r.user_id = s.user_id AND r.dataset = s.dataset) AS roads
       FROM competition_scores s WHERE s.user_id = $1 AND s.dataset = $2`, [userId, config.dataset]
    );
    const row = result.rows[0];
    return { miles: Number(row?.distance_mm || 0) / 1609344, roads: Number(row?.roads || 0) };
  }

  return {
    status: () => ({ available: true, dataset: config.dataset, cloud_project: config.cloudProject,
      miles_definition: "Server-matched driving distance estimate; repeat trips count.",
      roads_definition: "Unique OSM way sections with at least 20 matched meters in an accepted batch." }),
    totals: user => totals(pool, user.id),
    async begin(user) {
      const id = randomUUID();
      const nonce = randomBytes(32).toString("base64url");
      await pool.query(
        `INSERT INTO competition_runs(user_id, id, nonce, dataset) VALUES ($1,$2,$3,$4)
         ON CONFLICT (user_id) DO UPDATE SET id = EXCLUDED.id, nonce = EXCLUDED.nonce, dataset = EXCLUDED.dataset,
           sequence = 0, created_at = CURRENT_TIMESTAMP, expires_at = CURRENT_TIMESTAMP + INTERVAL '3 minutes'`,
        [user.id, id, nonce, config.dataset]
      );
      return { run: id, nonce, sequence: 0 };
    },
    async submit(user, evidence, integrityToken) {
      const body = parseEvidence(evidence);
      const hash = evidenceHash(evidence);
      // A committed retry needs no second Google decode (standard tokens have replay protection).
      const previous = await receipt(pool, user, body, hash);
      if (previous) return previous;
      const active = await pool.query(
        `SELECT 1 FROM competition_runs WHERE user_id = $1 AND id = $2 AND nonce = $3 AND sequence = $4
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
      const root = await match(body.fixes);
      const pairs = [...new Map((root?.matchings || []).flatMap(m => (m.legs || []).flatMap(l => {
        const nodes = l.annotation?.nodes || [];
        return nodes.slice(1).map((b, i) => [edgeKey(nodes[i], b), [Math.min(nodes[i], b), Math.max(nodes[i], b)]]);
      }))).values()];
      if (pairs.length > 4096 || pairs.some(([a, b]) => !Number.isSafeInteger(a) || !Number.isSafeInteger(b) || a <= 0 || b <= a)) {
        throw new EvidenceError("Invalid road catalog lookup.");
      }
      const found = await pool.query(
        `SELECT node_low, node_high, way_id FROM road_edges WHERE dataset = $1
         AND (node_low, node_high) IN (SELECT * FROM unnest($2::bigint[], $3::bigint[]))`,
        [config.dataset, pairs.map(p => p[0]), pairs.map(p => p[1])]
      );
      const roads = new Map(found.rows.filter(r => r.way_id).map(r => [edgeKey(Number(r.node_low), Number(r.node_high)), r.way_id]));
      const score = scoreMatch(root, body.fixes, roads, config.dataVersion);
      const db = await pool.connect();
      try {
        await db.query("BEGIN");
        // Serializes all score changes for the account, even from different devices/sessions.
        await db.query("SELECT id FROM users WHERE id = $1 FOR UPDATE", [user.id]);
        const concurrentReceipt = await receipt(db, user, body, hash);
        if (concurrentReceipt) { await db.query("COMMIT"); return concurrentReceipt; }
        const runs = await db.query("SELECT * FROM competition_runs WHERE user_id = $1 FOR UPDATE", [user.id]);
        const run = runs.rows[0];
        if (!run || run.id !== body.run || run.nonce !== body.nonce || run.sequence !== body.sequence ||
            run.dataset !== config.dataset || run.expires_at.getTime() <= Date.now() ||
            body.fixes[0].time < run.created_at.getTime() - 10000) throw new EvidenceError("Drive challenge expired or changed.", 409);
        await db.query("INSERT INTO competition_scores(user_id, dataset) VALUES ($1,$2) ON CONFLICT DO NOTHING", [user.id, config.dataset]);
        const old = await db.query("SELECT last_fix FROM competition_scores WHERE user_id = $1 AND dataset = $2", [user.id, config.dataset]);
        const last = old.rows[0].last_fix;
        const first = body.fixes[0];
        const lastTime = Number(last?.time);
        if (last && Number.isSafeInteger(lastTime)) {
          if (first.time < lastTime) throw new EvidenceError("Overlapping or altered driving evidence.", 409);
          if (first.time === lastTime &&
              last.fingerprint !== fixFingerprint(first, config.watermarkKey)) {
            throw new EvidenceError("Overlapping or altered driving evidence.", 409);
          }
        }
        await db.query(
          "UPDATE competition_scores SET distance_mm = distance_mm + $1, last_fix = $2 WHERE user_id = $3 AND dataset = $4",
          [score.millimeters, JSON.stringify(fixWatermark(body.fixes.at(-1), config.watermarkKey)), user.id, config.dataset]
        );
        const newRoads = await db.query(
          "INSERT INTO competition_roads(user_id,dataset,way_id) SELECT $1,$2,unnest($3::bigint[]) ON CONFLICT DO NOTHING RETURNING way_id",
          [user.id, config.dataset, score.roads]
        );
        // Batches spanning Monday midnight belong to the week of their final fix.
        // The timestamp is already freshness-checked and bound by Play Integrity.
        const week = new Date(body.fixes.at(-1).time);
        week.setUTCHours(0, 0, 0, 0);
        week.setUTCDate(week.getUTCDate() - (week.getUTCDay() + 6) % 7);
        await db.query(
          `INSERT INTO competition_weeks(user_id,dataset,week_start,distance_mm,new_roads) VALUES ($1,$2,$3,$4,$5)
           ON CONFLICT (user_id,dataset,week_start) DO UPDATE
           SET distance_mm = competition_weeks.distance_mm + EXCLUDED.distance_mm,
               new_roads = competition_weeks.new_roads + EXCLUDED.new_roads`,
          [user.id, config.dataset, week.toISOString().slice(0, 10), score.millimeters, newRoads.rowCount]
        );
        await db.query("UPDATE competition_runs SET sequence = sequence + 1, expires_at = CURRENT_TIMESTAMP + INTERVAL '3 minutes' WHERE user_id = $1", [user.id]);
        const result = { sequence: body.sequence + 1, credited_miles: score.millimeters / 1609344, ...await totals(db, user.id) };
        await db.query("INSERT INTO competition_receipts(run_id,sequence,user_id,evidence_hash,response) VALUES ($1,$2,$3,$4,$5)",
          [body.run, body.sequence, user.id, hash, JSON.stringify(result)]);
        await db.query("COMMIT");
        return result;
      } catch (error) { await db.query("ROLLBACK"); throw error; }
      finally { db.release(); }
    },
    async friendWeek(user, metric) {
      if (metric !== "miles" && metric !== "roads") throw new EvidenceError("Choose miles or roads.", 400);
      const column = metric === "miles" ? "distance_mm" : "new_roads";
      const week = (await pool.query("SELECT to_char(date_trunc('week', CURRENT_TIMESTAMP AT TIME ZONE 'UTC'), 'YYYY-MM-DD') AS week")).rows[0].week;
      const result = await pool.query(
        `WITH scores AS (
          SELECT u.username_display AS username, u.username_key,
            COALESCE(w.distance_mm,0) AS distance_mm, COALESCE(w.new_roads,0) AS new_roads
          FROM users u LEFT JOIN competition_weeks w ON w.user_id = u.id AND w.dataset = $2 AND w.week_start = $3
          WHERE u.leaderboard_visible AND u.leaderboard_eligible AND (u.id = $1 OR EXISTS (
            SELECT 1 FROM friendships f WHERE f.accepted AND
              ((f.user_low = $1 AND f.user_high = u.id) OR (f.user_high = $1 AND f.user_low = u.id))
          ))
        ) SELECT username,distance_mm,new_roads,RANK() OVER (ORDER BY ${column} DESC) AS rank
          FROM scores ORDER BY ${column} DESC, username_key LIMIT 51`, [user.id, config.dataset, week]
      );
      return { available: true, week_start: week, dataset: config.dataset, metric,
        entries: result.rows.map(r => ({ username: r.username, rank: Number(r.rank),
          miles: Number(r.distance_mm) / 1609344, roads: Number(r.new_roads) })) };
    },
    async leaderboard(metric) {
      if (metric !== "miles" && metric !== "roads") throw new EvidenceError("Choose miles or roads.", 400);
      const column = metric === "miles" ? "distance_mm" : "roads";
      const result = await pool.query(
        `WITH scores AS (
           SELECT u.username_display AS username, u.username_key, s.distance_mm,
             (SELECT COUNT(*) FROM competition_roads r WHERE r.user_id = u.id AND r.dataset = s.dataset) AS roads
           FROM competition_scores s JOIN users u ON u.id = s.user_id
           WHERE s.dataset = $1 AND u.leaderboard_visible AND u.leaderboard_eligible AND s.distance_mm > 0
         ) SELECT username, distance_mm, roads, RANK() OVER (ORDER BY ${column} DESC) AS rank
           FROM scores ORDER BY ${column} DESC, username_key ASC LIMIT 50`, [config.dataset]
      );
      return { metric, dataset: config.dataset, entries: result.rows.map(r => ({
        username: r.username, rank: Number(r.rank), miles: Number(r.distance_mm) / 1609344, roads: Number(r.roads)
      })) };
    }
  };
}
