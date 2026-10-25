import test from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { createServer } from "node:http";
import { competitionConfig } from "../src/competition.mjs";
import { boundedJson, evidenceHash, parseEvidence, verifyIntegrityVerdict } from "../src/scoring.mjs";

const now = Date.now();
const fixes = [
  { lat: 0, lon: 0, accuracy: 3, speed: 10, time: now - 5000, elapsed: 10000, mock: false },
  { lat: 0, lon: 0.0005, accuracy: 3, speed: 10, time: now, elapsed: 15000, mock: false }
];
const evidence = () => ({ run: randomUUID(), nonce: "A".repeat(43), sequence: 0, fixes: structuredClone(fixes) });
const config = { packageName: "com.roadconquest.app", certificates: ["A".repeat(43)], versions: ["71"] };
const verdict = () => ({ requestDetails: { requestHash: "hash", requestPackageName: config.packageName, timestampMillis: String(now) },
  appIntegrity: { appRecognitionVerdict: "PLAY_RECOGNIZED", packageName: config.packageName, versionCode: "71", certificateSha256Digest: config.certificates },
  accountDetails: { appLicensingVerdict: "LICENSED" }, deviceIntegrity: { deviceRecognitionVerdict: ["MEETS_DEVICE_INTEGRITY", "MEETS_STRONG_INTEGRITY"] } });

test("only tightly bounded fresh GPS evidence is accepted and all client totals are rejected", () => {
  const body = evidence();
  assert.deepEqual(parseEvidence(JSON.stringify(body), now), body);
  for (const mutate of [b => { b.miles = 1000000; }, b => { b.roads = [1]; }, b => { b.cells = [1]; }, b => { b.fixes[0].mock = true; },
    b => { b.fixes[0].accuracy = 26; }, b => { b.fixes[1].time = now - 5000; },
    b => { b.fixes[0].time = now - 150001; }, b => { b.fixes[1].elapsed += 5000; },
    b => { b.fixes[1].lon = 180; }, b => { b.fixes[1].speed = 100; },
    b => { b.fixes[0].accuracy = null; }, b => { b.sequence = -1; }]) {
    const bad = evidence(); mutate(bad); assert.throws(() => parseEvidence(JSON.stringify(bad), now));
  }
  assert.throws(() => parseEvidence("null", now));
  assert.throws(() => parseEvidence("x".repeat(12001), now));
  // Clock-skew tolerance must not permit an unchanged monotonic reading.
  const stoppedClock = evidence();
  stoppedClock.fixes[0].time = now - 1000;
  stoppedClock.fixes[1].elapsed = stoppedClock.fixes[0].elapsed;
  assert.throws(() => parseEvidence(JSON.stringify(stoppedClock), now), /Discontinuous/u);
  assert.notEqual(evidenceHash(JSON.stringify(body)), evidenceHash(JSON.stringify({ ...body, sequence: 1 })));
});

test("Google verdict binds the whole action to the official certificate, version, device and fresh timestamp", () => {
  verifyIntegrityVerdict(verdict(), "hash", config, now);
  const sideloaded = verdict();
  sideloaded.appIntegrity.appRecognitionVerdict = "UNRECOGNIZED_VERSION";
  sideloaded.accountDetails.appLicensingVerdict = "UNLICENSED";
  sideloaded.deviceIntegrity.deviceRecognitionVerdict = ["MEETS_DEVICE_INTEGRITY"];
  assert.throws(() => verifyIntegrityVerdict(sideloaded, "hash", config, now));

  const sideloadedUnevaluatedLicense = structuredClone(sideloaded);
  sideloadedUnevaluatedLicense.accountDetails.appLicensingVerdict = "UNEVALUATED";
  assert.throws(() => verifyIntegrityVerdict(sideloadedUnevaluatedLicense, "hash", config, now));

  for (const mutate of [v => { v.requestDetails.requestHash = "changed"; }, v => { v.requestDetails.timestampMillis = String(now - 120001); },
    v => { v.requestDetails.requestPackageName = "other"; }, v => { v.appIntegrity.packageName = "other"; },
    v => { v.appIntegrity.versionCode = "5"; }, v => { v.appIntegrity.certificateSha256Digest = ["B".repeat(43)]; },
    v => { v.appIntegrity.appRecognitionVerdict = "UNEVALUATED"; },
    v => { v.accountDetails.appLicensingVerdict = "UNLICENSED"; }, v => { v.deviceIntegrity.deviceRecognitionVerdict = []; },
    v => { v.deviceIntegrity.deviceRecognitionVerdict = ["MEETS_DEVICE_INTEGRITY"]; },
    v => { v.deviceIntegrity.deviceRecognitionVerdict = ["MEETS_STRONG_INTEGRITY"]; }]) {
    const bad = verdict(); mutate(bad); assert.throws(() => verifyIntegrityVerdict(bad, "hash", config, now));
  }
});

