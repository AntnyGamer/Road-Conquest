import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import net from "node:net";
import pg from "pg";
import { hashSessionToken } from "../src/security.mjs";
import { limitedApiDatabase } from "./helpers/limited-api-role.mjs";

const { Pool } = pg;
const connectionString = process.env.DATABASE_URL;

async function freePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      const port = typeof address === "object" && address ? address.port : null;
      server.close(error => error ? reject(error) : resolve(port));
    });
  });
}

async function waitForHealth(baseUrl, child) {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    if (child.exitCode != null) throw new Error("account server exited before health check");
    try {
      const response = await fetch(baseUrl + "/health");
      if (response.ok) return;
    } catch {
    }
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw new Error("account server did not become healthy");
}

async function json(baseUrl, path, method, body, token) {
  const headers = { Accept: "application/json" };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (token) headers.Authorization = "Bearer " + token;
  const response = await fetch(baseUrl + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    redirect: "manual"
  });
  const payload = await response.json();
  return { status: response.status, payload };
}

test("signup/login/privacy/logout are server-enforced and duplicate usernames are rejected", { skip: !connectionString }, async () => {
  const pool = new Pool({ connectionString });
  const apiConnectionString = await limitedApiDatabase(pool, connectionString);

  const port = await freePort();
  const child = spawn(process.execPath, ["src/server.mjs"], {
    cwd: new URL("..", import.meta.url),
    env: {
      ...process.env,
      DATABASE_URL: apiConnectionString,
      APPLY_SCHEMA_ON_STARTUP: "0",
      PORT: String(port),
      NODE_ENV: "test",
      PASSWORD_PEPPER: "test-pepper-that-is-long-enough-for-auth-tests"
    },
    stdio: ["ignore", "pipe", "pipe"]
  });
  let stderr = "";
  child.stderr.setEncoding("utf8");
  child.stderr.on("data", chunk => { stderr += chunk; });

  const baseUrl = "http://127.0.0.1:" + port;
  try {
    await waitForHealth(baseUrl, child);
    const privacyPage = await fetch(baseUrl + "/privacy");
    assert.equal(privacyPage.status, 200);
    assert.match(privacyPage.headers.get("content-type"), /^text\/html/u);
    assert.match(await privacyPage.text(), /Road Conquest Privacy Policy/u);
    const deletionPage = await fetch(baseUrl + "/delete-account");
    assert.equal(deletionPage.status, 200);
    assert.match(deletionPage.headers.get("content-security-policy"), /default-src 'none'/u);
    assert.match(await deletionPage.text(), /Delete Road Conquest Account/u);
    await pool.query("TRUNCATE auth_rate_limits, sessions, users RESTART IDENTITY CASCADE");

    const tooShort = await json(baseUrl, "/v1/signup", "POST", {
      username: "TooShort",
      password: "1234567"
    });
    assert.equal(tooShort.status, 400);

    const password = "12345678";
    const signup = await json(baseUrl, "/v1/signup", "POST", {
      username: "DriverOne",
      password
    });
    assert.equal(signup.status, 201);
    assert.equal(signup.payload.username, "DriverOne");
    assert.equal(signup.payload.leaderboard_visible, true);
    assert.match(signup.payload.token, /^[A-Za-z0-9_-]{43}$/u);

    const duplicate = await json(baseUrl, "/v1/signup", "POST", {
      username: "driverone",
      password: "another correct horse passphrase"
    });
    assert.equal(duplicate.status, 409);
    assert.equal(duplicate.payload.error, "Username unavailable.");

    const stored = await pool.query(
      "SELECT username_key, password_salt, password_hash, leaderboard_visible FROM users WHERE username_key = 'driverone'"
    );
    assert.equal(stored.rowCount, 1);
    assert.equal(stored.rows[0].leaderboard_visible, true);
    assert.equal(stored.rows[0].password_salt.length, 16);
    assert.equal(stored.rows[0].password_hash.length, 64);
    assert.equal(stored.rows[0].password_hash.includes(Buffer.from(password, "utf8")), false);

    const session = await pool.query("SELECT 1 FROM sessions WHERE token_hash = $1", [
      hashSessionToken(signup.payload.token)
    ]);
    assert.equal(session.rowCount, 1);

    const badLogin = await json(baseUrl, "/v1/login", "POST", {
      username: "DriverOne",
      password: "this password is definitely incorrect"
    });
    assert.equal(badLogin.status, 401);
    assert.equal(badLogin.payload.error, "Invalid username or password.");
    const missingUser = await json(baseUrl, "/v1/login", "POST", {
      username: "MissingUser",
      password: "this is only a constant dummy password"
    });
    assert.equal(missingUser.status, 401);
    assert.equal(missingUser.payload.error, "Invalid username or password.");

    const login = await json(baseUrl, "/v1/login", "POST", {
      username: "DRIVERONE",
      password
    });
    assert.equal(login.status, 200);

    const meBefore = await json(baseUrl, "/v1/me", "GET", undefined, login.payload.token);
    assert.equal(meBefore.status, 200);
    assert.equal(meBefore.payload.username, "DriverOne");
    assert.equal(meBefore.payload.leaderboard_visible, true);

    const privacy = await json(baseUrl, "/v1/privacy", "PUT", {
      leaderboard_visible: true
    }, login.payload.token);
    assert.equal(privacy.status, 200);
    assert.equal(privacy.payload.leaderboard_visible, true);

    const meAfter = await json(baseUrl, "/v1/me", "GET", undefined, login.payload.token);
    assert.equal(meAfter.payload.leaderboard_visible, true);

    const bundledFriends = await json(baseUrl, "/v1/friends?include=weekly", "GET", undefined, login.payload.token);
    assert.equal(bundledFriends.status, 200);
    assert.deepEqual(bundledFriends.payload,
      { friends: [], weekly: { available: false, entries: [] } });
    assert.deepEqual((await json(baseUrl, "/v1/friends", "GET", undefined, login.payload.token)).payload,
      { friends: [] });
    const leaderboard = await json(baseUrl, "/v1/leaderboard", "GET");
    assert.equal(leaderboard.status, 200);
    assert.deepEqual(leaderboard.payload, { metric: "cells", dataset: null, entries: [], verification_available: false });
    assert.equal((await json(baseUrl, "/v1/leaderboard?metric=roads", "GET")).status, 400);
    assert.equal((await json(baseUrl, "/v1/leaderboard?metric=miles", "GET")).status, 400);
    assert.equal((await json(baseUrl, "/v1/leaderboard?metric=invalid", "GET")).status, 400);
    const competition = await json(baseUrl, "/v1/competition", "GET");
    assert.deepEqual(competition.payload, { available: false });
    const forged = await json(baseUrl, "/v1/verified/batch", "POST", { miles: 999999, roads: 999999 }, login.payload.token);
    assert.equal(forged.status, 404);
    const invalidJson = await json(baseUrl, "/v1/privacy", "PUT", null, login.payload.token);
    assert.equal(invalidJson.status, 400);

    const logout = await json(baseUrl, "/v1/logout", "POST", {}, login.payload.token);
    assert.equal(logout.status, 200);
    const afterLogout = await json(baseUrl, "/v1/me", "GET", undefined, login.payload.token);
    assert.equal(afterLogout.status, 401);

    assert.equal((await json(baseUrl, "/v1/username", "PUT", { username: "Renamed", password })).status, 401);
    assert.equal((await json(baseUrl, "/v1/account", "DELETE", { password })).status, 401);
    assert.equal((await json(baseUrl, "/v1/account", "DELETE", { password, user_id: 999999 }, signup.payload.token)).status, 400);
    const second = await json(baseUrl, "/v1/signup", "POST", { username: "OtherDriver", password });
    assert.equal(second.status, 201);
    const owner = (await pool.query("SELECT id FROM users WHERE username_key = 'driverone'")).rows[0].id;
    const dataset = "c".repeat(64);
    await pool.query("INSERT INTO road_catalogs(dataset, data_version) VALUES ($1, 'account-test') ON CONFLICT DO NOTHING", [dataset]);
    await pool.query("INSERT INTO competition_scores(user_id, dataset, distance_mm) VALUES ($1, $2, 100000)", [owner, dataset]);
    await pool.query("INSERT INTO competition_roads(user_id, dataset, way_id) VALUES ($1, $2, 55)", [owner, dataset]);
    const runId = "11111111-1111-4111-8111-111111111111";
    await pool.query("INSERT INTO competition_runs(user_id, id, nonce, dataset) VALUES ($1, $2, $3, $4)", [owner, runId, "n".repeat(43), dataset]);
    await pool.query("INSERT INTO competition_receipts(run_id, sequence, user_id, evidence_hash, response) VALUES ($1, 0, $2, $3, '{}')", [runId, owner, "h".repeat(43)]);

    await pool.query("INSERT INTO cell_scores(user_id,dataset,cells) VALUES ($1,'fog-grid-v1',1)", [owner]);
    await pool.query("INSERT INTO competition_cells VALUES ($1,'fog-grid-v1',1000,1000)", [owner]);
    await pool.query("INSERT INTO cell_runs(user_id,id,nonce,dataset) VALUES ($1,$2,$3,'fog-grid-v1')", [owner, runId, "n".repeat(43)]);
    await pool.query("INSERT INTO cell_receipts(run_id,sequence,user_id,evidence_hash,response) VALUES ($1,0,$2,$3,'{}')", [runId, owner, "h".repeat(43)]);
    await pool.query("INSERT INTO cell_weeks VALUES ($1,'fog-grid-v1',date_trunc('week',CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date,1)", [owner]);
    const originalToken = signup.payload.token;
    assert.equal((await json(baseUrl, "/v1/username", "PUT", { username: "invalid name", password }, originalToken)).status, 400);
    assert.equal((await json(baseUrl, "/v1/username", "PUT", { username: "NewDriver", password: "wrong password" }, originalToken)).status, 403);
    const renamed = await json(baseUrl, "/v1/username", "PUT", { username: "NewDriver", password }, originalToken);
    assert.equal(renamed.status, 200);
    assert.equal(renamed.payload.username, "NewDriver");
    assert.equal(renamed.payload.leaderboard_visible, true);
    assert.equal((await json(baseUrl, "/v1/me", "GET", undefined, originalToken)).payload.username, "NewDriver");
    assert.equal((await pool.query("SELECT id FROM users WHERE username_key = 'newdriver'")).rows[0].id, owner);
    assert.equal((await pool.query("SELECT distance_mm FROM competition_scores WHERE user_id = $1", [owner])).rows[0].distance_mm, "100000");
    const collision = await json(baseUrl, "/v1/username", "PUT", { username: "otherdriver", password }, originalToken);
    assert.equal(collision.status, 409);
    assert.equal((await json(baseUrl, "/v1/me", "GET", undefined, originalToken)).payload.username, "NewDriver");
    const newLogin = await json(baseUrl, "/v1/login", "POST", { username: "newdriver", password });
    assert.equal(newLogin.status, 200);
    assert.equal((await json(baseUrl, "/v1/login", "POST", { username: "DriverOne", password })).status, 401);
    assert.equal((await json(baseUrl, "/v1/reauth", "POST", { password: "wrong password" }, originalToken)).status, 403);
    assert.equal((await json(baseUrl, "/v1/reauth", "POST", { password }, originalToken)).status, 200);
    assert.equal((await json(baseUrl, "/v1/me", "GET", undefined, originalToken)).status, 200);
    assert.equal((await json(baseUrl, "/v1/account", "DELETE", { password: "wrong password" }, originalToken)).status, 403);
    assert.equal((await json(baseUrl, "/v1/me", "GET", undefined, originalToken)).status, 200);
    assert.equal((await json(baseUrl, "/v1/account", "DELETE", { password }, originalToken)).status, 200);
    for (const token of [originalToken, newLogin.payload.token]) {
      assert.equal((await json(baseUrl, "/v1/me", "GET", undefined, token)).status, 401);
    }
    for (const table of ["sessions", "competition_scores", "competition_roads", "competition_runs", "competition_receipts", "cell_scores", "competition_cells", "cell_runs", "cell_receipts", "cell_weeks"]) {
      assert.equal((await pool.query(`SELECT count(*) FROM ${table} WHERE user_id = $1`, [owner])).rows[0].count, "0");
    }
    assert.equal((await pool.query("SELECT count(*) FROM users WHERE id = $1", [owner])).rows[0].count, "0");
    assert.equal((await json(baseUrl, "/v1/me", "GET", undefined, second.payload.token)).payload.username, "OtherDriver");
    assert.equal((await json(baseUrl, "/v1/login", "POST", { username: "NewDriver", password })).status, 401);
  } finally {
    if (child.exitCode == null) {
      child.kill("SIGTERM");
      await new Promise(resolve => child.once("exit", resolve));
    }
    await pool.end();
  }

  assert.equal(stderr, "");
});
