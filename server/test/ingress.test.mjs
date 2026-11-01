import test from "node:test";
import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { createAccountIngress } from "../worker/ingress.mjs";
import { createFetchHandler } from "../src/fetch-adapter.mjs";

const secret = "test-signed-ingress-secret-longer-than-32-characters";
const env = {
  API_ORIGIN: "https://roadconquest.neon.tech/",
  RATE_LIMIT_IP_SIGNATURE_KEY: secret
};
const now = 1_800_000_000_000;

test("trusted Cloudflare IP gets the exact HMAC identity expected by Neon", async () => {
  let outgoing;
  const proxy = createAccountIngress(async request => {
    outgoing = request.clone();
    return new Response(JSON.stringify({ ok: true }), {
      headers: { "Content-Type": "application/json", "Cache-Control": "public, max-age=9999" }
    });
  }, () => now);
  const response = await proxy(new Request(
    "https://roadconquest.workers.dev/v1/signup?source=app",
    {
      method: "POST",
      headers: {
        "CF-Connecting-IP": "203.0.113.31",
        "X-Forwarded-For": "192.0.2.1",
        "X-Real-IP": "192.0.2.2",
        "X-RoadConquest-Client-IP": "192.0.2.3",
        "X-RoadConquest-Client-Timestamp": "9999999999999",
        "X-RoadConquest-Client-Signature": "f".repeat(64),
        Authorization: "Bearer sample"
      },
      body: JSON.stringify({ username: "Driver", password: "examplePassword" }),
      duplex: "half"
    }
  ), env);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("Cache-Control"), "no-store");
  assert.equal(outgoing.url, "https://roadconquest.neon.tech/v1/signup?source=app");
  assert.equal(outgoing.method, "POST");
  assert.equal(outgoing.redirect, "manual");
  assert.equal(outgoing.headers.get("Authorization"), "Bearer sample");
  assert.equal(outgoing.headers.get("CF-Connecting-IP"), null);
  assert.equal(outgoing.headers.get("X-Forwarded-For"), null);
  assert.equal(outgoing.headers.get("X-Real-IP"), null);
  assert.equal(outgoing.headers.get("X-RoadConquest-Client-IP"), "203.0.113.31");
  assert.equal(outgoing.headers.get("X-RoadConquest-Client-Timestamp"), String(now));
  const signature = createHmac("sha256", secret)
    .update(["POST", "/v1/signup?source=app", "203.0.113.31", String(now)].join("\n"))
    .digest("hex");
  assert.equal(outgoing.headers.get("X-RoadConquest-Client-Signature"), signature);
  assert.deepEqual(JSON.parse(await outgoing.text()), {
    username: "Driver", password: "examplePassword"
  });
});

test("static public reads avoid identity signing but other requests keep rate-limit provenance", async () => {
  const forwarded = [];
  const proxy = createAccountIngress(async request => {
    forwarded.push(request);
    return Response.json({ ok: true });
  }, () => now);
  const headers = { "CF-Connecting-IP": "203.0.113.31",
    "X-RoadConquest-Client-IP": "forged", "X-RoadConquest-Client-Signature": "forged" };
  for (const path of ["/health/live", "/privacy", "/delete-account", "/v1/competition", "/v1/backups"]) {
    await proxy(new Request("https://roadconquest.workers.dev" + path, { headers }), env);
    const sent = forwarded.at(-1);
    assert.equal(sent.headers.get("X-RoadConquest-Client-IP"), null, path);
    assert.equal(sent.headers.get("X-RoadConquest-Client-Signature"), null, path);
  }
  for (const path of ["/v1/me", "/v1/leaderboard", "/v1/backups/account-key", "/v1/forum/posts"]) {
    await proxy(new Request("https://roadconquest.workers.dev" + path, { headers }), env);
    const sent = forwarded.at(-1);
    assert.equal(sent.headers.get("X-RoadConquest-Client-IP"), "203.0.113.31", path);
    assert.match(sent.headers.get("X-RoadConquest-Client-Signature"), /^[a-f0-9]{64}$/, path);
  }
  await proxy(new Request("https://roadconquest.workers.dev/v1/competition", {
    method: "POST", headers, body: "{}"
  }), env);
  assert.equal(forwarded.at(-1).headers.get("X-RoadConquest-Client-IP"), "203.0.113.31",
    "unknown methods must still use the normal signed ingress path");
});

