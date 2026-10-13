import test from "node:test";
import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { createFetchHandler } from "../src/fetch-adapter.mjs";

test("HTTPS transport cannot be spoofed with forwarding headers", async () => {
  const fetch = createFetchHandler(() => assert.fail("HTTP must never reach the account API"));
  const response = await fetch(new Request("http://accounts.example/v1/login", {
    method: "POST", headers: { "X-Forwarded-Proto": "https" }, body: "{}"
  }));
  assert.equal(response.status, 400);
  assert.equal(response.headers.get("Cache-Control"), "no-store");
});

test("spoofed IP headers cannot change the anonymous attempt budget", async () => {
  const requests = [];
  const fetch = createFetchHandler((req, res) => {
    requests.push(req);
    res.writeHead(200, { "Content-Type": "application/json", "Cache-Control": "no-store" });
    res.end('{"ok":true}');
  });
  for (const ip of ["198.51.100.1", "203.0.113.2, 127.0.0.1"]) {
    const response = await fetch(new Request("https://accounts.example/v1/me?test=1", {
      headers: { "X-Forwarded-For": ip, "X-Real-IP": ip, "X-Forwarded-Proto": "http",
        Authorization: "Bearer example" }
    }));
    assert.equal(response.status, 200);
    assert.equal(response.headers.get("Cache-Control"), "no-store");
  }
  assert.equal(requests[0].socket.remoteAddress, requests[1].socket.remoteAddress);
  for (const req of requests) {
    assert.equal(req.headers["x-forwarded-for"], undefined);
    assert.equal(req.headers["x-real-ip"], undefined);
    assert.equal(req.headers["x-forwarded-proto"], "https");
    assert.equal(req.headers.authorization, "Bearer example");
    assert.equal(req.url, "/v1/me?test=1");
  }
});

test("request bodies remain streaming so the API can enforce its size limit", async () => {
  const payload = 'x'.repeat(64 * 1024);
  const fetch = createFetchHandler(async (req, res) => {
    let length = 0;
    for await (const chunk of req) length += chunk.length;
    assert.equal(length, payload.length);
    res.writeHead(413, { "Content-Type": "application/json" });
    res.end('{"error":"request too large"}');
  });
  const response = await fetch(new Request("https://accounts.example/v1/signup", {
    method: "POST", body: payload
  }));
  assert.equal(response.status, 413);
});

test("only recent ingress-signed client IPs separate rate-limit identities", async () => {
  const key = "a-demo-secret-long-enough-for-identity-authentication";
  const remoteAddresses = [];
  const fetch = createFetchHandler((req, res) => {
    remoteAddresses.push(req.socket.remoteAddress);
    assert.equal(req.headers["x-roadconquest-client-ip"], undefined);
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end('{"ok":true}');
  }, { rateLimitIpSignatureKey: key });
  const path = "/v1/login?source=test";
  const timestamp = String(Date.now());
  const signed = (ip, time = timestamp) => createHmac("sha256", key)
    .update(`GET\n${path}\n${ip}\n${time}`, "utf8").digest("hex");
  const request = (ip, signature, time = timestamp) => new Request(
    "https://accounts.example" + path,
    { headers: {
      "X-Forwarded-For": "192.0.2.99",
      "X-RoadConquest-Client-IP": ip,
      "X-RoadConquest-Client-Timestamp": time,
      "X-RoadConquest-Client-Signature": signature
    } }
  );
  await fetch(request("203.0.113.5", signed("203.0.113.5")));
  await fetch(request("198.51.100.2", signed("198.51.100.2")));
  await fetch(request("198.51.100.3", signed("203.0.113.5")));
  await fetch(request("2001:db8::1", "0".repeat(64)));
  const expired = String(Date.now() - 120_000);
  await fetch(request("203.0.113.5", signed("203.0.113.5", expired), expired));
  assert.deepEqual(remoteAddresses, [
    "203.0.113.5", "198.51.100.2", "function-anonymous", "function-anonymous", "function-anonymous"
  ]);
});
