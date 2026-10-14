import test from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { competitionConfig } from "../src/competition.mjs";
import { boundedJson, edgeKey, evidenceHash, matchEvidence, parseEvidence, scoreMatch, verifyIntegrityVerdict } from "../src/scoring.mjs";

const now = Date.now();
const fixes = [
  { lat: 0, lon: 0, accuracy: 3, speed: 10, time: now - 5000, elapsed: 10000, mock: false },
  { lat: 0, lon: 0.0005, accuracy: 3, speed: 10, time: now, elapsed: 15000, mock: false }
];
export function matching(points = fixes) {
  return { code: "Ok", data_version: "2026-10-01T00:00:00Z",
    tracepoints: points.map((f, i) => ({ matchings_index: 0, waypoint_index: i, alternatives_count: 0, location: [f.lon, f.lat] })),
    matchings: [{ confidence: 0.99, legs: points.slice(1).map(() => ({ distance: 50, annotation: { nodes: [10, 11, 12], distance: [25, 25] } })) }] };
}
const evidence = () => ({ run: randomUUID(), nonce: "A".repeat(43), sequence: 0, fixes: structuredClone(fixes) });
const catalog = new Map([[edgeKey(10, 11), "100"], [edgeKey(11, 12), "101"]]);
const version = "2026-10-01T00:00:00Z";
const config = { packageName: "com.roadconquest.app", certificates: ["A".repeat(43)], versions: ["71"] };
const verdict = () => ({ requestDetails: { requestHash: "hash", requestPackageName: config.packageName, timestampMillis: String(now) },
  appIntegrity: { appRecognitionVerdict: "PLAY_RECOGNIZED", packageName: config.packageName, versionCode: "71", certificateSha256Digest: config.certificates },
  accountDetails: { appLicensingVerdict: "LICENSED" }, deviceIntegrity: { deviceRecognitionVerdict: ["MEETS_DEVICE_INTEGRITY", "MEETS_STRONG_INTEGRITY"] } });

test("only tightly bounded fresh GPS evidence is accepted and all client totals are rejected", () => {
  const body = evidence();
  assert.deepEqual(parseEvidence(JSON.stringify(body), now), body);
  for (const mutate of [b => { b.miles = 1000000; }, b => { b.roads = [1]; }, b => { b.fixes[0].mock = true; },
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

test("mileage uses trusted matched distances and roads use distinct catalog IDs regardless of names or direction", () => {
  const result = scoreMatch(matching(), fixes, catalog, version);
  assert.equal(result.millimeters, 50000);
  assert.deepEqual(result.roads, ["100", "101"]);
  const sameRoad = new Map([[edgeKey(10, 11), "100"], [edgeKey(11, 12), "100"]]);
  assert.deepEqual(scoreMatch(matching(), fixes, sameRoad, version).roads, ["100"]);
  const backwards = fixes.map((f, i) => ({ ...f, lon: fixes[fixes.length - 1 - i].lon }));
  const reverse = matching(backwards);
  reverse.matchings[0].legs[0].annotation.nodes.reverse();
  assert.deepEqual(scoreMatch(reverse, backwards, catalog, version).roads.sort(), ["100", "101"]);
});

test("partial matches, changed datasets, ambiguous snaps, unknown roads and impossible routes earn no credit", () => {
  for (const mutate of [m => { m.data_version = "changed"; }, m => { m.tracepoints[0] = null; },
    m => { m.tracepoints[0].alternatives_count = 1; }, m => { m.tracepoints[0].location = [20, 0]; },
    m => { m.matchings[0].confidence = 0.94; }, m => { m.matchings[0].legs[0].annotation.nodes = [99, 11, 12]; },
    m => { m.matchings[0].legs[0].annotation.distance = [1000, 1000]; m.matchings[0].legs[0].distance = 2000; },
    m => { m.matchings[0].legs[0].annotation.nodes = [10]; }, m => { m.matchings[0].legs[0].distance = 20; }]) {
    const bad = matching(); mutate(bad); assert.throws(() => scoreMatch(bad, fixes, catalog, version));
  }
});

test("stationary jitter and low-speed motion do not unlock roads or add miles", () => {
  const slow = structuredClone(fixes); slow.forEach(f => { f.speed = 0; });
  assert.deepEqual(scoreMatch(matching(slow), slow, catalog, version), { millimeters: 0, roads: [] });
  const jitter = matching(); jitter.matchings[0].legs[0].distance = 2;
  jitter.matchings[0].legs[0].annotation.distance = [1, 1];
  assert.deepEqual(scoreMatch(jitter, fixes, catalog, version), { millimeters: 0, roads: [] });
  const stationary = fixes.map(f => ({ ...f, lon: 0 }));
  const inflatedSnap = matching(stationary);
  inflatedSnap.matchings[0].legs[0].distance = 20;
  inflatedSnap.matchings[0].legs[0].annotation.distance = [10, 10];
  assert.deepEqual(scoreMatch(inflatedSnap, stationary, catalog, version), { millimeters: 0, roads: [] });
});

test("rankings cannot accidentally start with demo OSRM or missing production verification settings", () => {
  assert.equal(competitionConfig({}), null);
  assert.throws(() => competitionConfig({ LEADERBOARDS_ENABLED: "1" }));
  const env = { LEADERBOARDS_ENABLED: "1", ROAD_DATASET_SHA256: "a".repeat(64), OSRM_DATA_VERSION: version,
    VERIFIED_OSRM_URL: "https://roads.example.com", VERIFIED_OSRM_BEARER_TOKEN: "B".repeat(43), PLAY_SERVICE_ACCOUNT_FILE: "credential-file",
    PLAY_CERTIFICATES: "A".repeat(43), PLAY_VERSION_CODES: "71", PLAY_CLOUD_PROJECT_NUMBER: "123456789",
    VERIFIED_EVIDENCE_KEY: "K".repeat(43) };
  assert.equal(competitionConfig(env).dataset, "a".repeat(64));
  assert.throws(() => competitionConfig({ ...env, VERIFIED_OSRM_URL: "https://router.project-osrm.org" }));
  assert.throws(() => competitionConfig({ ...env, PLAY_VERSION_CODES: "70,71" }));
  assert.throws(() => competitionConfig({ ...env, VERIFIED_EVIDENCE_KEY: undefined }));
  assert.throws(() => competitionConfig({ ...env, VERIFIED_EVIDENCE_KEY: "short" }));
  assert.throws(() => competitionConfig({ ...env, VERIFIED_OSRM_BEARER_TOKEN: undefined }));
});

test("upstream failures and oversized verification responses fail closed", async () => {
  await assert.rejects(boundedJson(new Response("{}", { status: 500 })));
  await assert.rejects(boundedJson(new Response("x".repeat(100)), 10));
  await assert.rejects(boundedJson(new Response("not JSON")));
});

test("private road matching authenticates on the server without putting the secret in its URL", async () => {
  const savedFetch = globalThis.fetch;
  const token = "B".repeat(43);
  try {
    globalThis.fetch = async (url, options) => {
      assert.equal(new URL(url).origin, "https://roads.example.com");
      assert.equal(String(url).includes(token), false);
      assert.equal(options.headers.Authorization, `Bearer ${token}`);
      assert.equal(options.redirect, "error");
      return new Response(JSON.stringify(matching()));
    };
    assert.deepEqual(await matchEvidence(fixes, { osrmUrl: "https://roads.example.com", osrmToken: token }), matching());
  } finally { globalThis.fetch = savedFetch; }
});