test("no Cloudflare edge IP never forwards a forged signed client identity", async () => {
  let outgoing;
  const proxy = createAccountIngress(async request => {
    outgoing = request;
    return new Response("{}", { status: 200 });
  }, () => now);
  const response = await proxy(new Request("https://roadconquest.workers.dev/v1/me", {
    headers: {
      "X-Forwarded-For": "203.0.113.50",
      "X-RoadConquest-Client-IP": "203.0.113.51",
      "X-RoadConquest-Client-Timestamp": String(now),
      "X-RoadConquest-Client-Signature": "a".repeat(64)
    }
  }), env);
  assert.equal(response.status, 200);
  for (const key of ["x-forwarded-for", "x-roadconquest-client-ip",
    "x-roadconquest-client-timestamp", "x-roadconquest-client-signature"]) {
    assert.equal(outgoing.headers.get(key), null, key);
  }
});

test("preserves HTTP status, private response and DELETE account body", async () => {
  let requestCopy;
  const proxy = createAccountIngress(async request => {
    requestCopy = request.clone();
    return new Response('{"error":"Too many attempts"}', {
      status: 429, headers: { "Content-Type": "application/json" }
    });
  }, () => now);
  const response = await proxy(new Request("https://roadconquest.workers.dev/v1/account", {
    method: "DELETE", body: '{"password":"secret"}', duplex: "half",
    headers: { "Content-Type": "application/json" }
  }), env);
  assert.equal(response.status, 429);
  assert.equal(response.headers.get("Cache-Control"), "no-store");
  assert.equal(requestCopy.method, "DELETE");
  assert.equal(await requestCopy.text(), '{"password":"secret"}');
});

test("backend network errors return private JSON rather than an unhandled Worker exception", async () => {
  const proxy = createAccountIngress(async () => {
    throw new Error("internal provider error containing sensitive details");
  }, () => now);
  const response = await proxy(new Request("https://roadconquest.workers.dev/v1/me", {
    headers: { Authorization: "Bearer sensitive-token" }
  }), env);
  assert.equal(response.status, 503);
  assert.equal(response.headers.get("Cache-Control"), "no-store");
  assert.equal(response.headers.get("Content-Type"), "application/json");
  const body = await response.text();
  assert.equal(JSON.parse(body).error, "Account service is unavailable.");
  assert.doesNotMatch(body, /sensitive|provider error/);
});

test("accidentally pointing an ingress at itself fails closed without issuing fetch", async () => {
  let calls = 0;
  const proxy = createAccountIngress(async () => {
    calls++;
    return new Response("{}");
  });
  const response = await proxy(new Request("https://roadconquest.workers.dev/v1/me"), {
    ...env, API_ORIGIN: "https://roadconquest.workers.dev/"
  });
  assert.equal(response.status, 503);
  assert.equal(calls, 0);
});

test("canonical upstream URL and self-loop detection share the same parsed host", async () => {
  const destinations = [];
  const proxy = createAccountIngress(async request => {
    destinations.push(request.url);
    return new Response("{}");
  });
  const request = new Request("https://roadconquest.workers.dev/v1/me?source=test");
  assert.equal((await proxy(request, {
    ...env, API_ORIGIN: "https://ROADCONQUEST.NEON.TECH:443/"
  })).status, 200);
  assert.deepEqual(destinations, ["https://roadconquest.neon.tech/v1/me?source=test"]);
  assert.equal((await proxy(request, {
    ...env, API_ORIGIN: "https://ROADCONQUEST.WORKERS.DEV:443/"
  })).status, 503);
  assert.equal(destinations.length, 1);
});

test("misconfigured upstream and HTTP never forward credentials", async () => {
  let requests = 0;
  const proxy = createAccountIngress(async () => { requests++; return new Response("{}", { status: 200 }); });
  for (const config of [
    { ...env, API_ORIGIN: "http://insecure.example" },
    { ...env, API_ORIGIN: "https://example.neon.tech/other" },
    { ...env, RATE_LIMIT_IP_SIGNATURE_KEY: "short" }
  ]) {
    const response = await proxy(new Request("https://roadconquest.workers.dev/v1/me"), config);
    assert.equal(response.status, 503);
  }
  assert.equal((await proxy(new Request("http://roadconquest.workers.dev/v1/me"), env)).status, 400);
  assert.equal(requests, 0);
});

