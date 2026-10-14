import { createHash } from "node:crypto";

export class EvidenceError extends Error {
  constructor(message, status = 422) { super(message); this.status = status; }
}
export const evidenceHash = evidence => createHash("sha256").update(evidence, "utf8").digest("base64url");

export function distance(a, b) {
  const rad = Math.PI / 180;
  const h = Math.sin((b.lat - a.lat) * rad / 2) ** 2 +
    Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin((b.lon - a.lon) * rad / 2) ** 2;
  return 6371008.8 * 2 * Math.asin(Math.sqrt(Math.min(1, h)));
}

// Every value that affects credit is inside the exact UTF-8 string bound to Play Integrity.
// No client cell IDs, totals, geometry, or restored progress are accepted.
export function parseEvidence(text, now = Date.now(), requireFresh = true) {
  if (typeof text !== "string" || Buffer.byteLength(text) > 12000) throw new EvidenceError("Invalid evidence.");
  let body;
  try { body = JSON.parse(text); } catch { throw new EvidenceError("Invalid evidence."); }
  if (!body || Object.keys(body).sort().join(",") !== "fixes,nonce,run,sequence" ||
      typeof body.run !== "string" || !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/u.test(body.run) ||
      typeof body.nonce !== "string" || !/^[A-Za-z0-9_-]{43}$/u.test(body.nonce) ||
      !Number.isSafeInteger(body.sequence) || body.sequence < 0 ||
      !Array.isArray(body.fixes) || body.fixes.length < 2 || body.fixes.length > 32) {
    throw new EvidenceError("Invalid evidence.");
  }
  for (let i = 0; i < body.fixes.length; i += 1) {
    const f = body.fixes[i];
    if (!f || Object.keys(f).sort().join(",") !== "accuracy,elapsed,lat,lon,mock,speed,time" ||
        !Number.isFinite(f.lat) || Math.abs(f.lat) > 90 || !Number.isFinite(f.lon) || Math.abs(f.lon) > 180 ||
        !Number.isFinite(f.accuracy) || f.accuracy <= 0 || f.accuracy > 25 ||
        !Number.isFinite(f.speed) || f.speed < 0 || f.speed > 65 || f.mock !== false ||
        !Number.isSafeInteger(f.time) || f.time < 0 ||
        (requireFresh && (now - f.time < -10000 || now - f.time > 150000)) ||
        !Number.isSafeInteger(f.elapsed) || f.elapsed < 0) throw new EvidenceError("Poor or stale GPS evidence.");
    if (i > 0) {
      const p = body.fixes[i - 1];
      const dt = f.time - p.time;
      if (dt < 1000 || dt > 15000 || f.elapsed <= p.elapsed || Math.abs(f.elapsed - p.elapsed - dt) > 1000 ||
          distance(p, f) / (dt / 1000) > 65 ||
          Math.abs(f.speed - p.speed) / (dt / 1000) > 12) throw new EvidenceError("Discontinuous GPS evidence.");
    }
  }
  if (body.fixes.at(-1).time - body.fixes[0].time > 120000) throw new EvidenceError("Trace too long.");
  return body;
}

export function verifyIntegrityVerdict(payload, hash, config, now = Date.now()) {
  const request = payload?.requestDetails;
  const app = payload?.appIntegrity;
  const labels = payload?.deviceIntegrity?.deviceRecognitionVerdict;
  const stamp = Number(request?.timestampMillis);
  const recognition = app?.appRecognitionVerdict;
  const licensing = payload?.accountDetails?.appLicensingVerdict;
  if (request?.requestHash !== hash || request?.requestPackageName !== config.packageName ||
      !Number.isSafeInteger(stamp) || now - stamp < -10000 || now - stamp > 120000 ||
      recognition !== "PLAY_RECOGNIZED" || app?.packageName !== config.packageName ||
      !config.versions.includes(String(app?.versionCode)) ||
      !Array.isArray(app?.certificateSha256Digest) || app.certificateSha256Digest.length !== 1 ||
      !config.certificates.includes(app.certificateSha256Digest[0]) ||
      licensing !== "LICENSED" ||
      !Array.isArray(labels) || !labels.includes("MEETS_DEVICE_INTEGRITY") || !labels.includes("MEETS_STRONG_INTEGRITY")) {
    throw new EvidenceError("App/device verification failed.", 403);
  }
}

export async function boundedJson(response, limit = 1024 * 1024) {
  if (!response.ok || !response.body) throw new EvidenceError("Verification service unavailable.", 503);
  let size = 0;
  const chunks = [];
  for await (const chunk of response.body) {
    size += chunk.length;
    if (size > limit) throw new EvidenceError("Verification response too large.", 503);
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString("utf8")); }
  catch { throw new EvidenceError("Invalid verification response.", 503); }
}
