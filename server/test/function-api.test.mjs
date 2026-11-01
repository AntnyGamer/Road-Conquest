import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import pg from "pg";
import { limitedApiDatabase } from "./helpers/limited-api-role.mjs";

test("serverless accounts enforce authentication, privacy, limits and TLS with real PostgreSQL", {
  skip: !process.env.DATABASE_URL
}, async () => {
  const admin = new pg.Pool({ connectionString: process.env.DATABASE_URL });
  let runtimePool;
  try {
    await admin.query(await readFile(new URL("../schema.sql", import.meta.url), "utf8"));
    await admin.query("TRUNCATE auth_rate_limits, sessions, users RESTART IDENTITY CASCADE");
    process.env.DATABASE_URL = await limitedApiDatabase(admin, process.env.DATABASE_URL);
    process.env.NODE_ENV = "production";
    process.env.REQUIRE_HTTPS = "1";
    process.env.TRUST_PROXY = "1";
    process.env.APPLY_SCHEMA_ON_STARTUP = "0";
    process.env.BACKUP_MASTER_KEYS_JSON = JSON.stringify({
      v1: Buffer.alloc(32, 23).toString("base64url"),
      v2: Buffer.alloc(32, 42).toString("base64url")
    });
    process.env.BACKUP_CURRENT_KEY_ID = "v2";
    const { default: api, pool } = await import("../dist/index.mjs");
    runtimePool = pool;
    async function call(path, method = "GET", body, token, extraHeaders = {}) {
      const headers = { "Content-Type": "application/json", ...extraHeaders };
      if (token) headers.Authorization = `Bearer ${token}`;
      const response = await api.fetch(new Request("https://accounts.example" + path, {
        method, headers, body: body === undefined ? undefined : JSON.stringify(body)
      }));
      assert.equal(response.headers.get("Cache-Control"), "no-store");
      assert.match(response.headers.get("Strict-Transport-Security"), /max-age=31536000/);
      return { status: response.status, body: await response.json() };
    }
    const health = await call("/health");
    assert.equal(health.status, 200);
    assert.deepEqual(health.body, (await call("/health/live")).body);
    assert.equal(health.body.api_version, 1);
    const privacyPage = await api.fetch(new Request("https://accounts.example/privacy"));
    assert.equal(privacyPage.status, 200);
    assert.match(privacyPage.headers.get("Content-Type"), /^text\/html/u);
    assert.match(await privacyPage.text(), /Road Conquest Privacy Policy/u);
    const deletionPage = await api.fetch(new Request("https://accounts.example/delete-account"));
    assert.equal(deletionPage.status, 200);
    assert.match(await deletionPage.text(), /Delete Road Conquest Account/u);
    assert.equal((await call("/v1/me")).status, 401);
    assert.equal((await call("/v1/backups/account-key")).status, 401);
    assert.deepEqual((await call("/v1/backups")).body, { available: false, keys: [] });
    assert.equal((await call("/v1/backups/sign", "POST", { hash: "forged", integrity_token: "fake" })).status, 503);
    const credentials = { username: "FunctionDriver", password: "correct horse battery staple" };
    const signup = await call("/v1/signup", "POST", credentials);
    assert.equal(signup.status, 201);
    assert.equal(signup.body.leaderboard_visible, true);
    assert.match(signup.body.token, /^[A-Za-z0-9_-]{43}$/);
    assert.equal((await call("/v1/signup", "POST", { ...credentials, username: "functiondriver" })).status, 409);
    assert.equal((await call("/v1/login", "POST", { ...credentials, password: "an incorrect password" })).status, 401);
    const login = await call("/v1/login", "POST", { ...credentials, username: "FUNCTIONDRIVER" });
    assert.equal(login.status, 200);
    assert.equal((await call("/v1/me", "GET", undefined, login.body.token)).body.username, credentials.username);
    const originalKey = await call("/v1/backups/account-key", "GET", undefined, signup.body.token);
    const loginKey = await call("/v1/backups/account-key", "GET", undefined, login.body.token);
    assert.equal(originalKey.status, 200);
    assert.match(originalKey.body.key, /^[A-Za-z0-9_-]{43}$/u);
    assert.equal(originalKey.body.key_id, "v2");
    const earlierKey = await call("/v1/backups/account-key?key_id=v1", "GET", undefined, login.body.token);
    assert.equal(earlierKey.status, 200);
    assert.equal(earlierKey.body.key_id, "v1");
    assert.notEqual(earlierKey.body.key, originalKey.body.key);
    assert.equal((await call("/v1/backups/account-key?key_id=invalid", "GET", undefined, login.body.token)).status, 410);
    assert.equal((await call("/v1/backups/account-key?key_id=%21", "GET", undefined, login.body.token)).status, 400);
    assert.deepEqual(loginKey, originalKey, "Different sessions for one account must restore the same file");
    assert.equal((await call("/v1/backups/account-key", "GET", undefined, "A".repeat(43))).status, 401);
    assert.equal((await call("/v1/privacy", "PUT", { leaderboard_visible: false }, login.body.token)).body.leaderboard_visible, false);
    const privacyRow = await admin.query("SELECT xmin::text AS version FROM users WHERE username_key='functiondriver'");
    assert.equal((await call("/v1/privacy", "PUT", { leaderboard_visible: false }, login.body.token)).body.leaderboard_visible, false);
    assert.deepEqual((await admin.query("SELECT xmin::text AS version FROM users WHERE username_key='functiondriver'")).rows, privacyRow.rows);
    assert.equal((await call("/v1/me", "GET", undefined, login.body.token)).body.leaderboard_visible, false);
    assert.equal((await call("/v1/privacy", "PUT", { leaderboard_visible: true }, login.body.token)).body.leaderboard_visible, true);
    assert.deepEqual((await call("/v1/competition")).body, { available: false });
    const rankingWithMine = await call("/v1/leaderboard?metric=cells&mine=1", "GET", undefined, login.body.token);
    assert.equal(rankingWithMine.status, 200);
    assert.deepEqual(rankingWithMine.body, { metric: "cells", dataset: null, entries: [], verification_available: false });
    const ranking = await call("/v1/leaderboard?metric=cells");
    assert.equal(ranking.status, 200);
    const unsignedMine = await call("/v1/leaderboard?metric=cells&mine=1");
    assert.deepEqual(unsignedMine.body, ranking.body, 'Unsigned mine opt-in stays a public leaderboard');
    assert.deepEqual(ranking.body, { metric: "cells", dataset: null, entries: [], verification_available: false });
    assert.equal((await call("/v1/leaderboard?metric=invalid")).status, 400);
    assert.equal((await call("/v1/verified/batch", "POST", { miles: 999999 }, login.body.token)).status, 404);
    assert.equal((await call("/v1/friends")).status, 401);
    assert.equal((await call("/health/live")).status, 200);
    const headHealth = await api.fetch(new Request("https://accounts.example/health/live", { method: "HEAD" }));
    assert.equal(headHealth.status, 200);
    assert.equal(await headHealth.text(), "");
    assert.equal((await call("/v1/notifications/device", "POST", {})).status, 401);
    assert.equal((await call("/v1/notifications/device", "POST", {}, login.body.token)).status, 400);
    assert.equal((await call("/v1/notifications/device", "DELETE", undefined, login.body.token)).status, 200);
    // Personal profile lookup now reserves an IP budget in the same SQL call.
    const meQuery = runtimePool.query.bind(runtimePool);
    let profileQueries = 0;
    runtimePool.query = (...args) => { profileQueries++; return meQuery(...args); };
    const profileCheck = await call("/v1/me", "GET", undefined, login.body.token);
    runtimePool.query = meQuery;
    assert.equal(profileCheck.status, 200);
    assert.equal(profileQueries, 1, 'Profile lookup and budget must share one database round trip');
    // Warm authenticated reads now reserve the IP budget and check the session
    // together, then query the friend list. No extra session SELECT is needed.
    const realQuery = runtimePool.query.bind(runtimePool);
    let friendQueries = 0;
    runtimePool.query = (...args) => { friendQueries++; return realQuery(...args); };
    assert.deepEqual((await call("/v1/friends", "GET", undefined, login.body.token)).body, { friends: [] });
    runtimePool.query = realQuery;
    assert.equal(friendQueries, 2, 'Friends list needs only combined IP/session check and list query');
    assert.deepEqual((await call("/v1/friends?include=weekly", "GET", undefined, login.body.token)).body,
      { friends: [], weekly: { available: false, entries: [] } });
    const friend = await call("/v1/signup", "POST", { username: "FriendDriver", password: credentials.password });
    assert.equal(friend.status, 201);
    const friendKey = await call("/v1/backups/account-key", "GET", undefined, friend.body.token);
    assert.equal(friendKey.status, 200);
    assert.notEqual(friendKey.body.key, originalKey.body.key, "Other accounts must never share encryption keys");
    assert.equal((await call("/v1/friends", "POST", { username: "FriendDriver", action: "request" }, login.body.token)).status, 200);
    assert.equal((await call("/v1/friends", "POST", { username: "FriendDriver", action: "accept" }, login.body.token)).status, 409);
    assert.equal((await call("/v1/friends", "POST", { username: credentials.username, action: "accept" }, friend.body.token)).status, 200);
    assert.deepEqual((await call("/v1/friends", "GET", undefined, login.body.token)).body.friends, [{ username: "FriendDriver", status: "accepted" }]);
    assert.deepEqual((await call("/v1/friends/weekly", "GET", undefined, login.body.token)).body, { available: false, entries: [] });
    assert.equal((await call("/v1/friends/weekly?metric=invalid", "GET", undefined, login.body.token)).status, 400);
    assert.equal((await call("/v1/friends", "POST", { username: "FriendDriver", action: "remove", user_id: 999 }, login.body.token)).status, 400);
    assert.equal((await call("/v1/privacy", "PUT", { oversized: "x".repeat(33 * 1024) }, login.body.token)).status, 413);
    const renamed = await call("/v1/username", "PUT", { username: "FunctionRenamed", password: credentials.password }, login.body.token);
    assert.equal(renamed.status, 200);
    assert.equal(renamed.body.username, "FunctionRenamed");
    assert.equal((await call("/v1/me", "GET", undefined, signup.body.token)).body.username, "FunctionRenamed");
    const version=(await admin.query("SELECT xmin::text AS version FROM users WHERE username_key='functionrenamed'")).rows[0].version;
    const unchanged=await call('/v1/username','PUT',{username:'FunctionRenamed',password:credentials.password},login.body.token);
    assert.equal(unchanged.status,200);assert.deepEqual(unchanged.body,renamed.body);
    assert.equal((await admin.query("SELECT xmin::text AS version FROM users WHERE username_key='functionrenamed'")).rows[0].version,version);
    const caseChange=await call('/v1/username','PUT',{username:'FUNCTIONRENAMED',password:credentials.password},login.body.token);
    assert.equal(caseChange.status,200);assert.equal(caseChange.body.username,'FUNCTIONRENAMED');
    assert.notEqual((await admin.query("SELECT xmin::text AS version FROM users WHERE username_key='functionrenamed'")).rows[0].version,version);
    assert.equal((await call("/v1/reauth", "POST", { password: "wrong password" }, login.body.token)).status, 403);
    assert.equal((await call("/v1/reauth", "POST", { password: credentials.password }, login.body.token)).status, 200);
    assert.equal((await call("/v1/me", "GET", undefined, login.body.token)).status, 200);
    const guarded = await admin.query("SELECT bucket,attempts FROM auth_rate_limits WHERE bucket IN ('account-ip','privacy-ip') ORDER BY bucket");
    assert.deepEqual(guarded.rows.map(r => r.bucket), ['account-ip', 'privacy-ip'],
      'Password-protected and privacy routes reserve a budget before attempting session validation');
    assert.ok(guarded.rows.every(r => Number(r.attempts) >= 1));
    assert.equal((await call("/v1/account", "DELETE", { password: "wrong password" }, login.body.token)).status, 403);
    assert.equal((await call("/v1/account", "DELETE", { password: credentials.password }, login.body.token)).status, 200);
    assert.deepEqual((await call("/v1/friends", "GET", undefined, friend.body.token)).body, { friends: [] });
    assert.equal((await call("/v1/me", "GET", undefined, signup.body.token)).status, 401);
    assert.equal((await call("/v1/backups/account-key", "GET", undefined, signup.body.token)).status, 401,
      "Account deletion must revoke access to its recovery key");
    let logoutQueries = 0;
    const logoutQuery = runtimePool.query.bind(runtimePool);
    runtimePool.query = (...args) => { logoutQueries++; return logoutQuery(...args); };
    assert.equal((await call("/v1/logout", "POST", {}, login.body.token)).status, 200);
    runtimePool.query = logoutQuery;
    assert.equal(logoutQueries, 1, 'Logout revocation and budget must share one database round trip');
    assert.equal((await call("/v1/me", "GET", undefined, login.body.token)).status, 401);
    let last;
    for (let i = 0; i < 7; i++) {
      last = await call("/v1/signup", "POST", credentials, undefined, { "X-Forwarded-For": `198.51.100.${i}` });
    }
    assert.equal(last.status, 429, "Changing a caller-supplied IP cannot evade signup limits");
    const saturatedBefore = await admin.query(
      "SELECT attempts, xmin::text AS row_version FROM auth_rate_limits WHERE bucket='signup-ip'"
    );
    assert.equal(saturatedBefore.rowCount, 1);
    assert.equal(Number(saturatedBefore.rows[0].attempts), 9);
    assert.equal((await call("/v1/signup", "POST", credentials)).status, 429);
    const saturatedAfter = await admin.query(
      "SELECT attempts, xmin::text AS row_version FROM auth_rate_limits WHERE bucket='signup-ip'"
    );
    // A refused, already-saturated request must not rewrite the counter row.
    assert.deepEqual(saturatedAfter.rows, saturatedBefore.rows);
    const insecure = await api.fetch(new Request("http://accounts.example/v1/login", {
      method: "POST", headers: { "X-Forwarded-Proto": "https" }, body: JSON.stringify(credentials)
    }));
    assert.equal(insecure.status, 400);
  } finally {
    if (runtimePool) await runtimePool.end();
    await admin.end();
  }
});