test("strips all caller identity and proxy header variants while preserving API headers", async () => {
  let outgoing;
  const proxy = createAccountIngress(async request => {
    outgoing = request;
    return new Response("{}");
  }, () => now);
  const untrusted = {
    "Forwarded": "for=192.0.2.4;host=attacker.example;proto=http",
    "X-Forwarded-Host": "attacker.example",
    "X-Forwarded-Proto": "http",
    "X-Forwarded-Port": "80",
    "X-Forwarded-Custom": "untrusted",
    "True-Client-IP": "192.0.2.4",
    "CF-Connecting-IPv6": "2001:db8::99",
    "CF-Pseudo-IPv4": "240.0.0.4",
    "X-RoadConquest-Client-Extra": "untrusted"
  };
  await proxy(new Request("https://roadconquest.workers.dev/v1/me", {
    headers: { ...untrusted, "CF-Connecting-IP": "203.0.113.31", Authorization: "Bearer sample" }
  }), env);
  for (const name of Object.keys(untrusted)) assert.equal(outgoing.headers.get(name), null, name);
  assert.equal(outgoing.headers.get("Authorization"), "Bearer sample");
  assert.equal(outgoing.headers.get("X-RoadConquest-Client-IP"), "203.0.113.31");
});

test("upstream Worker requests never promote mutable IPs or the shared placeholder to trusted identities", async () => {
  const outgoing = [];
  const proxy = createAccountIngress(async request => {
    outgoing.push(request);
    return new Response("{}");
  }, () => now);
  for (const headers of [
    { "CF-Worker": "upstream.example", "CF-Connecting-IP": "203.0.113.31" },
    { "CF-Worker": "", "CF-Connecting-IP": "203.0.113.32" },
    { "CF-Connecting-IP": "2a06:98c0:3600::103" }
  ]) {
    await proxy(new Request("https://roadconquest.workers.dev/v1/signup", {
      headers: { ...headers, "X-Real-IP": "192.0.2.99", "X-RoadConquest-Client-IP": "192.0.2.98" }
    }), env);
  }
  for (const request of outgoing) {
    for (const name of ["CF-Worker", "CF-Connecting-IP", "X-Real-IP", "X-RoadConquest-Client-IP",
      "X-RoadConquest-Client-Timestamp", "X-RoadConquest-Client-Signature"]) {
      assert.equal(request.headers.get(name), null, name);
    }
  }
});

test("Worker signatures interoperate with the actual Neon verifier for simulated IPv4 and IPv6 clients", async () => {
  const identities = [];
  const backend = createFetchHandler((request, response) => {
    identities.push(request.socket.remoteAddress);
    assert.equal(request.url, "/v1/login?source=ingress-test&encoded=%2F");
    response.writeHead(401, { "Content-Type": "application/json" });
    response.end('{"error":"Invalid credentials"}');
  }, { rateLimitIpSignatureKey: secret });
  const proxy = createAccountIngress(backend);
  for (const headers of [
    { "CF-Connecting-IP": "203.0.113.31" },
    { "CF-Connecting-IP": "2001:db8::31" },
    { "CF-Worker": "upstream.example", "CF-Connecting-IP": "192.0.2.99" },
    { "X-Forwarded-For": "192.0.2.98", "X-RoadConquest-Client-IP": "192.0.2.97" }
  ]) {
    const response = await proxy(new Request(
      "https://roadconquest.workers.dev/v1/login?source=ingress-test&encoded=%2F",
      { method: "POST", headers, body: "{}" }
    ), env);
    assert.equal(response.status, 401);
    assert.equal(response.headers.get("Cache-Control"), "no-store");
    assert.deepEqual(await response.json(), { error: "Invalid credentials" });
  }
  assert.deepEqual(identities, ["203.0.113.31", "2001:db8::31", "function-anonymous", "function-anonymous"]);
});

test("upstream redirects are returned without forwarding credentials to their target", async () => {
  let calls = 0;
  const proxy = createAccountIngress(async request => {
    calls++;
    assert.equal(request.redirect, "manual");
    assert.equal(new URL(request.url).origin, "https://roadconquest.neon.tech");
    return new Response(null, { status: 307, headers: { Location: "https://untrusted.example/" } });
  });
  const response = await proxy(new Request("https://roadconquest.workers.dev/v1/me", {
    headers: { Authorization: "Bearer sample" }
  }), env);
  assert.equal(calls, 1);
  assert.equal(response.status, 307);
  assert.equal(response.headers.get("Location"), "https://untrusted.example/");
  assert.equal(response.headers.get("Cache-Control"), "no-store");
});
