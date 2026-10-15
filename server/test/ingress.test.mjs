import test from "node:test";
import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { createAccountIngress } from "../worker/ingress.mjs";

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
