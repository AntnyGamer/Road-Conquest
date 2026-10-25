import { createCipheriv, createDecipheriv, createHash, createHmac, randomBytes, randomInt, sign, createPrivateKey } from 'node:crypto';
import { EvidenceError } from './scoring.mjs';
import { RATE_LIMIT_SQL } from './rate-limits.mjs';

const PROJECT = 'roadconquest-9f0eb';
const FID = /^[A-Za-z0-9_-]{22}$/;
const BINDING = /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/;
export function createFcmSender(raw, fetcher = fetch, now = Date.now) {
  if (!raw) return null;
  const account = JSON.parse(raw);
  if (account.project_id !== PROJECT || !account.client_email?.endsWith('@' + PROJECT + '.iam.gserviceaccount.com') ||
      !account.private_key?.startsWith('-----BEGIN PRIVATE KEY-----')) throw new Error('Invalid FCM service account');
  let cached; let pending; let signingKey;
  async function accessToken() {
    if (cached && cached.expires > now()) return cached.token;
    if (!pending) pending = (async () => {
      const issued = Math.floor(now() / 1000);
      const b64 = value => Buffer.from(JSON.stringify(value)).toString('base64url');
      const assertion = b64({ alg: 'RS256', typ: 'JWT' }) + '.' + b64({ iss: account.client_email,
        scope: 'https://www.googleapis.com/auth/firebase.messaging', aud: 'https://oauth2.googleapis.com/token',
        iat: issued, exp: issued + 3600 });
      signingKey ??= createPrivateKey(account.private_key);
      const jwt = assertion + '.' + sign('RSA-SHA256', Buffer.from(assertion), signingKey).toString('base64url');
      const response = await fetcher('https://oauth2.googleapis.com/token', { method: 'POST', redirect: 'error',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, signal: AbortSignal.timeout(3000),
        body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: jwt }).toString() });
      if (!response.ok) {
        // No error body is needed. Release its connection without waiting on
        // cleanup or replacing the original retryable authorization failure.
        void response.body?.cancel().catch(() => {});
        throw new Error('Notification authorization unavailable');
      }
      const value = await response.json();
      if (typeof value.access_token !== 'string' || !Number.isFinite(value.expires_in) || value.expires_in < 120) throw new Error('Invalid notification authorization');
      cached = { token: value.access_token, expires: now() + (Math.min(3600, value.expires_in) - 60) * 1000 };
      return cached.token;
    })().finally(() => { pending = null; });
    return pending;
  }
  return async (token, binding, validateOnly = false, kind = 'friend_request') => {
    const access = await accessToken();
    // Data-only messages are checked on-device against current consent and account binding.
    // Never include names, account IDs, GPS, credentials or arbitrary message text.
    const response = await fetcher(`https://fcm.googleapis.com/v1/projects/${PROJECT}/messages:send`, {
      method: 'POST', redirect: 'error', signal: AbortSignal.timeout(3000),
      headers: { Authorization: 'Bearer ' + access, 'Content-Type': 'application/json' },
      body: JSON.stringify({ validate_only: validateOnly, message: { fid: token,
        data: { kind, binding }, android: { priority: 'normal', ttl: '3600s', collapse_key: kind } } })
    });
    // A delayed rejection of an older token must not discard a newer refresh.
    if (response.status === 401 && cached?.token === access) cached = null;
    if (response.ok) {
      void response.body?.cancel().catch(() => {});
      return 'sent';
    }
    const error = await response.json().catch(() => ({}));
    return error.error?.details?.some(item => item.errorCode === 'UNREGISTERED') ? 'unregistered' : 'unavailable';
  };
}

