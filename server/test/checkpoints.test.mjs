import test from "node:test";
import assert from "node:assert/strict";
import { createCheckpointCodec } from "../src/checkpoints.mjs";
import { parseEvidence } from "../src/scoring.mjs";
import { createCipheriv, createDecipheriv, createHmac, randomUUID } from "node:crypto";

const secret = "K".repeat(43);
const run = randomUUID();
const scope = "2:123:dataset";
const fix = { lat: 40, lon: -75, time: Date.now() - 5000, elapsed: 100000, accuracy: 5, speed: 10, mock: false };
const next = (changes = {}) => ({ ...fix, lon: -74.9995, time: fix.time + 5000, elapsed: fix.elapsed + 5000, ...changes });

test("checkpoint buffer assembly avoids duplicate ciphertext and authenticated plaintext copies", () => {
  const codec = createCheckpointCodec(scope, secret);
  const concatenate = Buffer.concat;
  const counts = { seal: 0, verify: 0 };
  let phase = 'seal';
  Buffer.concat = function (...args) { counts[phase]++; return concatenate.apply(this, args); };
  try {
    const checkpoint = codec.seal(fix, run);
    phase = 'verify';
    codec.verify(checkpoint, next(), run);
  } finally { Buffer.concat = concatenate; }
  assert.deepEqual(counts, { seal: 1, verify: 0 });
});

test("checkpoint bytes match original assembly and old ciphertext still verifies with exact UTF8", () => {
  const codec = createCheckpointCodec(scope, secret);
  const key = createHmac('sha256', secret).update('roadconquest-checkpoint-v2\0').update(scope).digest();
  for (const fixtureRun of [run, 'Unicode-🛣️-é-\u0000']) {
    const checkpoint = codec.seal(fix, fixtureRun);
    const bytes = Buffer.from(checkpoint.sealed, 'base64url');
    const iv = bytes.subarray(0, 12);
    const cipher = createCipheriv('aes-256-gcm', key, iv);
    cipher.setAAD(Buffer.from(scope));
    const payload = JSON.stringify({ ...fix, run: fixtureRun });
    const encrypted = Buffer.concat([cipher.update(payload, 'utf8'), cipher.final()]);
    const original = Buffer.concat([iv, encrypted, cipher.getAuthTag()]).toString('base64url');
    assert.equal(checkpoint.sealed, original);
    assert.doesNotThrow(() => codec.verify({ ...checkpoint, sealed: original }, next(), fixtureRun));
    const decipher = createDecipheriv('aes-256-gcm', key, iv);
    decipher.setAAD(Buffer.from(scope));
    decipher.setAuthTag(bytes.subarray(-16));
    assert.equal(Buffer.concat([decipher.update(bytes.subarray(12, -16)), decipher.final()]).toString('utf8'), payload);
    const corrupt = Buffer.from(bytes);
    corrupt[corrupt.length - 1] ^= 1;
    assert.throws(() => codec.verify({ ...checkpoint, sealed: corrupt.toString('base64url') }, next(), fixtureRun), /checkpoint unavailable/u);
  }
});

test("batch checkpoint contexts interoperate with existing ciphertext and retain scope and replay checks", () => {
  const codec = createCheckpointCodec(scope, secret);
  const old = createCheckpointCodec(scope, secret).seal(fix, run);
  assert.doesNotThrow(() => codec.verify(old, next(), run));
  assert.doesNotThrow(() => codec.verify(null, fix, run));
  const sealed = codec.seal(fix, run);
  assert.doesNotThrow(() => createCheckpointCodec(scope, secret).verify(sealed, next(), run));
  assert.notEqual(codec.seal(fix, run).sealed, sealed.sealed);
  assert.throws(() => createCheckpointCodec('other-account', secret).verify(sealed, next(), run), /checkpoint unavailable/u);
  assert.throws(() => createCheckpointCodec(scope, 'L'.repeat(43)).verify(sealed, next(), run), /checkpoint unavailable/u);
  assert.throws(() => codec.verify(sealed, { ...fix, lon: fix.lon + 0.00001 }, run), /Overlapping/u);
  assert.throws(() => codec.verify(sealed, next({ lon: 0 }), run), /Impossible travel/u);
});

test("checkpoint encryption is randomized, scoped, authenticated and not exposed as coordinates", () => {
  const codec = createCheckpointCodec(scope, secret);
  const a = codec.seal(fix, run);
  const b = codec.seal(fix, run);
  assert.deepEqual(Object.keys(a).sort(), ["fingerprint", "sealed", "time", "version"]);
  assert.notEqual(a.sealed, b.sealed);
  assert.doesNotThrow(() => codec.verify(a, fix, run));
  assert.doesNotThrow(() => codec.verify(a, next(), run));
  for (const [s, key] of [["2:other:dataset", secret], ["2:123:other", secret], [scope, "L".repeat(43)]]) {
    assert.throws(() => createCheckpointCodec(s, key).verify(a, next(), run), /checkpoint unavailable/u);
  }
  for (const altered of [{ ...a, time: a.time + 1 }, { ...a, fingerprint: "changed" },
    { ...a, sealed: "A".repeat(a.sealed.length) }, { ...a, version: 1 }, { ...a, sealed: undefined }]) {
    assert.throws(() => codec.verify(altered, next(), run), /checkpoint unavailable/u);
  }
});

test("overlap, altered boundary, clock resets and fast jumps cannot restart credit", () => {
  const codec = createCheckpointCodec(scope, secret);
  const checkpoint = codec.seal(fix, run);
  for (const bad of [{ ...fix, time: fix.time - 1 }, { ...fix, lon: fix.lon + 0.00001 }]) {
    assert.throws(() => codec.verify(checkpoint, bad, run), /Overlapping/u);
  }
  for (const bad of [next({ lon: 0 }), next({ elapsed: 1 }), next({ elapsed: fix.elapsed + 9000 }),
    next({ time: fix.time + 1000, elapsed: fix.elapsed + 1000, speed: 50 })]) {
    assert.throws(() => codec.verify(checkpoint, bad, run), /Impossible travel/u);
  }
  assert.throws(() => codec.verify(checkpoint, next({ lon: 0 }), randomUUID()), /Impossible travel/u);
  // An honest separate drive can follow a flight/reboot. That intervening travel earns no miles.
  assert.doesNotThrow(() => codec.verify(checkpoint,
    next({ lon: -120, time: fix.time + 6 * 3600000, elapsed: 100 }), randomUUID()));
  assert.doesNotThrow(() => codec.verify(null, next(), run));
});

test("impossible acceleration is rejected even when coordinates and reported top speed are bounded", () => {
  const now = Date.now();
  const a = { ...fix, time: now - 1000 };
  const b = { ...a, lon: -74.9995, time: now, elapsed: a.elapsed + 1000, speed: 50 };
  assert.throws(() => parseEvidence(JSON.stringify({ run, nonce: "A".repeat(43), sequence: 0, fixes: [a, b] }), now), /Discontinuous/u);
});
