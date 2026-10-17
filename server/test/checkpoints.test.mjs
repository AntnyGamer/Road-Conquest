import test from "node:test";
import assert from "node:assert/strict";
import { createCheckpointCodec, sealCheckpoint, verifyContinuity } from "../src/checkpoints.mjs";
import { parseEvidence } from "../src/scoring.mjs";
import { randomUUID } from "node:crypto";

const secret = "K".repeat(43);
const run = randomUUID();
const scope = "2:123:dataset";
const fix = { lat: 40, lon: -75, time: Date.now() - 5000, elapsed: 100000, accuracy: 5, speed: 10, mock: false };
const next = (changes = {}) => ({ ...fix, lon: -74.9995, time: fix.time + 5000, elapsed: fix.elapsed + 5000, ...changes });

test("batch checkpoint contexts interoperate with existing ciphertext and retain scope and replay checks", () => {
  const codec = createCheckpointCodec(scope, secret);
  const old = sealCheckpoint(fix, run, scope, secret);
  assert.doesNotThrow(() => codec.verify(old, next(), run));
  assert.doesNotThrow(() => codec.verify(null, fix, run));
  const sealed = codec.seal(fix, run);
  assert.doesNotThrow(() => verifyContinuity(sealed, next(), run, scope, secret));
  assert.notEqual(codec.seal(fix, run).sealed, sealed.sealed);
  assert.throws(() => createCheckpointCodec('other-account', secret).verify(sealed, next(), run), /checkpoint unavailable/u);
  assert.throws(() => createCheckpointCodec(scope, 'L'.repeat(43)).verify(sealed, next(), run), /checkpoint unavailable/u);
  assert.throws(() => codec.verify(sealed, { ...fix, lon: fix.lon + 0.00001 }, run), /Overlapping/u);
  assert.throws(() => codec.verify(sealed, next({ lon: 0 }), run), /Impossible travel/u);
});

test("checkpoint encryption is randomized, scoped, authenticated and not exposed as coordinates", () => {
  const a = sealCheckpoint(fix, run, scope, secret);
  const b = sealCheckpoint(fix, run, scope, secret);
  assert.deepEqual(Object.keys(a).sort(), ["fingerprint", "sealed", "time", "version"]);
  assert.notEqual(a.sealed, b.sealed);
  assert.doesNotThrow(() => verifyContinuity(a, fix, run, scope, secret));
  assert.doesNotThrow(() => verifyContinuity(a, next(), run, scope, secret));
  for (const [s, key] of [["2:other:dataset", secret], ["2:123:other", secret], [scope, "L".repeat(43)]]) {
    assert.throws(() => verifyContinuity(a, next(), run, s, key), /checkpoint unavailable/u);
  }
  for (const altered of [{ ...a, time: a.time + 1 }, { ...a, fingerprint: "changed" },
    { ...a, sealed: "A".repeat(a.sealed.length) }, { ...a, version: 1 }, { ...a, sealed: undefined }]) {
    assert.throws(() => verifyContinuity(altered, next(), run, scope, secret), /checkpoint unavailable/u);
  }
});

test("overlap, altered boundary, clock resets and fast jumps cannot restart credit", () => {
  const checkpoint = sealCheckpoint(fix, run, scope, secret);
  for (const bad of [{ ...fix, time: fix.time - 1 }, { ...fix, lon: fix.lon + 0.00001 }]) {
    assert.throws(() => verifyContinuity(checkpoint, bad, run, scope, secret), /Overlapping/u);
  }
  for (const bad of [next({ lon: 0 }), next({ elapsed: 1 }), next({ elapsed: fix.elapsed + 9000 }),
    next({ time: fix.time + 1000, elapsed: fix.elapsed + 1000, speed: 50 })]) {
    assert.throws(() => verifyContinuity(checkpoint, bad, run, scope, secret), /Impossible travel/u);
  }
  assert.throws(() => verifyContinuity(checkpoint, next({ lon: 0 }), randomUUID(), scope, secret), /Impossible travel/u);
  // An honest separate drive can follow a flight/reboot. That intervening travel earns no miles.
  assert.doesNotThrow(() => verifyContinuity(checkpoint,
    next({ lon: -120, time: fix.time + 6 * 3600000, elapsed: 100 }), randomUUID(), scope, secret));
  assert.doesNotThrow(() => verifyContinuity(null, next(), run, scope, secret));
});

test("impossible acceleration is rejected even when coordinates and reported top speed are bounded", () => {
  const now = Date.now();
  const a = { ...fix, time: now - 1000 };
  const b = { ...a, lon: -74.9995, time: now, elapsed: a.elapsed + 1000, speed: 50 };
  assert.throws(() => parseEvidence(JSON.stringify({ run, nonce: "A".repeat(43), sequence: 0, fixes: [a, b] }), now), /Discontinuous/u);
});