export function createNotifications(pool, { pepper, sender, deliveryLimit, sampleForumPost = () => randomInt(4) === 0 }) {
  const key = createHmac('sha256', pepper).update('roadconquest-fcm-token-encryption-v1').digest();
  const aad = Buffer.from('roadconquest-fcm-token-v1');
  const digest = token => createHash('sha256').update(token).digest();
  const seal = token => {
    const iv = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', key, iv);
    cipher.setAAD(aad);
    return Buffer.concat([iv, cipher.update(token.padEnd(100, '\0'), 'utf8'), cipher.final(), cipher.getAuthTag()]);
  };
  const open = bytes => {
    const cipher = createDecipheriv('aes-256-gcm', key, bytes.subarray(0, 12));
    cipher.setAAD(aad);
    cipher.setAuthTag(bytes.subarray(-16));
    const plaintext = cipher.update(bytes.subarray(12, -16));
    const tail = cipher.final(); // Authentication still completes before a token is exposed.
    return (tail.length ? Buffer.concat([plaintext, tail]) : plaintext).toString('utf8').replace(/\0+$/, '');
  };
  return {
    configured: Boolean(sender),
    async register(user, body) {
      const fields = Object.keys(body).sort().join(',');
      const legacy = fields === 'binding,fid';
      if ((!legacy && fields !== 'binding,fid,friend_posts,friend_requests') ||
          (!legacy && (typeof body.friend_posts !== 'boolean' || typeof body.friend_requests !== 'boolean')) ||
          typeof body.fid !== 'string' || !FID.test(body.fid) || !BINDING.test(body.binding))
        throw new EvidenceError('Invalid notification registration.', 400);
      if (!sender) throw new EvidenceError('Notifications are unavailable.', 503);
      const tokenHash = digest(body.fid);
      const db = await pool.connect();
      try {
        await db.query('BEGIN');
        // The foreign key locks session ownership during insertion and cascades on logout.
        // Validate ownership and remove a different prior FID in one round trip.
        // An invalid/expired session cannot delete another session's device.
        const session = await db.query(`WITH active_session AS (
          SELECT token_hash FROM sessions WHERE token_hash=$1 AND user_id=$2 AND expires_at>CURRENT_TIMESTAMP
        ), removed AS (
          DELETE FROM notification_devices WHERE session_hash IN (SELECT token_hash FROM active_session)
            AND token_hash<>$3
        ) SELECT 1 FROM active_session`, [user.session_hash, user.id, tokenHash]);
        if (!session.rowCount) throw new EvidenceError('Authentication required.', 401);
        await db.query(`INSERT INTO notification_devices(token_hash,session_hash,encrypted_token,binding,friend_requests_enabled,friend_posts_enabled)
          VALUES ($1,$2,$3,$4,$5,$6) ON CONFLICT(token_hash) DO UPDATE SET session_hash=EXCLUDED.session_hash,
          encrypted_token=EXCLUDED.encrypted_token,binding=EXCLUDED.binding,
          friend_requests_enabled=EXCLUDED.friend_requests_enabled,
          friend_posts_enabled=EXCLUDED.friend_posts_enabled,updated_at=CURRENT_TIMESTAMP`,
          [tokenHash, user.session_hash, seal(body.fid), body.binding,
            legacy ? true : body.friend_requests, legacy ? false : body.friend_posts]);
        await db.query('COMMIT');
      } catch (error) {
        await db.query('ROLLBACK');
        if (error.code === '23503') throw new EvidenceError('Authentication required.', 401);
        throw error;
      }
      finally { db.release(); }
      return { ok: true };
    },
    async remove(user) {
      await pool.query('DELETE FROM notification_devices WHERE session_hash=$1', [user.session_hash]);
      return { ok: true };
    },
    async friendRequest(userId) {
      if (!sender) return;
      const permit = deliveryLimit?.(userId);
      // Preserve the same per-recipient reservation even if it has no devices;
      // a denied permit sends no registrations to the Function. Read only the
      // ciphertext and binding: the token hash can be derived if Google rejects it.
      const { rows } = await pool.query(`${permit ? `WITH permit AS (${RATE_LIMIT_SQL})` : ''}
        SELECT d.encrypted_token,d.binding FROM notification_devices d
        JOIN sessions s ON s.token_hash=d.session_hash WHERE s.user_id=${permit ? '$5' : '$1'}
        AND s.expires_at>CURRENT_TIMESTAMP AND d.updated_at>CURRENT_TIMESTAMP-INTERVAL '30 days'
        AND d.friend_requests_enabled
        ${permit ? 'AND EXISTS(SELECT 1 FROM permit WHERE allowed)' : ''}
        ORDER BY d.updated_at DESC LIMIT 5`, permit ? [...permit,userId] : [userId]);
      await Promise.allSettled(rows.map(async row => {
        const token = open(row.encrypted_token);
        const result = await sender(token, row.binding);
        if (result === 'unregistered') await pool.query('DELETE FROM notification_devices WHERE token_hash=$1 AND encrypted_token=$2', [digest(token), row.encrypted_token]);
      }));
    },
    async forumPost(authorId) {
      // Most approved posts cost zero database/FCM requests. Only a sampled
      // quarter can alert friends, and each recipient is capped once per seven
      // days across all friends and all API instances.
      if (!sender || !sampleForumPost()) return;
      const { rows } = await pool.query(`WITH eligible AS (
        SELECT CASE WHEN f.user_low=$1 THEN f.user_high ELSE f.user_low END AS user_id
        FROM friendships f
        WHERE f.accepted AND (f.user_low=$1 OR f.user_high=$1)
          AND NOT EXISTS (
            SELECT 1 FROM forum_blocks b
            WHERE (b.user_id=$1 AND b.blocked_id=CASE WHEN f.user_low=$1 THEN f.user_high ELSE f.user_low END)
               OR (b.blocked_id=$1 AND b.user_id=CASE WHEN f.user_low=$1 THEN f.user_high ELSE f.user_low END)
          )
          AND EXISTS (
            SELECT 1 FROM notification_devices d JOIN sessions s ON s.token_hash=d.session_hash
            WHERE s.user_id=CASE WHEN f.user_low=$1 THEN f.user_high ELSE f.user_low END
              AND s.expires_at>CURRENT_TIMESTAMP AND d.updated_at>CURRENT_TIMESTAMP-INTERVAL '30 days'
              AND d.friend_posts_enabled
          )
        LIMIT 50
      ), claimed AS (
        INSERT INTO forum_post_push(user_id,last_sent_at)
        SELECT user_id,CURRENT_TIMESTAMP FROM eligible WHERE TRUE
        ON CONFLICT(user_id) DO UPDATE SET last_sent_at=EXCLUDED.last_sent_at
          WHERE forum_post_push.last_sent_at<=CURRENT_TIMESTAMP-INTERVAL '7 days'
        RETURNING user_id
      )
      SELECT d.encrypted_token,d.binding FROM claimed c
      CROSS JOIN LATERAL (
        SELECT d.encrypted_token,d.binding FROM notification_devices d
        JOIN sessions s ON s.token_hash=d.session_hash
        WHERE s.user_id=c.user_id AND s.expires_at>CURRENT_TIMESTAMP
          AND d.updated_at>CURRENT_TIMESTAMP-INTERVAL '30 days'
          AND d.friend_posts_enabled ORDER BY d.updated_at DESC LIMIT 5
      ) d`, [authorId]);
      await Promise.allSettled(rows.map(async row => {
        const token = open(row.encrypted_token);
        const result = await sender(token, row.binding, false, 'friend_post');
        if (result === 'unregistered') await pool.query(
          'DELETE FROM notification_devices WHERE token_hash=$1 AND encrypted_token=$2', [digest(token), row.encrypted_token]);
      }));
    }
  };
}