test("cell competition needs production Play configuration and encryption, with no road infrastructure", () => {
  assert.equal(competitionConfig({}), null);
  assert.throws(() => competitionConfig({ LEADERBOARDS_ENABLED: "1" }));
  const env = { LEADERBOARDS_ENABLED: "1", PLAY_SERVICE_ACCOUNT_FILE: "credential-file",
    PLAY_CERTIFICATES: "A".repeat(43), PLAY_VERSION_CODES: "71", PLAY_CLOUD_PROJECT_NUMBER: "123456789",
    VERIFIED_EVIDENCE_KEY: "K".repeat(43) };
  assert.equal(competitionConfig(env).dataset, "fog-grid-v1");
  assert.throws(() => competitionConfig({ ...env, PLAY_VERSION_CODES: "70,71" }));
  assert.throws(() => competitionConfig({ ...env, VERIFIED_EVIDENCE_KEY: undefined }));
  assert.throws(() => competitionConfig({ ...env, VERIFIED_EVIDENCE_KEY: "short" }));
  assert.throws(() => competitionConfig({ ...env, PLAY_CERTIFICATES: undefined }));
});

test("upstream failures and oversized verification responses fail closed", async () => {
  await assert.rejects(boundedJson(new Response("{}", { status: 500 })));
  await assert.rejects(boundedJson(new Response("x".repeat(100)), 10));
  await assert.rejects(boundedJson(new Response("not JSON")));
});

test("bounded verification JSON avoids a one-chunk copy and preserves sliced/split UTF-8", async () => {
  const data = { value: '道路 🚗', verified: true };
  const text = JSON.stringify(data);
  const backing = Buffer.from('prefix' + text + 'suffix');
  const view = backing.subarray(6, 6 + Buffer.byteLength(text));
  const response = chunks => new Response(new ReadableStream({ start(controller) {
    for (const chunk of chunks) controller.enqueue(chunk);
    controller.close();
  } }));
  const original = Buffer.concat;
  let copies = 0;
  Buffer.concat = (...args) => { copies++; return original(...args); };
  try {
    assert.deepEqual(await boundedJson(response([view]), view.length), data);
    assert.equal(copies, 0);
    const split = Buffer.byteLength('{"value":"') + 1; // Inside a multibyte character.
    assert.deepEqual(await boundedJson(response([view.subarray(0, split), view.subarray(split)]), view.length), data);
    assert.equal(copies, 1);
    await assert.rejects(boundedJson(response([view]), view.length - 1), error => error.status === 503);
    assert.equal(copies, 1, 'Reject oversized bytes before allocating a joined buffer');
  } finally { Buffer.concat = original; }
});

test("failed verification responses release unread streams and preserve the retryable error", async () => {
  for (const status of [401, 403, 429, 500, 503]) {
    let cancelled = 0;
    const response = new Response(new ReadableStream({
      cancel() { cancelled++; }
    }), { status });
    await assert.rejects(boundedJson(response), error => error.status === 503);
    assert.equal(cancelled, 1);
    assert.equal(response.bodyUsed, true);
  }
  await assert.rejects(boundedJson(new Response(new ReadableStream({
    cancel() { throw new Error('socket already failed'); }
  }), { status: 500 })), error => error.status === 503);
  await assert.rejects(boundedJson(new Response(null, { status: 503 })), error => error.status === 503);
});

test("slow error-stream cleanup cannot delay reporting a verification outage", async () => {
  let finishCleanup, cancelled = false, reported;
  const cleanup = new Promise(resolve => { finishCleanup = resolve; });
  const response = new Response(new ReadableStream({
    cancel() { cancelled = true; return cleanup; }
  }), { status: 503 });
  const attempt = boundedJson(response).catch(error => { reported = error; });
  try {
    // Drain microtasks without depending on a wall-clock timing threshold.
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(cancelled, true);
    assert.equal(reported?.status, 503, 'Return the failure while cleanup is still pending');
  } finally {
    finishCleanup();
    await attempt;
  }
});

test("failed verification cancels an actual unfinished upstream HTTP response", async () => {
  let disconnected, timer;
  const closed = new Promise(resolve => { disconnected = resolve; });
  const server = createServer((_request, response) => {
    response.once('close', disconnected);
    response.writeHead(503, { 'Content-Type': 'application/json' });
    response.write('{"error":'); // Headers arrive, but the upstream body never ends.
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const response = await fetch(`http://127.0.0.1:${server.address().port}/`);
    await assert.rejects(boundedJson(response), error => error.status === 503);
    await Promise.race([closed, new Promise((_resolve, reject) => {
      timer = setTimeout(() => reject(new Error('Unread upstream connection remained open')), 5000);
    })]);
  } finally {
    clearTimeout(timer);
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
});

test('oversized announced JSON is cancelled before reading while encoded responses retain the decoded-byte limit', async () => {
  let reads = 0, cancelled = false;
  const oversized = new Response(new ReadableStream({
    pull() { reads++; throw new Error('Oversized announced reply must not be downloaded'); },
    cancel() { cancelled = true; }
  }, { highWaterMark: 0 }), { headers: { 'Content-Length': '100' } });
  await assert.rejects(boundedJson(oversized, 10), e => e.status === 503 && /too large/.test(e.message));
  assert.equal(reads, 0);
  assert.equal(cancelled, true);
  // Fetch transparently decompresses bodies but retains the wire-length header.
  for (const encoding of ['gzip', 'br']) {
    assert.deepEqual(await boundedJson(new Response('{}', { headers: {
      'Content-Encoding': encoding, 'Content-Length': '100'
    } }), 2), {});
  }
  await assert.rejects(boundedJson(new Response('x'.repeat(11)), 10), /too large/);
});
