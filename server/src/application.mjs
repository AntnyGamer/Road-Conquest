import { createHash, createHmac, randomBytes } from "node:crypto";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import pg from "pg";
import { databaseConfig } from "./database-config.mjs";
import {
  createSessionToken,
  hashPassword,
  hashSessionToken,
  normalizeUsername,
  validatePassword,
  verifyPassword
} from "./security.mjs";
import { competitionConfig, createCompetition } from "./competition.mjs";
import { createFriends } from "./friends.mjs";
import { createNotifications, createFcmSender } from "./notifications.mjs";
import { createRequestMaintenance } from "./maintenance.mjs";

const { Pool } = pg;
const DATABASE_URL = process.env.DATABASE_URL;
const PEPPER = process.env.PASSWORD_PEPPER || "";
const REQUIRE_HTTPS = process.env.REQUIRE_HTTPS === "1";
const TRUST_PROXY = process.env.TRUST_PROXY === "1";
const SESSION_DAYS = 30;
const JSON_LIMIT = 32 * 1024;
const TOKEN_RE = /^[A-Za-z0-9_-]{43}$/u;

const PRIVACY_HTML = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Road Conquest Privacy Policy</title>
<style>body{font:16px system-ui,sans-serif;max-width:760px;margin:40px auto;padding:0 20px;line-height:1.55}h1,h2{line-height:1.2}code{overflow-wrap:anywhere}</style></head>
<body><h1>Road Conquest Privacy Policy</h1>
<p><strong>Effective:</strong> October 16, 2026. <strong>Developer/publisher:</strong> AntnyGamer.</p>
<p>Road Conquest collects precise location data to record driven roads and reveal visited places, including in the background when automatic tracking is enabled and the app is closed or not in use. It also uses location to display your live map position. Precise driving history, explored places, points, purchases, and cosmetics are stored on your device unless you export them.</p>
<h2>Data sent to services</h2>
<p>Road matching for new roads sends bounded GPS coordinate batches, timestamps and accuracy through Road Conquest's Cloudflare map proxy to Geoapify, or to LocationIQ when Geoapify is unavailable or its allowance is exhausted. See <a href="https://locationiq.com/privacy">LocationIQ's privacy policy</a>. Repeat drives on previously saved roads may be recognized on-device without a network request. The map proxy processes matching coordinates transiently and does not retain GPS traces. It stores daily request totals and daily keyed client-address fingerprints to enforce separate free allowances; those fingerprints are deleted when a later day's requests are processed. OpenFreeMap and Esri provide map display data; Geoapify supplies backup street-map tiles. Map providers receive requests for areas you view. While the app is in the foreground, Android's system geocoder may process saved coordinates for place names, and U.S. Census TIGERweb receives individual saved GPS coordinates to verify the exact municipality visited. Outside the U.S., place names may be sent to the configured OpenStreetMap boundary service. Country and region overlays can fall back to public geoBoundaries datasets, selected by country code without sending GPS coordinates to geoBoundaries. Enabling place overlays downloads and locally caches public boundaries for discovered places.</p>
<p>Optional Firebase Crashlytics reporting is off by default and enabled only after you enable Share crash reports in Settings. When enabled, Google receives crash stack traces, app/device and operating-system information, diagnostic events and installation identifiers. Road Conquest does not attach saved routes, GPS history, usernames, passwords or session tokens, and does not include Google Analytics. Turning reporting off stops automatic reports and requests deletion of unsent reports on the device. See <a href="https://firebase.google.com/support/privacy">Firebase privacy information</a> for Google's handling of reports already sent.</p>
<p>Friend-request notifications and weekly challenge reminders are on by default unless you have already turned them off in Settings. Both require Android notification permission; friend notifications also require sign-in. When friend notifications are enabled and these requirements are met, the app initializes Firebase Cloud Messaging and shares a Firebase Installation ID with Google and the Road Conquest account service. The server encrypts the token and binds it to the current login session; logout, account deletion or disabling notifications removes that session's registration. Logout preserves your saved notification choice. Device registrations expire after 30 days without refresh. Google receives only a generic friend-request event and a random device binding, without usernames, passwords, Road Conquest session tokens or GPS. The phone checks the current setting, permission and account binding before showing a notification. Firebase may process device, app and installation information to deliver messages; notifications do not enable crash reports. Weekly challenge reminders are scheduled locally without Firebase or a cloud account. You can turn either notification type off independently in Settings.</p>
<p>The app downloads public provider availability switches from Cloudflare. These switches contain no personal data and cannot change road-verification rules or service URLs. UptimeRobot checks public service health endpoints and alerts the developer to outages; these monitoring requests contain no player account or driving data.</p>
<p>Optional Road Conquest accounts send your username and password over HTTPS for signup/login; passwords are salted and scrypt-hashed on the server and are not stored in plaintext. Android stores an encrypted session token. If you explicitly enable verified scoring, live non-mock GPS evidence and Google Play Integrity evidence are sent to the Road Conquest account service to verify visits to the app's exploration cells and award leaderboard credit. Ordinary local driving history is not uploaded for leaderboard scoring.</p>
<h2>Advertising and rewards</h2>
<p>Road Conquest uses Google AdMob for banner ads on the Garage, Achievements, Leaderboards, and Settings screens, and optional rewarded ads in the Garage. The Google Mobile Ads SDK can collect and share IP addresses (which may estimate general location), device or account identifiers such as the advertising ID and app-set ID, app and ad interactions, and diagnostics for advertising, analytics, and fraud prevention. Google encrypts these transmissions in transit. Road Conquest does not pass recorded routes, GPS coordinates, or Road Conquest account credentials to its ad requests.</p>
<p>Where required, Google's User Messaging Platform presents privacy choices before ads can load. You can reopen applicable choices using Ad privacy choices on these screens. The driving map has no ads. Rewarded ads are voluntary: the Garage shows the point reward before playback, and a completed ad earns local points only after the ad SDK confirms the reward. See <a href="https://policies.google.com/privacy">Google's Privacy Policy</a> for Google's data practices.</p>
<h2>Retention and deletion</h2>
<p>Competitive accounts count only server-verified exploration cells. Private visited-cell identifiers and weekly cell counts are stored until account deletion; these identifiers describe coarse geographic areas. Older totals and imported or local progress cannot enter hardened rankings. For anti-cheating continuity, the account service keeps one latest accepted GPS fix encrypted with an account-bound server key, plus its timestamp and keyed fingerprint. It is replaced by the next accepted batch and removed with account deletion. Full GPS traces are processed transiently for verification rather than stored in the ranking database.</p>
<p>Importing a backup validates and replaces local progress without uploading that history for verified scores. Before replacement, the app keeps one private recovery ZIP on this phone. Delete all data and uninstalling remove this recovery copy. Google Play VIP ownership and account credentials are not imported. Weekly local challenges award local points; accepted friends can compare newly verified exploration cells each week. Friendships require consent, may be removed by either person, and are deleted with the account. Hiding a profile hides it from friend rankings as well as public leaderboards. Weekly verified totals remain with the account until account deletion.</p>
<p>Local app data remains on your device until you use Delete all data, uninstall the app, or otherwise clear app storage. Delete all data requires typing the exact in-app confirmation phrase and removes local Road Conquest data while keeping the cloud account and leaderboard scores; it does not require account sign-in or network access. Exported files are controlled by you and must be deleted separately. Delete account requires the current account password and removes the cloud account, sessions, leaderboard scores, visited-cell and legacy verified-road records, live runs, and verification receipts while leaving saved device history in place. Some short-lived anti-abuse and verification records expire automatically.</p>
<h2>Security and choices</h2>
<p>Network account traffic uses HTTPS. You can use Road Conquest without an account, hide yourself from leaderboards, leave verified-drive sharing off, use manual tracking, export your data, delete only your cloud account, or delete all local Road Conquest data while keeping your account.</p>
<h2>Privacy inquiries</h2>
<p>For privacy questions, use the Road Conquest project's <a href="https://github.com/AntnyGamer/RoadConquest-Beta/issues">GitHub Issues page</a>. Do not post passwords, precise location history, session tokens, or other sensitive information in a public issue.</p>
<p><a href="/delete-account">Delete a Road Conquest account</a></p></body></html>`;

const DELETE_ACCOUNT_HTML = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Delete Road Conquest Account</title>
<style>body{font:16px system-ui,sans-serif;max-width:640px;margin:40px auto;padding:0 20px;line-height:1.5}label,input,button{display:block;width:100%;box-sizing:border-box}input,button{font:inherit;padding:10px;margin:6px 0 16px}button{cursor:pointer}#status{min-height:1.5em}</style></head>
<body><h1>Delete Road Conquest Account</h1>
<p>This permanently deletes your Road Conquest cloud account, active sessions, leaderboard scores, visited-cell and legacy verified-road records, live runs, and verification receipts. Data stored only on a phone or in exported files must be deleted separately.</p>
<form id="deleteForm"><label>Username<input id="username" autocomplete="username" required minlength="3" maxlength="24"></label>
<label>Password<input id="password" type="password" autocomplete="current-password" required minlength="8" maxlength="128"></label>
<button type="submit">Delete account permanently</button></form>
<p id="status" role="status" aria-live="polite"></p><p><a href="/privacy">Privacy policy</a></p>
<script>
const form=document.getElementById("deleteForm"),status=document.getElementById("status");
form.addEventListener("submit",async e=>{e.preventDefault();status.textContent="Deleting…";let token=null,deleted=false;
try{const username=document.getElementById("username").value,password=document.getElementById("password").value;
let r=await fetch("/v1/login",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({username,password})});
let b=await r.json();if(!r.ok)throw new Error(b.error||"Sign-in failed.");token=b.token;
r=await fetch("/v1/account",{method:"DELETE",headers:{"Content-Type":"application/json","Authorization":"Bearer "+token},body:JSON.stringify({password})});
b=await r.json();if(!r.ok)throw new Error(b.error||"Deletion failed.");deleted=true;token=null;form.reset();status.textContent="Your Road Conquest account has been deleted.";
}catch(err){status.textContent=err.message||"Deletion failed.";}finally{if(token&&!deleted)fetch("/v1/logout",{method:"POST",headers:{"Content-Type":"application/json","Authorization":"Bearer "+token},body:"{}"}).catch(()=>{});}});
</script></body></html>`;

