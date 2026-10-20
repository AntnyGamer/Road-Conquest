import { createCipheriv, createDecipheriv, createHmac, randomBytes } from "node:crypto";
import { distance, EvidenceError } from "./scoring.mjs";

// Keep only the latest accepted fix, encrypted and bound to its account/map.
// Public responses and retry receipts never contain the checkpoint.
function keyFor(secret, scope) {
  return createHmac("sha256", secret).update("roadconquest-checkpoint-v2\0").update(scope).digest();
}
function fingerprint(fix, key) {
  return createHmac("sha256", key).update(JSON.stringify([
    fix.lat, fix.lon, fix.time, fix.elapsed, fix.accuracy, fix.speed, fix.mock
  ])).digest("base64url");
}

export function sealCheckpoint(fix, run, scope, secret) {
  return createCheckpointCodec(scope, secret).seal(fix, run);
}

export function verifyContinuity(checkpoint, first, run, scope, secret) {
  if (checkpoint) createCheckpointCodec(scope, secret).verify(checkpoint, first, run);
}

// Keep this context local to one batch: reuse its derived key and AAD for
// continuity verification and sealing, without caching account keys globally.
export function createCheckpointCodec(scope, secret) {
  const key = keyFor(secret, scope);
  const aad = Buffer.from(scope);
  return {
    seal: (fix, run) => seal(fix, run, key, aad),
    verify: (checkpoint, first, run) => verify(checkpoint, first, run, key, aad)
  };
}

function seal(fix, run, key, aad) {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  cipher.setAAD(aad);
  const encrypted = cipher.update(JSON.stringify({ ...fix, run }), "utf8");
  const tail = cipher.final();
  return { version: 2, time: fix.time, fingerprint: fingerprint(fix, key),
    sealed: Buffer.concat([iv, encrypted, tail, cipher.getAuthTag()]).toString("base64url") };
}

function verify(checkpoint, first, run, key, aad) {
  if (!checkpoint) return;
  let previous;
  try {
    if (checkpoint.version !== 2 || typeof checkpoint.sealed !== "string" ||
        !/^[A-Za-z0-9_-]{1,1024}$/u.test(checkpoint.sealed)) throw new Error("Invalid checkpoint");
    const bytes = Buffer.from(checkpoint.sealed, "base64url");
    if (bytes.length < 29) throw new Error("Invalid checkpoint");
    const decipher = createDecipheriv("aes-256-gcm", key, bytes.subarray(0, 12));
    decipher.setAAD(aad);
    decipher.setAuthTag(bytes.subarray(-16));
    const plaintext = decipher.update(bytes.subarray(12, -16));
    const tail = decipher.final(); // Authenticate before parsing or using any plaintext.
    previous = JSON.parse((tail.length ? Buffer.concat([plaintext, tail]) : plaintext).toString("utf8"));
    if (previous.time !== checkpoint.time || fingerprint(previous, key) !== checkpoint.fingerprint ||
        !Number.isFinite(previous.lat) || !Number.isFinite(previous.lon)) throw new Error("Invalid checkpoint");
  } catch {
    // Corrupt state or a wrong deployment key cannot silently erase the anti-replay boundary.
    throw new EvidenceError("Verification checkpoint unavailable.", 503);
  }
  const elapsed = first.time - previous.time;
  if (elapsed < 0 || (elapsed === 0 && fingerprint(first, key) !== checkpoint.fingerprint)) {
    throw new EvidenceError("Overlapping or altered driving evidence.", 409);
  }
  if (elapsed === 0) return;
  const sameRun = previous.run === run;
  // Separate drives may follow air travel; gaps themselves never earn mileage.
  // In one live drive require driving speed and a consistent monotonic clock.
  const maximumSpeed = sameRun ? 65 : 350;
  const allowance = previous.accuracy + first.accuracy + 10;
  if (distance(previous, first) > maximumSpeed * elapsed / 1000 + allowance ||
      (sameRun && (first.elapsed <= previous.elapsed ||
        Math.abs(first.elapsed - previous.elapsed - elapsed) > 1000 ||
        Math.abs(first.speed - previous.speed) / (elapsed / 1000) > 12))) {
    throw new EvidenceError("Impossible travel between verified batches.", 422);
  }
}
