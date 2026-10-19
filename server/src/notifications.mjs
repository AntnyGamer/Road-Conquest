import { createCipheriv, createDecipheriv, createHash, createHmac, randomBytes, sign } from 'node:crypto';
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
  let cached; let pending;
  async function accessToken() {
    if (cached && cached.expires > now()) return cached.token;
    if (!pending) pending = (async () => {
      const issued = Math.floor(now() / 1000);
      const b64 = value => Buffer.from(JSON.stringify(value)).toString('base64url');
      const assertion = b64({ alg: 'RS256', typ: 'JWT' }) + '.' + b64({ iss: account.client_email,
        scope: 'https://www.googleapis.com/auth/firebase.messaging', aud: 'https://oauth2.googleapis.com/token',
        iat: issued, exp: issued + 3600 });
      const jwt = assertion + '.' + sign('RSA-SHA256', Buffer.from(assertion), account.private_key).toString('base64url');
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
  return async (token, binding, validateOnly = false) => {
    const access = await accessToken();
    // Data-only messages are checked on-device against current consent and account binding.
    // Never include names, account IDs, GPS, credentials or arbitrary message text.
    const response = await fetcher(`https://fcm.googleapis.com/v1/projects/${PROJECT}/messages:send`, {
      method: 'POST', redirect: 'error', signal: AbortSignal.timeout(3000),
      headers: { Authorization: 'Bearer ' + access, 'Content-Type': 'application/json' },
      body: JSON.stringify({ validate_only: validateOnly, message: { fid: token,
        data: { kind: 'friend_request', binding }, android: { priority: 'normal', ttl: '3600s', collapse_key: 'friend_request' } } })
    });
    // A delayed rejection of an older token must not discard a newer refresh.
    if (response.status === 401 && cached?.token === access) cached = null;
    if (response.ok) { await response.body?.cancel(); return 'sent'; }
    const error = await response.json().catch(() => ({}));
    return error.error?.details?.some(item => item.errorCode === 'UNREGISTERED') ? 'unregistered' : 'unavailable';
  };
}

export function createNotifications(pool, { pepper, sender, deliveryLimit }) {
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
    return Buffer.concat([cipher.update(bytes.subarray(12, -16)), cipher.final()]).toString('utf8').replace(/\0+$/, '');
  };
  return {
    configured: Boolean(sender),
    async register(user, body) {
      if (Object.keys(body).sort().join(',') !== 'binding,fid' || typeof body.fid !== 'string' || !FID.test(body.fid) || !BINDING.test(body.binding))
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
        await db.query(`INSERT INTO notification_devices(token_hash,session_hash,encrypted_token,binding)
          VALUES ($1,$2,$3,$4) ON CONFLICT(token_hash) DO UPDATE SET session_hash=EXCLUDED.session_hash,
          encrypted_token=EXCLUDED.encrypted_token,binding=EXCLUDED.binding,updated_at=CURRENT_TIMESTAMP`,
          [tokenHash, user.session_hash, seal(body.fid), body.binding]);
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
        ${permit ? 'AND EXISTS(SELECT 1 FROM permit WHERE allowed)' : ''}
        ORDER BY d.updated_at DESC LIMIT 5`, permit ? [...permit,userId] : [userId]);
      await Promise.allSettled(rows.map(async row => {
        const token = open(row.encrypted_token);
        const result = await sender(token, row.binding);
        if (result === 'unregistered') await pool.query('DELETE FROM notification_devices WHERE token_hash=$1 AND encrypted_token=$2', [digest(token), row.encrypted_token]);
      }));
    }
  };
}
