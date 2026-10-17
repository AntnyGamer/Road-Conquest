// The single-budget reservation remains shared by ordinary routes and push
// delivery. Only trusted, static SQL aliases/parameter offsets are used here.
// Saturated windows stop at limit+1 without rewriting unchanged rows; expired
// windows reset exactly as before. No quota, bucket or keyed identity changes.
function reservation(offset = 0, previous = null) {
  const [bucket, key, window, limit] = [1, 2, 3, 4].map(n => `$${n + offset}`);
  return `INSERT INTO auth_rate_limits(bucket,key_hash,window_started_at,attempts)
    ${previous ? `SELECT ${bucket},${key},CURRENT_TIMESTAMP,1 FROM ${previous} WHERE allowed`
    : `VALUES (${bucket},${key},CURRENT_TIMESTAMP,1)`}
    ON CONFLICT (bucket,key_hash) DO UPDATE SET
      window_started_at=CASE WHEN auth_rate_limits.window_started_at<=CURRENT_TIMESTAMP-(${window}::bigint*INTERVAL '1 millisecond')
        THEN CURRENT_TIMESTAMP ELSE auth_rate_limits.window_started_at END,
      attempts=CASE WHEN auth_rate_limits.window_started_at<=CURRENT_TIMESTAMP-(${window}::bigint*INTERVAL '1 millisecond')
        THEN 1 ELSE LEAST(auth_rate_limits.attempts+1,${limit}+1) END
    WHERE auth_rate_limits.window_started_at<=CURRENT_TIMESTAMP-(${window}::bigint*INTERVAL '1 millisecond')
      OR auth_rate_limits.attempts<=${limit}
    RETURNING attempts<=${limit} AS allowed`;
}

export const RATE_LIMIT_SQL = reservation();
// An authenticated route can reserve its existing IP rate limit and check
// its current session in one PostgreSQL round trip. The permission CTE
// prevents an exhausted window from reading identity at all.
export const RATE_LIMIT_SESSION_SQL = `WITH permit AS (${RATE_LIMIT_SQL})
  SELECT COALESCE((SELECT allowed FROM permit), FALSE) AS allowed,
    (SELECT user_id FROM sessions WHERE token_hash=$5
      AND expires_at>CURRENT_TIMESTAMP
      AND EXISTS(SELECT 1 FROM permit WHERE allowed)) AS id`;
// A denied/saturated IP produces no username reservation, preserving short
// circuit accounting. The two distinct bucket rows retain the same lock order.
const loginBudgetCtes = `WITH ip_limit AS (${reservation()}),
  user_limit AS (${reservation(4, 'ip_limit')})`;
export const LOGIN_LIMIT_SQL = `${loginBudgetCtes}
  SELECT COALESCE((SELECT allowed FROM user_limit),FALSE) AS allowed`;
// The normal sign-in path can retrieve its account data in the SAME round trip
// as both budgets. A denied budget yields only {allowed:false}, never a
// password hash; unknown usernames still take the existing dummy scrypt path.
export const LOGIN_WITH_USER_SQL = `${loginBudgetCtes},
  permission AS (SELECT COALESCE((SELECT allowed FROM user_limit),FALSE) AS allowed)
  SELECT p.allowed,u.id,u.username_display,u.password_salt,u.password_hash,u.leaderboard_visible
  FROM permission p LEFT JOIN users u ON p.allowed AND u.username_key=$9`;
