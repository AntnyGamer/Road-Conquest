import { createCipheriv, createDecipheriv, createHash, createHmac, randomBytes, sign, verify, createPublicKey } from 'node:crypto';
import { boundedJson, EvidenceError } from './scoring.mjs';

export const FORUM_PRODUCT = 'forum_plus_lifetime';
const PACKAGE = 'com.roadconquest.app';
export const purchaseHash = token => createHash('sha256').update(token).digest('hex');
export const forumAccountBinding = (pepper, id) => createHmac('sha256', pepper)
  .update('roadconquest-forum-purchase-v1\0' + id).digest('hex');

export function purchaseCodec(pepper) {
  const key = createHmac('sha256', pepper).update('roadconquest-forum-token-v1').digest();
  return {
    seal(token) {
      const iv = randomBytes(12), cipher = createCipheriv('aes-256-gcm', key, iv);
      return Buffer.concat([iv, cipher.update(token, 'utf8'), cipher.final(), cipher.getAuthTag()]);
    },
    open(bytes) {
      const cipher = createDecipheriv('aes-256-gcm', key, bytes.subarray(0, 12));
      cipher.setAuthTag(bytes.subarray(-16));
      return Buffer.concat([cipher.update(bytes.subarray(12, -16)), cipher.final()]).toString('utf8');
    }
  };
}

export function createForumPurchaseVerifier(raw, request = fetch, now = Date.now) {
  if (!raw) return null;
  const account = JSON.parse(raw);
  if (account.type !== 'service_account' || typeof account.client_email !== 'string' || !account.private_key) {
    throw new Error('Invalid forum Google Play service account');
  }
  let cached, pending;
  async function access() {
    if (cached && cached.until > now()) return cached.token;
    if (!pending) pending = (async () => {
      const issued = Math.floor(now() / 1000);
      const encode = data => Buffer.from(JSON.stringify(data)).toString('base64url');
      const claims = encode({ alg: 'RS256', typ: 'JWT' }) + '.' + encode({ iss: account.client_email,
        scope: 'https://www.googleapis.com/auth/androidpublisher', aud: 'https://oauth2.googleapis.com/token',
        iat: issued, exp: issued + 3600 });
      const assertion = claims + '.' + sign('RSA-SHA256', Buffer.from(claims), account.private_key).toString('base64url');
      const reply = await boundedJson(await request('https://oauth2.googleapis.com/token', {
        method: 'POST', redirect: 'error', signal: AbortSignal.timeout(8000),
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion })
      }), 32768);
      if (typeof reply.access_token !== 'string' || !Number.isFinite(reply.expires_in) || reply.expires_in < 120) {
        throw new EvidenceError('Purchase verification is temporarily unavailable.', 503);
      }
      cached = { token: reply.access_token, until: now() + (Math.min(reply.expires_in, 3600) - 60) * 1000 };
      return cached.token;
    })().finally(() => { pending = null; });
    return pending;
  }
  return async (token, binding) => {
    if (typeof token !== 'string' || !token.length || token.length > 4096 || /[\r\n]/.test(token)) {
      throw new EvidenceError('Invalid purchase token.', 400);
    }
    const headers = { Authorization: 'Bearer ' + await access() };
    const endpoint = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PACKAGE}/purchases/products/${FORUM_PRODUCT}/tokens/${encodeURIComponent(token)}`;
    const response = await request(endpoint, { headers, redirect: 'error', signal: AbortSignal.timeout(8000) });
    if (response.status === 400 || response.status === 404 || response.status === 410) {
      void response.body?.cancel().catch(() => {});
      return false;
    }
    const purchase = await boundedJson(response, 32768);
    if (purchase.purchaseState !== 0) return false;
    if (purchase.obfuscatedExternalAccountId !== binding ||
        (purchase.productId != null && purchase.productId !== FORUM_PRODUCT) || purchase.consumptionState !== 0) {
      throw new EvidenceError('This purchase belongs to another account or product.', 403);
    }
    if (purchase.acknowledgementState === 0) {
      const ack = await request(endpoint + ':acknowledge', {
        method: 'POST', redirect: 'error', headers: { ...headers, 'Content-Type': 'application/json' },
        body: '{}', signal: AbortSignal.timeout(8000)
      });
      if (!ack.ok) { void ack.body?.cancel().catch(() => {}); throw new EvidenceError('Purchase acknowledgement is temporarily unavailable.', 503); }
      await ack.body?.cancel();
    } else if (purchase.acknowledgementState !== 1) {
      throw new EvidenceError('Purchase verification is temporarily unavailable.', 503);
    }
    return true;
  };
}

/** Google Pub/Sub OIDC identity, rather than a caller-supplied purchase or refund flag. */
export function createForumWebhookVerifier(audience, email, request = fetch, now = Date.now) {
  if (!audience || !email) return null;
  let keys = [], expires = 0, pending;
  return async authorization => {
    try {
      if (typeof authorization !== 'string' || authorization.length > 16000 || !authorization.startsWith('Bearer ')) return false;
      const parts = authorization.slice(7).split('.');
      if (parts.length !== 3 || parts.some(p => !/^[\w-]+$/.test(p))) return false;
      const header = JSON.parse(Buffer.from(parts[0], 'base64url'));
      const claims = JSON.parse(Buffer.from(parts[1], 'base64url'));
      const time = now() / 1000;
      if (header.alg !== 'RS256' || typeof header.kid !== 'string' || header.kid.length > 128 ||
          !['accounts.google.com', 'https://accounts.google.com'].includes(claims.iss) || claims.aud !== audience ||
          claims.email !== email || claims.email_verified !== true || !Number.isFinite(claims.exp) || claims.exp <= time ||
          !Number.isFinite(claims.iat) || claims.iat > time + 60 || time - claims.iat > 3600) return false;
      if (expires <= now()) {
        if (!pending) pending = (async () => {
          const reply = await boundedJson(await request('https://www.googleapis.com/oauth2/v3/certs', {
            redirect: 'error', signal: AbortSignal.timeout(5000)
          }), 65536);
          if (!Array.isArray(reply.keys)) throw new Error('Invalid signing keys');
          keys = reply.keys.filter(k => k.kty === 'RSA' && k.alg === 'RS256').slice(0, 10);
          expires = now() + 5 * 60_000;
        })().finally(() => { pending = null; });
        await pending;
      }
      const key = keys.find(k => k.kid === header.kid);
      return Boolean(key && verify('RSA-SHA256', Buffer.from(parts[0] + '.' + parts[1]),
        createPublicKey({ key, format: 'jwk' }), Buffer.from(parts[2], 'base64url')));
    } catch { return false; }
  };
}
