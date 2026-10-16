import { normalizeUsername } from "./security.mjs";
import { EvidenceError } from "./scoring.mjs";

export function createFriends(pool, notifyRequest = async () => {}) {
  return {
    async list(user) {
      const result = await pool.query(
        `SELECT u.username_display AS username, f.accepted, f.requested_by = $1 AS outgoing
         FROM friendships f JOIN users u ON u.id = CASE WHEN f.user_low = $1 THEN f.user_high ELSE f.user_low END
         WHERE f.user_low = $1 OR f.user_high = $1
         ORDER BY f.accepted DESC, u.username_key`, [user.id]
      );
      return { friends: result.rows.map(r => ({ username: r.username,
        status: r.accepted ? "accepted" : r.outgoing ? "outgoing" : "incoming" })) };
    },
    async change(user, username, action) {
      const normalized = normalizeUsername(username);
      if (!normalized || !["request", "accept", "remove"].includes(action)) throw new EvidenceError("Invalid friend action.", 400);
      const db = await pool.connect();
      let notificationTarget;
      try {
        await db.query("BEGIN");
        // Resolve the target and lock both participants in one database round
        // trip. ORDER BY still acquires locks in stable ID order, preventing
        // reciprocal requests from deadlocking or bypassing the 50-friend cap.
        const locked = await db.query(
          "SELECT id, username_key FROM users WHERE id = $1 OR username_key = $2 ORDER BY id FOR UPDATE",
          [user.id, normalized.key]
        );
        const target = locked.rows.find(row => row.username_key === normalized.key);
        if (!target || String(target.id) === String(user.id)) {
          throw new EvidenceError("Choose another existing username.", 400);
        }
        if (locked.rowCount !== 2) throw new EvidenceError("Account unavailable.", 409);
        const pair = locked.rows.map(row => row.id);
        if (action === "request") {
          // Both user locks remain held. Existing requests are idempotent even
          // at the cap, and only a new relationship checks each participant's
          // full accepted + pending count. A single snapshot/check/insert saves
          // two round trips without relaxing the cap or reciprocal consent.
          const decision = await db.query(`WITH existing AS (
            SELECT 1 FROM friendships WHERE user_low=$1 AND user_high=$2
          ), inserted AS (
            INSERT INTO friendships(user_low,user_high,requested_by)
            SELECT $1,$2,$3 WHERE NOT EXISTS (SELECT 1 FROM existing)
              AND (SELECT COUNT(*) FROM friendships WHERE user_low=$1 OR user_high=$1) < 50
              AND (SELECT COUNT(*) FROM friendships WHERE user_low=$2 OR user_high=$2) < 50
            RETURNING 1
          ) SELECT EXISTS(SELECT 1 FROM existing) AS existed,
              EXISTS(SELECT 1 FROM inserted) AS inserted`, [...pair, user.id]);
          const { existed, inserted } = decision.rows[0];
          if (!existed && !inserted) throw new EvidenceError("Friend/request limit reached.", 409);
          if (inserted) notificationTarget = target.id;
        } else if (action === "accept") {
          const updated = await db.query(
            "UPDATE friendships SET accepted = TRUE WHERE user_low = $1 AND user_high = $2 AND requested_by <> $3 RETURNING user_low", [...pair, user.id]
          );
          if (!updated.rowCount) throw new EvidenceError("No incoming request to accept.", 409);
        } else {
          await db.query("DELETE FROM friendships WHERE user_low = $1 AND user_high = $2", pair);
        }
        await db.query("COMMIT");
      } catch (error) { await db.query("ROLLBACK"); throw error; }
      finally { db.release(); }
      // Best effort, after commit and connection release. Push failures must not undo consent.
      if (notificationTarget) await notifyRequest(notificationTarget).catch(() => {});
      return { ok: true };
    }
  };
}
