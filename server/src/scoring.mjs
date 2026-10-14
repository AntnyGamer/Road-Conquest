import { createHash } from "node:crypto";

export class EvidenceError extends Error {
  constructor(message, status = 422) { super(message); this.status = status; }
}
export const evidenceHash = evidence => createHash("sha256").update(evidence, "utf8").digest("base64url");
export const edgeKey = (a, b) => a < b ? `${a}:${b}` : `${b}:${a}`;

export function distance(a, b) {
  const rad = Math.PI / 180;
  const h = Math.sin((b.lat - a.lat) * rad / 2) ** 2 +
    Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin((b.lon - a.lon) * rad / 2) ** 2;
  return 6371008.8 * 2 * Math.asin(Math.sqrt(Math.min(1, h)));
}

// Every value that affects credit is inside the exact UTF-8 string bound to Play Integrity.
// No client mileage, names, road IDs, geometry, or matching results are accepted.
export function parseEvidence(text, now = Date.now()) {
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
        !Number.isSafeInteger(f.time) || now - f.time < -10000 || now - f.time > 150000 ||
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

export async function matchEvidence(fixes, config) {
  const coordinates = fixes.map(f => `${f.lon},${f.lat}`).join(";");
  const url = new URL(`/match/v1/driving/${coordinates}`, config.osrmUrl);
  url.search = new URLSearchParams({
    annotations: "nodes,distance", steps: "false", overview: "false", tidy: "false", gaps: "ignore",
    timestamps: fixes.map(f => Math.floor(f.time / 1000)).join(";"),
    // Match the Android client's turn candidate envelope; scoreMatch still requires a
    // very high-confidence, unambiguous snap before any leaderboard credit is accepted.
    radiuses: fixes.map(f => Math.max(10, f.accuracy)).join(";")
  }).toString();
  return boundedJson(await fetch(url, { redirect: "error", signal: AbortSignal.timeout(8000),
    headers: { Accept: "application/json", Authorization: `Bearer ${config.osrmToken}` } }));
}

export function scoreMatch(root, fixes, catalog, dataVersion) {
  if (root?.code !== "Ok" || root.data_version !== dataVersion || root.matchings?.length !== 1 ||
      root.tracepoints?.length !== fixes.length) throw new EvidenceError("Incomplete or changed road match.");
  const matching = root.matchings[0];
  if (!Number.isFinite(matching.confidence) || matching.confidence < 0.95 || matching.confidence > 1 ||
      matching.legs?.length !== fixes.length - 1) throw new EvidenceError("Ambiguous road match.");
  root.tracepoints.forEach((point, i) => {
    if (!point || point.matchings_index !== 0 || point.waypoint_index !== i ||
        point.alternatives_count !== 0 || !Array.isArray(point.location) || point.location.length !== 2 ||
        !Number.isFinite(point.location[0]) || Math.abs(point.location[0]) > 180 ||
        !Number.isFinite(point.location[1]) || Math.abs(point.location[1]) > 90 ||
        distance(fixes[i], { lon: point.location[0], lat: point.location[1] }) > Math.max(15, fixes[i].accuracy * 2)) {
      throw new EvidenceError("Ambiguous or distant GPS snap.");
    }
  });
  let millimeters = 0;
  const roads = new Map();
  matching.legs.forEach((leg, i) => {
    const nodes = leg.annotation?.nodes;
    const lengths = leg.annotation?.distance;
    if (!Array.isArray(nodes) || !Array.isArray(lengths) || lengths.length > 2048 ||
        nodes.length !== lengths.length + 1 ||
        nodes.some(n => !Number.isSafeInteger(n) || n <= 0) ||
        lengths.some(m => !Number.isFinite(m) || m < 0)) throw new EvidenceError("Invalid road annotations.");
    const meters = lengths.reduce((a, b) => a + b, 0);
    const chord = distance(fixes[i], fixes[i + 1]);
    const seconds = (fixes[i + 1].time - fixes[i].time) / 1000;
    if (seconds <= 0 || !Number.isFinite(leg.distance) || Math.abs(meters - leg.distance) > 1 ||
        meters / seconds > 65 || meters > chord * 3 + 30) throw new EvidenceError("Implausible matched distance.");
    // Stops, GPS jitter, and very slow motion receive no competitive credit.
    if (chord < Math.max(10, fixes[i].accuracy + fixes[i + 1].accuracy) ||
        meters < Math.max(10, fixes[i].accuracy + fixes[i + 1].accuracy) ||
        meters / seconds < 2.5 || Math.max(fixes[i].speed, fixes[i + 1].speed) < 3) return;
    for (let n = 0; n < lengths.length; n += 1) {
      if (lengths[n] === 0) continue;
      const way = catalog.get(edgeKey(nodes[n], nodes[n + 1]));
      if (!way) throw new EvidenceError("Road identity not in the pinned catalog.");
      roads.set(way, (roads.get(way) || 0) + Math.round(lengths[n] * 1000));
    }
    millimeters += Math.round(meters * 1000);
  });
  return { millimeters, roads: [...roads].filter(([, mm]) => mm >= 20000).map(([way]) => way) };
}
