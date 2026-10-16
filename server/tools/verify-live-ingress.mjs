import { randomBytes, createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";

// Deliberately exhaust only disposable GitHub-hosted clients' IP allowances.
// Never run the quota probes on a developer's or an app user's connection.
if (process.env.GITHUB_ACTIONS !== "true") throw new Error("Live quota probes require a disposable GitHub Actions client.");
const mode = process.argv[2];
if (mode === "audit") {
  const a = JSON.parse(await readFile("ingress-client-a/client-a.json", "utf8"));
  const b = JSON.parse(await readFile("ingress-client-b/client-b.json", "utf8"));
  if (!a.passed || !b.passed || !a.cleanupConfirmed || !b.cleanupConfirmed ||
      !a.clientIpHash || a.clientIpHash === b.clientIpHash) {
    throw new Error("Independent live clients and completed account cleanup were not established.");
  }
  const evidence = { checkedAt: new Date().toISOString(), distinctExternalClients: true,
    signupAllowancesIndependent: a.signupQuotaVerified && b.signupQuotaVerified,
    loginAllowancesIndependent: a.loginQuotaVerified && b.loginQuotaVerified,
    spoofedHeadersBlocked: a.spoofedHeadersBlocked && b.spoofedHeadersBlocked,
    temporaryAccountsDeleted: true, clients: [a, b] };
  await writeFile("live-ingress-results.json", JSON.stringify(evidence, null, 2));
  console.log(JSON.stringify(evidence));
  process.exit(0);
}
if (!["client-a", "client-b"].includes(mode)) throw new Error("Expected a named live client or audit.");
const origin = "https://roadconquest-ingress.22abongiovanni.workers.dev";
const existing = "https://roadconquest-api.22abongiovanni.workers.dev";
const seed = randomBytes(7).toString("hex");
let username = `Rci${seed}`;
const renamed = `Rcr${seed}`;
const password = randomBytes(32).toString("base64url");
let token;
let created = false;
let loginCalls = 0;
let checks = 0;
const result = { client: mode, origin, passed: false, cleanupConfirmed: false };
function check(condition, label) {
  if (!condition) throw new Error(label);
  checks++;
}
function spoofedHeaders() {
  return { "X-Forwarded-For": "192.0.2.250", "X-Real-IP": "192.0.2.251",
    "CF-Connecting-IP": "192.0.2.252", "X-RoadConquest-Client-IP": "192.0.2.253",
    "X-RoadConquest-Client-Timestamp": String(Date.now()), "X-RoadConquest-Client-Signature": "f".repeat(64) };
}
async function call(path, { method = "GET", body, auth = token, headers = {}, base = origin } = {}) {
  if (path === "/v1/login" && method === "POST") loginCalls++;
  const requestHeaders = { Accept: "application/json", ...headers };
  if (body !== undefined) requestHeaders["Content-Type"] = "application/json";
  if (auth) requestHeaders.Authorization = `Bearer ${auth}`;
  const response = await fetch(base + path, { method, headers: requestHeaders,
    body: body === undefined ? undefined : JSON.stringify(body), redirect: "manual",
    signal: AbortSignal.timeout(30000) });
  check(response.headers.get("Cache-Control") === "no-store", "API response must not be cached");
  check(response.url.startsWith("https://") && !response.redirected, "HTTPS without followed redirects required");
  check(response.headers.get("Content-Type")?.includes("application/json"), "API response must be JSON");
  return { status: response.status, body: await response.json() };
}
try {
  const trace = await fetch("https://www.cloudflare.com/cdn-cgi/trace", {
    redirect: "error", signal: AbortSignal.timeout(30000)
  });
  const ip = (await trace.text()).match(/^ip=(.+)$/m)?.[1]?.trim();
  check(Boolean(ip), "External client address was unavailable");
  result.clientIpHash = createHash("sha256").update(ip).digest("hex");

  const signup = await call("/v1/signup", { method: "POST", body: { username, password }, auth: null });
  check(signup.status === 201 && typeof signup.body.token === "string", "Temporary signup failed");
  token = signup.body.token;
  created = true;
  check((await call("/v1/privacy", { method: "PUT", body: { leaderboard_visible: false } })).status === 200,
    "Temporary profile privacy failed");
  check((await call("/v1/me?probe=ingress%2Fquery")).status === 200, "Authenticated query forwarding failed");
  check((await call("/v1/friends")).status === 200, "Friend list failed");

  // Duplicate only this script's own account; never create additional accounts
  // or probe an existing user's username to fill the eight-attempt allowance.
  for (let attempt = 2; attempt <= 8; attempt++) {
    const duplicate = await call("/v1/signup", { method: "POST", body: { username, password }, auth: null });
    check(duplicate.status === 409, `Signup allowance failed before attempt ${attempt}`);
  }
  check((await call("/v1/signup", { method: "POST", body: { username, password }, auth: null,
    headers: spoofedHeaders() })).status === 429, "Forged headers bypassed the signup IP allowance");
  result.signupQuotaVerified = true;

  check((await call("/v1/username", { method: "PUT", body: { username: renamed, password } })).status === 200,
    "Username change failed");
  username = renamed;
  const revoked = token;
  check((await call("/v1/logout", { method: "POST" })).status === 200, "Logout failed");
  token = null;
  check((await call("/v1/me", { auth: revoked })).status === 401, "Logout did not revoke the session");

  // This valid login goes through the pre-existing Worker. Its IP allowance
  // must agree with the new ingress after the approved coordinated key rotation.
  const login = await call("/v1/login", { method: "POST", body: { username, password }, base: existing, auth: null });
  check(login.status === 200 && typeof login.body.token === "string", "Existing Worker login failed after key rotation");
  token = login.body.token;
  check((await call("/v1/me")).status === 200, "Cross-ingress session failed");
  check((await call("/v1/account", { method: "DELETE", body: { password } })).status === 200,
    "Temporary account deletion failed");
  created = false;
  check((await call("/v1/me")).status === 401, "Deleted account session remained valid");
  token = null;
  check((await call("/v1/login", { method: "POST", body: { username, password }, auth: null })).status === 401,
    "Deleted account could still log in");
  result.cleanupConfirmed = true;

  while (loginCalls < 20) {
    const absent = `Rcp${randomBytes(8).toString("hex")}`;
    check((await call("/v1/login", { method: "POST", body: { username: absent, password }, auth: null })).status === 401,
      "Login IP allowance was shared or exhausted prematurely");
  }
  check((await call("/v1/login", { method: "POST", body: {
    username: `Rcp${randomBytes(8).toString("hex")}`, password }, auth: null, headers: spoofedHeaders() })).status === 429,
    "Forged headers bypassed the login IP allowance");
  result.loginQuotaVerified = true;
  result.spoofedHeadersBlocked = true;
  result.passed = true;
} catch (error) {
  // Labels contain no response bodies, credentials, IPs, or GPS data.
  result.failure = error.message;
  process.exitCode = 1;
} finally {
  if (created) {
    try {
      if (!token) {
        const login = await call("/v1/login", { method: "POST", body: { username, password }, auth: null });
        if (login.status === 200) token = login.body.token;
      }
      if (token) {
        const cleanup = await call("/v1/account", { method: "DELETE", body: { password } });
        result.cleanupConfirmed = cleanup.status === 200;
      }
    } catch { result.cleanupConfirmed = false; }
    if (!result.cleanupConfirmed) { result.temporaryAccountRequiringCleanup = username; process.exitCode = 1; }
  }
  result.checks = checks;
  result.checkedAt = new Date().toISOString();
  await mkdir("live-ingress-evidence", { recursive: true });
  await writeFile(`live-ingress-evidence/${mode}.json`, JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result));
}
