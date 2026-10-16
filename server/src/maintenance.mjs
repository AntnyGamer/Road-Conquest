/** Expire transient state without holding locks across unrelated tables. */
export async function cleanupExpiredState(pool) {
  // Each statement commits before the next table is touched. A single writable
  // CTE holds every table's row locks until the whole statement finishes, which
  // can deadlock with account deletion's foreign-key cascades in another order.
  // Keep the existing expiry thresholds and reuse the pool's idle connection.
  for (const sql of [
    "DELETE FROM notification_devices WHERE updated_at < CURRENT_TIMESTAMP - INTERVAL '30 days'",
    "DELETE FROM sessions WHERE expires_at <= CURRENT_TIMESTAMP",
    "DELETE FROM auth_rate_limits WHERE window_started_at < CURRENT_TIMESTAMP - INTERVAL '2 hours'",
    "DELETE FROM competition_receipts WHERE created_at < CURRENT_TIMESTAMP - INTERVAL '1 day'",
    "DELETE FROM competition_runs WHERE expires_at < CURRENT_TIMESTAMP - INTERVAL '1 day'",
    "DELETE FROM cell_receipts WHERE created_at < CURRENT_TIMESTAMP - INTERVAL '1 day'",
    "DELETE FROM cell_runs WHERE expires_at < CURRENT_TIMESTAMP - INTERVAL '1 day'"
  ]) await pool.query(sql);
}

/** Prepare database-backed requests without opening connections or timers while idle. */
export function createRequestMaintenance(pool, { initialize = async () => {}, now = () => performance.now() } = {}) {
  let initialized;
  let pending;
  let nextCleanup = -Infinity;
  return async function prepareDatabase() {
    if (!initialized) {
      initialized = Promise.resolve().then(initialize).catch(error => {
        initialized = undefined;
        throw error;
      });
    }
    await initialized;
    if (pending) return pending;
    if (now() < nextCleanup) return;
    pending = Promise.resolve().then(() => cleanupExpiredState(pool)).then(() => {
      nextCleanup = now() + 15 * 60 * 1000;
    }).finally(() => { pending = undefined; });
    return pending;
  };
}
