import { createHash, randomUUID } from 'node:crypto';
import { EvidenceError } from './scoring.mjs';
import { normalizeUsername } from './security.mjs';
import { FORUM_RULES_VERSION, forumGifUrl } from './forum-moderation.mjs';
import { FORUM_PRODUCT, forumAccountBinding, purchaseCodec, purchaseHash } from './forum-billing.mjs';

const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i;
const REACTIONS = new Set(['like', 'love', 'celebrate']);
const REPORTS = new Set(['harassment', 'hate', 'sexual', 'violence', 'spam', 'other']);
const hash = value => createHash('sha256').update(value).digest('hex');
const blocked = row => row?.banned === true || (row?.muted_until && new Date(row.muted_until).getTime() > Date.now());
export const POST_COOLDOWN_MS = 10 * 60_000;

export function moderationPenalty(violations, now = Date.now()) {
  const count = Math.min(3, violations + 1);
  return { violations: count, banned: count === 3,
    muted_until: count === 2 ? new Date(now + 7 * 86400000).toISOString() : null };
}

function postInput(body) {
  if (!body || Object.keys(body).sort().join(',') !== 'gif_url,request_id,rules_version,text' ||
      typeof body.request_id !== 'string' || !UUID.test(body.request_id) || typeof body.text !== 'string' ||
      body.text.length > 1200 || /[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(body.text) ||
      body.rules_version !== FORUM_RULES_VERSION) throw new EvidenceError('Accept the community rules and enter a post up to 1,200 characters.', 400);
  const text = body.text.trim();
  const gifUrl = forumGifUrl(body.gif_url);
  if (!text && !gifUrl) throw new EvidenceError('Write a post or attach a GIF.', 400);
  return { text, gifUrl, requestId: body.request_id, contentHash: hash(JSON.stringify([text, gifUrl, FORUM_RULES_VERSION])) };
}

function cursorFrom(value) {
  if (!value) return null;
  try {
    if (typeof value !== 'string' || value.length > 200 || !/^[\w-]+$/.test(value)) throw new Error();
    const cursor = JSON.parse(Buffer.from(value, 'base64url'));
    if (!Array.isArray(cursor) || cursor.length !== 2 || !UUID.test(cursor[1]) ||
        typeof cursor[0] !== 'string' || !Number.isFinite(Date.parse(cursor[0]))) throw new Error();
    return cursor;
  } catch { throw new EvidenceError('Invalid community page.', 400); }
}

export function createForum(pool, { pepper, moderate, verifyPurchase, verifyWebhook, prepare = async () => {}, adminIds = [] }) {
  const codec = purchaseCodec(pepper);
  const admins = new Set(adminIds.map(String));
  const pendingPosts = new Map();
  const binding = id => forumAccountBinding(pepper, id);

  async function profile(db, userId, lock = false) {
    await db.query('INSERT INTO forum_profiles(user_id) VALUES($1) ON CONFLICT DO NOTHING', [userId]);
    const { rows } = await db.query(`SELECT violations,muted_until,banned,plus_active,rules_version,
      last_post_at + INTERVAL '10 minutes' AS next_post_at FROM forum_profiles
      WHERE user_id=$1${lock ? ' FOR UPDATE' : ''}`, [userId]);
    if (!rows[0]) throw new EvidenceError('Authentication required.', 401);
    return rows[0];
  }
  function enforce(row) {
    if (row.banned) throw new EvidenceError('This account is banned from participating in the forum.', 403);
    if (blocked(row)) throw new EvidenceError('Forum participation is muted until ' + new Date(row.muted_until).toISOString() + '.', 403);
  }
  function enforceCooldown(row) {
    if (row.next_post_at && new Date(row.next_post_at).getTime() > Date.now()) {
      throw new EvidenceError('You can post once every 10 minutes. Your next post is available at ' +
        new Date(row.next_post_at).toISOString() + '.', 429);
    }
  }
  async function transact(work) {
    const db = await pool.connect();
    try {
      await db.query('BEGIN');
      const result = await work(db);
      await db.query('COMMIT');
      return result;
    } catch (error) { await db.query('ROLLBACK').catch(() => {}); throw error; }
    finally { db.release(); }
  }
  async function strike(db, userId, row) {
    const penalty = moderationPenalty(row.violations);
    await db.query('UPDATE forum_profiles SET violations=$2,muted_until=$3,banned=$4 WHERE user_id=$1',
      [userId, penalty.violations, penalty.muted_until, penalty.banned]);
    return penalty;
  }
  function postResult(row, current) {
    return { id: row.id, removed: row.state === 'removed', deleted: row.state === 'deleted',
      violations: current.violations, banned: current.banned, muted_until: current.muted_until,
      next_post_at: current.next_post_at,
      message: row.state === 'removed' ? 'This post was removed for violating the community rules.' : null };
  }
  async function existing(db, userId, input) {
    const { rows } = await db.query('SELECT id,state,content_hash FROM forum_posts WHERE user_id=$1 AND request_id=$2', [userId, input.requestId]);
    if (rows[0] && rows[0].content_hash !== input.contentHash) throw new EvidenceError('This submission ID was already used for another post.', 409);
    return rows[0];
  }

  return {
    async list(user, cursorValue) {
      const cursor = cursorFrom(cursorValue);
      // One bounded query: page, author highlight, counts, own reaction, and viewer state.
      // No polling, N+1 profile lookups, or downloads of GIF bytes through this endpoint.
      const { rows } = await pool.query(`WITH page AS (
        SELECT p.id,p.user_id,p.body,p.gif_url,p.gif_hash,p.created_at,u.username_display AS username,
          COALESCE(fp.plus_active,FALSE) AS highlighted
        FROM forum_posts p JOIN users u ON u.id=p.user_id
        LEFT JOIN forum_profiles fp ON fp.user_id=u.id
        WHERE p.state='visible' AND ($2::timestamptz IS NULL OR (p.created_at,p.id)<($2,$3::uuid))
          AND NOT EXISTS(SELECT 1 FROM forum_blocks b WHERE b.user_id=$1 AND b.blocked_id=p.user_id)
        ORDER BY p.created_at DESC,p.id DESC LIMIT 21
      ), counts AS (
        SELECT r.post_id,COUNT(*) FILTER(WHERE reaction='like') AS likes,
          COUNT(*) FILTER(WHERE reaction='love') AS loves,COUNT(*) FILTER(WHERE reaction='celebrate') AS celebrates,
          MAX(reaction) FILTER(WHERE r.user_id=$1) AS mine
        FROM forum_reactions r JOIN page p ON p.id=r.post_id GROUP BY r.post_id
      ) SELECT COALESCE((SELECT json_agg(json_build_object(
        'id',p.id,'username',p.username,'text',p.body,'gif_url',p.gif_url,'gif_hash',p.gif_hash,
        'created_at',p.created_at,'highlighted',p.highlighted,'own',COALESCE(p.user_id=$1,FALSE),
        'reactions',json_build_object('like',COALESCE(c.likes,0),'love',COALESCE(c.loves,0),
          'celebrate',COALESCE(c.celebrates,0)),'my_reaction',c.mine)
        ORDER BY p.created_at DESC,p.id DESC) FROM page p LEFT JOIN counts c ON c.post_id=p.id),'[]'::json) AS posts,
        (SELECT json_build_object('username',u.username_display,'violations',COALESCE(f.violations,0),
          'muted_until',f.muted_until,'banned',COALESCE(f.banned,FALSE),'plus',COALESCE(f.plus_active,FALSE),
          'next_post_at',f.last_post_at + INTERVAL '10 minutes')
         FROM users u LEFT JOIN forum_profiles f ON f.user_id=u.id WHERE u.id=$1) AS viewer`,
      [user?.id || null, cursor?.[0] || null, cursor?.[1] || null]);
      const posts = rows[0].posts;
      const hasMore = posts.length > 20;
      if (hasMore) posts.pop();
      const last = posts.at(-1);
      return { available: true, posting_available: Boolean(moderate), purchases_available: Boolean(verifyPurchase && verifyWebhook),
        product_id: FORUM_PRODUCT, rules_version: FORUM_RULES_VERSION, posts,
        next: hasMore ? Buffer.from(JSON.stringify([last.created_at,last.id])).toString('base64url') : null,
        viewer: rows[0].viewer ? { ...rows[0].viewer, billing_account_id: binding(user.id), moderator: admins.has(String(user.id)) } : null };
    },
    async post(user, body) {
      const input = postInput(body);
      const key = user.id + ':' + input.requestId + ':' + input.contentHash;
      if (pendingPosts.has(key)) return pendingPosts.get(key);
      const prior = await existing(pool, user.id, input);
      if (prior) return postResult(prior, await profile(pool, user.id));
      if (!moderate) throw new EvidenceError('Community posting is temporarily unavailable.', 503);
      const state = await profile(pool, user.id);
      // The first lookup can precede another instance's commit while this profile
      // read follows it. Return that receipt before enforcing its new penalty/cooldown.
      if (blocked(state) || (state.next_post_at && new Date(state.next_post_at).getTime() > Date.now())) {
        const completed = await existing(pool, user.id, input);
        if (completed) return postResult(completed,state);
      }
      enforce(state);
      enforceCooldown(state);
      if (input.gifUrl && !state.plus_active) throw new EvidenceError('Forum Plus is required to attach GIFs.', 403);
      if (pendingPosts.has(key)) return pendingPosts.get(key);
      if ([...pendingPosts.keys()].some(k => k.startsWith(user.id + ':'))) throw new EvidenceError('Your previous post is still being checked.', 429);
      if (pendingPosts.size >= 32) throw new EvidenceError('Community is busy. Please try again shortly.', 503);
      const task = (async () => {
        const verdict = await moderate(input);
        if (typeof verdict?.flagged !== 'boolean') throw new EvidenceError('Moderation is temporarily unavailable.', 503);
        return transact(async db => {
          const current = await profile(db, user.id, true);
          const raced = await existing(db, user.id, input);
          if (raced) return postResult(raced, current);
          enforce(current);
          enforceCooldown(current);
          if (input.gifUrl && !current.plus_active) throw new EvidenceError('Forum Plus is required to attach GIFs.', 403);
          const penalty = verdict.flagged ? await strike(db, user.id, current) : current;
          const id = randomUUID(), state = verdict.flagged ? 'removed' : 'visible';
          await db.query(`INSERT INTO forum_posts(id,user_id,request_id,content_hash,body,gif_url,gif_hash,state,moderation)
            VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)`, [id,user.id,input.requestId,input.contentHash,
            verdict.flagged ? '' : input.text, verdict.flagged ? null : input.gifUrl,
            verdict.flagged ? null : verdict.gifHash, state,
            JSON.stringify({ model: verdict.model, categories: verdict.categories || [], rules_version: FORUM_RULES_VERSION })]);
          const updated = await db.query(`UPDATE forum_profiles SET rules_version=$2,last_post_at=CURRENT_TIMESTAMP
            WHERE user_id=$1 RETURNING last_post_at + INTERVAL '10 minutes' AS next_post_at`, [user.id,FORUM_RULES_VERSION]);
          return postResult({ id, state }, { ...penalty, ...updated.rows[0] });
        });
      })();
      pendingPosts.set(key, task);
      try { return await task; } finally { pendingPosts.delete(key); }
    },
    async react(user, postId, reaction) {
      if (!UUID.test(postId) || (reaction !== null && !REACTIONS.has(reaction))) throw new EvidenceError('Invalid reaction.', 400);
      return transact(async db => {
        enforce(await profile(db,user.id,true));
        const post = await db.query(`SELECT id FROM forum_posts p WHERE id=$1 AND state='visible'
          AND NOT EXISTS(SELECT 1 FROM forum_blocks b WHERE b.user_id=$2 AND b.blocked_id=p.user_id) FOR UPDATE`, [postId,user.id]);
        if (!post.rowCount) throw new EvidenceError('Post is unavailable.', 404);
        if (reaction === null) await db.query('DELETE FROM forum_reactions WHERE post_id=$1 AND user_id=$2', [postId,user.id]);
        else await db.query(`INSERT INTO forum_reactions(post_id,user_id,reaction) VALUES($1,$2,$3)
          ON CONFLICT(post_id,user_id) DO UPDATE SET reaction=EXCLUDED.reaction
          WHERE forum_reactions.reaction IS DISTINCT FROM EXCLUDED.reaction`, [postId,user.id,reaction]);
        return { ok: true };
      });
    },
    async remove(user, postId) {
      if (!UUID.test(postId)) throw new EvidenceError('Invalid post.', 400);
      await pool.query(`UPDATE forum_posts SET state='deleted',body='',gif_url=NULL,gif_hash=NULL
        WHERE id=$1 AND user_id=$2 AND state='visible'`, [postId,user.id]);
      return { ok: true };
    },
    async block(user, username, enabled) {
      const target = normalizeUsername(username);
      if (!target || typeof enabled !== 'boolean') throw new EvidenceError('Invalid community account.', 400);
      if (enabled) await pool.query(`INSERT INTO forum_blocks(user_id,blocked_id)
        SELECT $1,id FROM users WHERE username_key=$2 AND id<>$1 ON CONFLICT DO NOTHING`, [user.id,target.key]);
      else await pool.query(`DELETE FROM forum_blocks WHERE user_id=$1 AND blocked_id=(SELECT id FROM users WHERE username_key=$2)`, [user.id,target.key]);
      return { ok: true };
    },
    async blocks(user) {
      const { rows } = await pool.query(`SELECT u.username_display AS username FROM forum_blocks b
        JOIN users u ON u.id=b.blocked_id WHERE b.user_id=$1 ORDER BY u.username_key LIMIT 100`, [user.id]);
      return { accounts: rows.map(r => r.username) };
    },
    async report(user, postId, reason) {
      if (!UUID.test(postId) || !REPORTS.has(reason)) throw new EvidenceError('Choose a report reason.', 400);
      await pool.query(`INSERT INTO forum_reports(post_id,reporter_id,reason)
        SELECT id,$2,$3 FROM forum_posts WHERE id=$1 AND state='visible'
        ON CONFLICT(post_id,reporter_id) DO NOTHING`, [postId,user.id,reason]);
      return { ok: true };
    },
    async reports(user) {
      if (!admins.has(String(user.id))) throw new EvidenceError('Moderator access required.', 403);
      const { rows } = await pool.query(`SELECT p.id,p.body AS text,p.gif_url,p.gif_hash,u.username_display AS username,
        COUNT(*) AS reports,MAX(r.created_at) AS reported_at FROM forum_reports r
        JOIN forum_posts p ON p.id=r.post_id JOIN users u ON u.id=p.user_id
        WHERE NOT r.reviewed AND p.state='visible' GROUP BY p.id,u.username_display
        ORDER BY MAX(r.created_at) LIMIT 50`);
      return { posts: rows };
    },
    async review(user, postId, remove) {
      if (!admins.has(String(user.id)) || !UUID.test(postId) || typeof remove !== 'boolean') throw new EvidenceError('Moderator access required.', 403);
      return transact(async db => {
        const found = await db.query('SELECT user_id FROM forum_posts WHERE id=$1', [postId]);
        if (!found.rowCount) throw new EvidenceError('Post is unavailable.', 404);
        // Lock account before post everywhere, preventing moderation/reaction lock inversion.
        const current = await profile(db,found.rows[0].user_id,true);
        const { rows } = await db.query('SELECT user_id,state FROM forum_posts WHERE id=$1 FOR UPDATE', [postId]);
        if (remove && rows[0].state === 'visible') {
          await strike(db, rows[0].user_id, current);
          await db.query("UPDATE forum_posts SET state='removed',body='',gif_url=NULL,gif_hash=NULL WHERE id=$1", [postId]);
        }
        await db.query('UPDATE forum_reports SET reviewed=TRUE WHERE post_id=$1 AND NOT reviewed', [postId]);
        return { ok: true };
      });
    },
    async appealReview(user, username) {
      if (!admins.has(String(user.id))) throw new EvidenceError('Moderator access required.', 403);
      const target = normalizeUsername(username);
      if (!target) throw new EvidenceError('Invalid username.', 400);
      await pool.query(`UPDATE forum_profiles SET violations=0,muted_until=NULL,banned=FALSE
        WHERE user_id=(SELECT id FROM users WHERE username_key=$1)`, [target.key]);
      return { ok: true };
    },
    async purchase(user, token) {
      if (!verifyPurchase || !verifyWebhook) throw new EvidenceError('Forum Plus purchases are temporarily unavailable.', 503);
      if (typeof token !== 'string' || !token.length || token.length > 4096) throw new EvidenceError('Invalid purchase token.', 400);
      const digest = purchaseHash(token);
      return transact(async db => {
        await profile(db,user.id,true);
        const prior = await db.query('SELECT user_id FROM forum_purchases WHERE token_hash=$1 FOR UPDATE', [digest]);
        if (prior.rowCount && String(prior.rows[0].user_id) !== String(user.id)) throw new EvidenceError('Purchase belongs to another account.', 403);
        // Serialize verification with refunds/restores so an older response cannot overwrite a refund.
        const active = await verifyPurchase(token, binding(user.id));
        if (active) await db.query(`INSERT INTO forum_purchases(token_hash,user_id,encrypted_token,active)
          VALUES($1,$2,$3,TRUE) ON CONFLICT(token_hash) DO UPDATE SET active=TRUE,checked_at=CURRENT_TIMESTAMP`, [digest,user.id,codec.seal(token)]);
        else await db.query('UPDATE forum_purchases SET active=FALSE,checked_at=CURRENT_TIMESTAMP WHERE token_hash=$1 AND user_id=$2', [digest,user.id]);
        const { rows } = await db.query(`UPDATE forum_profiles SET plus_active=EXISTS(
          SELECT 1 FROM forum_purchases WHERE user_id=$1 AND active) WHERE user_id=$1 RETURNING plus_active`, [user.id]);
        return { plus: rows[0].plus_active };
      });
    },
    async webhook(authorization, body) {
      if (!verifyWebhook || !await verifyWebhook(authorization)) throw new EvidenceError('Invalid notification identity.', 401);
      await prepare();
      const encoded = body?.message?.data, id = body?.message?.messageId;
      if (typeof encoded !== 'string' || encoded.length > 16000 || typeof id !== 'string' || id.length > 128) throw new EvidenceError('Invalid purchase notification.', 400);
      let notification;
      try { notification = JSON.parse(Buffer.from(encoded, 'base64')); } catch { throw new EvidenceError('Invalid purchase notification.', 400); }
      if (notification.packageName !== 'com.roadconquest.app') throw new EvidenceError('Wrong application.', 400);
      const item = notification.oneTimeProductNotification || notification.voidedPurchaseNotification;
      if (!item || (notification.oneTimeProductNotification ? item.sku !== FORUM_PRODUCT : item.productType !== 2)) return { ok: true };
      if (typeof item.purchaseToken !== 'string' || !item.purchaseToken.length || item.purchaseToken.length > 4096) throw new EvidenceError('Invalid purchase notification.', 400);
      if ((await pool.query('SELECT 1 FROM forum_billing_events WHERE id=$1', [id])).rowCount) return { ok: true };
      const digest = typeof item.purchaseToken === 'string' ? purchaseHash(item.purchaseToken) : '';
      const found = await pool.query('SELECT user_id FROM forum_purchases WHERE token_hash=$1', [digest]);
      if (found.rowCount) await this.purchase({ id: found.rows[0].user_id }, item.purchaseToken);
      await pool.query('INSERT INTO forum_billing_events(id) VALUES($1) ON CONFLICT DO NOTHING', [id]);
      await pool.query("DELETE FROM forum_billing_events WHERE created_at < CURRENT_TIMESTAMP - INTERVAL '30 days'");
      return { ok: true };
    }
  };
}