if (!DATABASE_URL) throw new Error("DATABASE_URL is required");
if (PEPPER.length < 32) throw new Error("PASSWORD_PEPPER must be at least 32 characters");
if (process.env.NODE_ENV === "production") {
  if (!REQUIRE_HTTPS) throw new Error("REQUIRE_HTTPS=1 is required in production");
  if (!TRUST_PROXY) throw new Error("TRUST_PROXY=1 is required behind the production TLS proxy");
}

const pool = new Pool(databaseConfig(DATABASE_URL, process.env.DATABASE_SSL === "1"));
// Serverless database computes can sleep. An idle connection error must not kill
// the function; pg removes that client and creates a connection for the next request.
pool.on("error", () => console.error("Idle database connection closed."));

const prepareDatabase = createRequestMaintenance(pool, { initialize: async () => {
  if (process.env.APPLY_SCHEMA_ON_STARTUP !== "0") {
    const schemaPath = fileURLToPath(new URL("../schema.sql", import.meta.url));
    await pool.query(await readFile(schemaPath, "utf8"));
  }
} });
const competition = await createCompetition(pool, competitionConfig(process.env));
const notifications = createNotifications(pool, { pepper: PEPPER, sender: createFcmSender(process.env.FCM_SERVICE_ACCOUNT_JSON) });
const friends = createFriends(pool, async target => {
  if (notifications.configured && await rateLimit("push-delivery", String(target), 3, 5 * 60 * 1000)) await notifications.friendRequest(target);
});

