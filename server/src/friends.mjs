import { normalizeUsername } from "./security.mjs";
import { EvidenceError } from "./scoring.mjs";

export function createFriends(pool) {
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
          const existing = await db.query("SELECT 1 FROM friendships WHERE user_low = $1 AND user_high = $2", pair);
          if (!existing.rowCount) {
            const counts = await db.query(
              "SELECT id, (SELECT COUNT(*) FROM friendships f WHERE f.user_low = u.id OR f.user_high = u.id) AS count FROM users u WHERE id = ANY($1::bigint[])", [pair]
            );
            if (counts.rows.some(r => Number(r.count) >= 50)) throw new EvidenceError("Friend/request limit reached.", 409);
            await db.query("INSERT INTO friendships(user_low,user_high,requested_by) VALUES ($1,$2,$3)", [...pair, user.id]);
          }
        } else if (action === "accept") {
          const updated = await db.query(
            "UPDATE friendships SET accepted = TRUE WHERE user_low = $1 AND user_high = $2 AND requested_by <> $3 RETURNING user_low", [...pair, user.id]
          );
          if (!updated.rowCount) throw new EvidenceError("No incoming request to accept.", 409);
        } else {
          await db.query("DELETE FROM friendships WHERE user_low = $1 AND user_high = $2", pair);
        }
        await db.query("COMMIT");
        return { ok: true };
      } catch (error) { await db.query("ROLLBACK"); throw error; }
      finally { db.release(); }
    }
  };
}
