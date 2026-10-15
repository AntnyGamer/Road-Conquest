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
    assert.equal((await call("/health")).status, 200);
    const privacyPage = await api.fetch(new Request("https://accounts.example/privacy"));
    assert.equal(privacyPage.status, 200);
    assert.match(privacyPage.headers.get("Content-Type"), /^text\/html/u);
    assert.match(await privacyPage.text(), /Road Conquest Privacy Policy/u);
    const deletionPage = await api.fetch(new Request("https://accounts.example/delete-account"));
    assert.equal(deletionPage.status, 200);
    assert.match(await deletionPage.text(), /Delete Road Conquest Account/u);
    assert.equal((await call("/v1/me")).status, 401);
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
    assert.equal((await call("/v1/privacy", "PUT", { leaderboard_visible: false }, login.body.token)).body.leaderboard_visible, false);
    assert.equal((await call("/v1/me", "GET", undefined, login.body.token)).body.leaderboard_visible, false);
    assert.equal((await call("/v1/privacy", "PUT", { leaderboard_visible: true }, login.body.token)).body.leaderboard_visible, true);
    assert.deepEqual((await call("/v1/competition")).body, { available: false });
    const ranking = await call("/v1/leaderboard?metric=cells");
    assert.equal(ranking.status, 200);
    assert.deepEqual(ranking.body, { metric: "cells", dataset: null, entries: [], verification_available: false });
    assert.equal((await call("/v1/leaderboard?metric=invalid")).status, 400);
    assert.equal((await call("/v1/verified/batch", "POST", { miles: 999999 }, login.body.token)).status, 404);
    assert.equal((await call("/v1/friends")).status, 401);
    assert.deepEqual((await call("/v1/friends", "GET", undefined, login.body.token)).body, { friends: [] });
    const friend = await call("/v1/signup", "POST", { username: "FriendDriver", password: credentials.password });
    assert.equal(friend.status, 201);
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
    assert.equal((await call("/v1/reauth", "POST", { password: "wrong password" }, login.body.token)).status, 403);
    assert.equal((await call("/v1/reauth", "POST", { password: credentials.password }, login.body.token)).status, 200);
    assert.equal((await call("/v1/me", "GET", undefined, login.body.token)).status, 200);
    assert.equal((await call("/v1/account", "DELETE", { password: "wrong password" }, login.body.token)).status, 403);
    assert.equal((await call("/v1/account", "DELETE", { password: credentials.password }, login.body.token)).status, 200);
    assert.deepEqual((await call("/v1/friends", "GET", undefined, friend.body.token)).body, { friends: [] });
    assert.equal((await call("/v1/me", "GET", undefined, signup.body.token)).status, 401);
    assert.equal((await call("/v1/logout", "POST", {}, login.body.token)).status, 200);
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