// Invalid usernames still take one full scrypt verification, just like bad passwords
// for real users. Random dummy hashes cannot accidentally authenticate and do not
// require an expensive additional scrypt calculation on every cold start.
const dummy = { salt: randomBytes(16), hash: randomBytes(64) };

function clientIp(req) {
  if (TRUST_PROXY) {
    const forwarded = req.headers["x-forwarded-for"];
    if (typeof forwarded === "string" && forwarded.trim()) return forwarded.split(",")[0].trim();
  }
  return req.socket.remoteAddress || "unknown";
}

function opaqueRateKey(bucket, key) {
  if (PEPPER) {
    return createHmac("sha256", PEPPER)
      .update("rate-limit\0", "utf8")
      .update(bucket, "utf8")
      .update("\0", "utf8")
      .update(key, "utf8")
      .digest();
  }
  return createHash("sha256").update(bucket, "utf8").update("\0").update(key, "utf8").digest();
}

async function rateLimit(bucket, key, limit, windowMs) {
  await prepareDatabase();
  const result = await pool.query(
    `INSERT INTO auth_rate_limits(bucket, key_hash, window_started_at, attempts)
     VALUES ($1, $2, CURRENT_TIMESTAMP, 1)
     ON CONFLICT (bucket, key_hash) DO UPDATE SET
       window_started_at = CASE
         WHEN auth_rate_limits.window_started_at <= CURRENT_TIMESTAMP - ($3::bigint * INTERVAL '1 millisecond')
         THEN CURRENT_TIMESTAMP ELSE auth_rate_limits.window_started_at END,
       attempts = CASE
         WHEN auth_rate_limits.window_started_at <= CURRENT_TIMESTAMP - ($3::bigint * INTERVAL '1 millisecond')
         THEN 1 ELSE LEAST(auth_rate_limits.attempts + 1, $4 + 1) END
     -- Once the count is already at limit+1, another blocked attempt should
     -- not rewrite the identical row. Expired windows must still reset.
     WHERE auth_rate_limits.window_started_at <= CURRENT_TIMESTAMP - ($3::bigint * INTERVAL '1 millisecond')
        OR auth_rate_limits.attempts <= $4
     RETURNING attempts <= $4 AS allowed`,
    [bucket, opaqueRateKey(bucket, key), windowMs, limit]
  );
  // A missing RETURNING row means an already-saturated window: still denied.
  return result.rows[0]?.allowed === true;
}

function sendHtml(res, status, body) {
  const headers = {
    "Content-Type": "text/html; charset=utf-8",
    "Content-Length": Buffer.byteLength(body),
    "Cache-Control": "no-store",
    "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "no-referrer"
  };
  if (REQUIRE_HTTPS) headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains";
  res.writeHead(status, headers);
  res.end(body);
}

function send(res, status, body, head = false) {
  const data = JSON.stringify(body);
  const headers = {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(data),
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer"
  };
  if (REQUIRE_HTTPS) {
    headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains";
  }
  res.writeHead(status, headers);
  res.end(head ? undefined : data);
}

async function readJson(req) {
  const contentType = req.headers["content-type"];
  if (typeof contentType !== "string" || !contentType.toLowerCase().startsWith("application/json")) {
    throw Object.assign(new Error("application/json required"), { status: 415 });
  }

  let size = 0;
  const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > JSON_LIMIT) throw Object.assign(new Error("request too large"), { status: 413 });
    chunks.push(chunk);
  }
  if (!chunks.length) return {};
  try {
    const body = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    if (!body || Array.isArray(body) || typeof body !== "object") throw new Error("JSON object required");
    return body;
  } catch {
    throw Object.assign(new Error("invalid JSON"), { status: 400 });
  }
}

function bearerToken(req) {
  const auth = req.headers.authorization;
  if (typeof auth !== "string" || !auth.startsWith("Bearer ")) return null;
  const token = auth.slice(7);
  return TOKEN_RE.test(token) ? token : null;
}

async function authenticatedUser(req) {
  const token = bearerToken(req);
  if (!token) return null;
  await prepareDatabase();
  const result = await pool.query(
    `SELECT u.id, u.username_display, u.leaderboard_visible, s.token_hash AS session_hash
       FROM sessions s
       JOIN users u ON u.id = s.user_id
      WHERE s.token_hash = $1 AND s.expires_at > CURRENT_TIMESTAMP`,
    [hashSessionToken(token)]
  );
  return result.rows[0] || null;
}

async function createSession(userId, db = pool) {
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const { token, tokenHash } = createSessionToken();
    const result = await db.query(
      `INSERT INTO sessions(token_hash, user_id, expires_at)
       VALUES ($1, $2, CURRENT_TIMESTAMP + ($3 * INTERVAL '1 day'))
       ON CONFLICT (token_hash) DO NOTHING
       RETURNING 1`,
      [tokenHash, userId, SESSION_DAYS]
    );
    if (result.rowCount === 1) return token;
  }
  throw new Error("Could not allocate a unique session token");
}

async function handle(req, res) {
  const url = new URL(req.url || "/", "http://localhost");
  if (["GET", "HEAD"].includes(req.method) && url.pathname === "/health/live") {
    return send(res, 200, { ok: true }, req.method === "HEAD"); // No database query.
  }

  if (req.method === "GET" && url.pathname === "/health") {
    await prepareDatabase();
    await pool.query("SELECT 1");
    return send(res, 200, { ok: true });
  }

  if (REQUIRE_HTTPS && req.headers["x-forwarded-proto"] !== "https") {
    return send(res, 400, { error: "HTTPS required" });
  }

  if (req.method === "GET" && url.pathname === "/privacy") return sendHtml(res, 200, PRIVACY_HTML);
  if (req.method === "GET" && url.pathname === "/delete-account") return sendHtml(res, 200, DELETE_ACCOUNT_HTML);
  if (req.method === "GET" && url.pathname === "/v1/competition") {
    return send(res, 200, competition?.status() || { available: false });
  }

  const ip = clientIp(req);
  if (url.pathname === "/v1/notifications/device") {
    if (!await rateLimit("push-ip", ip, 60, 60 * 1000)) return send(res, 429, { error: "Try again later." });
    const user = await authenticatedUser(req);
    if (!user) return send(res, 401, { error: "Authentication required." });
    if (!await rateLimit("push-user", String(user.id), 20, 60 * 1000)) return send(res, 429, { error: "Try again later." });
    if (req.method === "POST") return send(res, 200, await notifications.register(user, await readJson(req)));
    if (req.method === "DELETE") return send(res, 200, await notifications.remove(user));
    return send(res, 405, { error: "Method not allowed." });
  }
  if (url.pathname === "/v1/friends" || url.pathname === "/v1/friends/weekly") {
    if (!await rateLimit("friends-ip", ip, 120, 60 * 1000)) return send(res, 429, { error: "Try again later." });
    const user = await authenticatedUser(req);
    if (!user) return send(res, 401, { error: "Authentication required." });
    if (req.method === "GET" && url.pathname === "/v1/friends/weekly") {
      const metric = url.searchParams.get("metric") || "cells";
      if (metric !== "cells") return send(res, 400, { error: "Choose cells." });
      return send(res, 200, competition ? await competition.friendWeek(user, metric) : { available: false, entries: [] });
    }
    if (req.method === "GET" && url.pathname === "/v1/friends") {
      const listing = await friends.list(user);
      // One authenticated HTTP request, rate-limit reservation and session
      // lookup instead of two when the Challenges screen displays rankings.
      // Older clients still get the unchanged {friends} response.
      if (url.searchParams.get("include") === "weekly") {
        try {
          listing.weekly = competition ? await competition.friendWeek(user) : { available: false, entries: [] };
        } catch {
          // Do not hide friend management if the independent weekly query fails.
          // Omission allows the client to use its existing weekly-only fallback.
        }
      }
      return send(res, 200, listing);
    }
    if (req.method === "POST" && url.pathname === "/v1/friends") {
      if (!await rateLimit("friends-write", String(user.id), 30, 60 * 1000)) return send(res, 429, { error: "Try again later." });
      const body = await readJson(req);
      if (Object.keys(body).sort().join(",") !== "action,username") return send(res, 400, { error: "Username and action required." });
      return send(res, 200, await friends.change(user, body.username, body.action));
    }
    return send(res, 405, { error: "Method not allowed." });
  }
  if (req.method === "GET" && url.pathname === "/v1/leaderboard") {
    if (!await rateLimit("competition-ip", ip, 180, 60 * 1000)) return send(res, 429, { error: "Try again later." });
    const metric = url.searchParams.get("metric") || "cells";
    if (metric !== "cells") return send(res, 400, { error: "Choose cells." });
    // The public page can load before verification is provisioned. No scores are invented,
    // and every drive-writing endpoint below remains gated on strict competition setup.
    const ranking = competition
      ? { ...await competition.leaderboard(metric), verification_available: true }
      : { metric, dataset: null, entries: [], verification_available: false };
    if (competition && url.searchParams.get("mine") === "1") {
      // An optional authenticated total avoids another HTTP request and
      // rate-limit DB write. Public rankings remain accessible anonymously.
      const user = await authenticatedUser(req);
      if (user) {
        try { ranking.mine = await competition.totals(user); }
        catch { /* Keep the public rankings if the personal count is unavailable. */ }
      }
    }
    return send(res, 200, ranking);
  }
  if (url.pathname.startsWith("/v1/verified/")) {
    if (!competition) return send(res, 404, { error: "Verified leaderboards are unavailable." });
    if (!await rateLimit("competition-ip", ip, 180, 60 * 1000)) return send(res, 429, { error: "Try again later." });
    const user = await authenticatedUser(req);
    if (!user) return send(res, 401, { error: "Authentication required." });
    if (req.method === "GET" && url.pathname === "/v1/verified/me") {
      return send(res, 200, await competition.totals(user));
    }
    if (req.method === "POST" && url.pathname === "/v1/verified/start") {
      if (!await rateLimit("drive-start", String(user.id), 6, 60 * 1000)) return send(res, 429, { error: "Try again later." });
      return send(res, 201, await competition.begin(user));
    }
    if (req.method === "POST" && url.pathname === "/v1/verified/batch") {
      if (!await rateLimit("drive-batch", String(user.id), 12, 60 * 1000)) return send(res, 429, { error: "Try again later." });
      const body = await readJson(req);
      if (Object.keys(body).sort().join(",") !== "evidence,integrity_token") return send(res, 400, { error: "Evidence and integrity token required." });
      return send(res, 200, await competition.submit(user, body.evidence, body.integrity_token));
    }
  }
  if (req.method === "POST" && url.pathname === "/v1/signup") {
    const body = await readJson(req);
    const username = normalizeUsername(body.username);
    const password = body.password;

    if (!username || !validatePassword(password)) {
      return send(res, 400, {
        error: "Username must be 3-24 letters, numbers, or underscores; password must be 8-128 characters."
      });
    }
    if (!await rateLimit("signup-ip", ip, 8, 60 * 60 * 1000)) {
      return send(res, 429, { error: "Too many attempts. Try again later." });
    }

    const { salt, hash } = await hashPassword(password, PEPPER);
    const client = await pool.connect();
    try {
      await client.query("BEGIN");
      const result = await client.query(
        `INSERT INTO users(username_display, username_key, password_salt, password_hash)
         VALUES ($1, $2, $3, $4)
         RETURNING id, username_display, leaderboard_visible`,
        [username.display, username.key, salt, hash]
      );
      const user = result.rows[0];
      const token = await createSession(user.id, client);
      await client.query("COMMIT");
      return send(res, 201, {
        username: user.username_display,
        leaderboard_visible: user.leaderboard_visible,
        token
      });
    } catch (error) {
      await client.query("ROLLBACK").catch(() => {});
      if (error?.code === "23505") {
        return send(res, 409, { error: "Username unavailable." });
      }
      throw error;
    } finally {
      client.release();
    }
  }

  if (req.method === "POST" && url.pathname === "/v1/login") {
    const body = await readJson(req);
    const username = normalizeUsername(body.username);
    const password = typeof body.password === "string" && Array.from(body.password).length <= 128 ? body.password : "";
    const key = username?.key || "invalid";

    if (!await rateLimit("login-ip", ip, 20, 15 * 60 * 1000) ||
        !await rateLimit("login-user", key, 8, 15 * 60 * 1000)) {
      return send(res, 429, { error: "Too many attempts. Try again later." });
    }

    const result = username
      ? await pool.query(
          "SELECT id, username_display, password_salt, password_hash, leaderboard_visible FROM users WHERE username_key = $1",
          [username.key]
        )
      : { rows: [] };
    const user = result.rows[0];
    const valid = user
      ? await verifyPassword(password, user.password_salt, user.password_hash, PEPPER)
      : await verifyPassword(password, dummy.salt, dummy.hash, PEPPER);

    if (!valid) return send(res, 401, { error: "Invalid username or password." });
    const token = await createSession(user.id);
    return send(res, 200, {
      username: user.username_display,
      leaderboard_visible: user.leaderboard_visible,
      token
    });
  }

  if (req.method === "GET" && url.pathname === "/v1/me") {
    const user = await authenticatedUser(req);
    if (!user) return send(res, 401, { error: "Authentication required." });
    return send(res, 200, {
      username: user.username_display,
      leaderboard_visible: user.leaderboard_visible
    });
  }

  const renameAccount = req.method === "PUT" && url.pathname === "/v1/username";
  const deleteAccount = req.method === "DELETE" && url.pathname === "/v1/account";
  const reauthenticate = req.method === "POST" && url.pathname === "/v1/reauth";
  if (renameAccount || deleteAccount || reauthenticate) {
    const user = await authenticatedUser(req);
    if (!user) return send(res, 401, { error: "Authentication required." });
    const body = await readJson(req);
    const username = renameAccount ? normalizeUsername(body.username) : null;
    const fields = renameAccount ? "password,username" : "password";
    if (Object.keys(body).sort().join(",") !== fields ||
        !validatePassword(body.password) || (renameAccount && !username)) {
      return send(res, 400, { error: renameAccount
        ? "Enter your current password and a valid username." : "Enter your current password." });
    }
    const accountRateBucket = reauthenticate ? "reauth" : "account-change";
    if (!await rateLimit(accountRateBucket, String(user.id), 5, 15 * 60 * 1000)) {
      return send(res, 429, { error: "Too many attempts. Try again later." });
    }
    const client = await pool.connect();
    try {
      await client.query("BEGIN");
      const result = await client.query(
        "SELECT password_salt, password_hash, username_key FROM users WHERE id = $1 FOR UPDATE",
        [user.id]
      );
      const saved = result.rows[0];
      if (!saved) {
        await client.query("ROLLBACK");
        return send(res, 401, { error: "Authentication required." });
      }
      if (!await verifyPassword(body.password, saved.password_salt, saved.password_hash, PEPPER)) {
        await client.query("ROLLBACK");
        return send(res, 403, { error: "Current password is incorrect." });
      }
      if (reauthenticate) {
        await client.query("COMMIT");
        return send(res, 200, { ok: true });
      }
      if (renameAccount) {
        const renamed = await client.query(
          "UPDATE users SET username_display = $1, username_key = $2 WHERE id = $3 RETURNING username_display, leaderboard_visible",
          [username.display, username.key, user.id]
        );
        await client.query("COMMIT");
        return send(res, 200, {
          username: renamed.rows[0].username_display,
          leaderboard_visible: renamed.rows[0].leaderboard_visible
        });
      }
      // Foreign keys remove every session, score, unlocked way, live run and receipt.
      await client.query("DELETE FROM users WHERE id = $1", [user.id]);
      // Delete exactly the same five account-specific throttling keys in one
      // round trip. Keep paired buckets and hashes (rather than a cross-product).
      const removalKeys = [
        ["account-change", String(user.id)], ["reauth", String(user.id)],
        ["drive-start", String(user.id)], ["drive-batch", String(user.id)],
        ["login-user", saved.username_key]
      ];
      await client.query(
        `DELETE FROM auth_rate_limits WHERE (bucket,key_hash) IN (
          SELECT cleanup.bucket,cleanup.key_hash
          FROM unnest($1::text[], $2::bytea[]) AS cleanup(bucket,key_hash)
        )`,
        [removalKeys.map(entry => entry[0]), removalKeys.map(([bucket, key]) => opaqueRateKey(bucket, key))]
      );
      await client.query("COMMIT");
      return send(res, 200, { ok: true });
    } catch (error) {
      await client.query("ROLLBACK").catch(() => {});
      if (renameAccount && error?.code === "23505") {
        return send(res, 409, { error: "Username unavailable." });
      }
      throw error;
    } finally {
      client.release();
    }
  }

  if (req.method === "PUT" && url.pathname === "/v1/privacy") {
    const user = await authenticatedUser(req);
    if (!user) return send(res, 401, { error: "Authentication required." });
    const body = await readJson(req);
    if (typeof body.leaderboard_visible !== "boolean") {
      return send(res, 400, { error: "leaderboard_visible must be boolean." });
    }
    await pool.query(
      "UPDATE users SET leaderboard_visible = $1 WHERE id = $2 AND leaderboard_visible IS DISTINCT FROM $1",
      [body.leaderboard_visible, user.id]
    );
    return send(res, 200, { leaderboard_visible: body.leaderboard_visible });
  }

  if (req.method === "POST" && url.pathname === "/v1/logout") {
    const token = bearerToken(req);
    if (token) {
      await prepareDatabase();
      await pool.query("DELETE FROM sessions WHERE token_hash = $1", [hashSessionToken(token)]);
    }
    return send(res, 200, { ok: true });
  }

  return send(res, 404, { error: "Not found." });
}

export function serve(req, res) {
  handle(req, res).catch(error => {
    if (!error?.status || error.status >= 500) {
      console.error("request failed", error instanceof Error ? error.message : "unknown error");
    }
    if (!res.headersSent) send(res, error?.status || 500, { error: "Request failed." });
    else res.destroy();
  });
}


export { pool };
