import { readFile } from "node:fs/promises";
import { createSign, createPrivateKey } from "node:crypto";
import { boundedJson, EvidenceError, verifyIntegrityVerdict } from "./scoring.mjs";

export function playIntegrityConfig(env) {
  const config = {
    credentialsFile: env.PLAY_SERVICE_ACCOUNT_FILE, credentialsJson: env.PLAY_SERVICE_ACCOUNT_JSON,
    packageName: "com.roadconquest.app", certificates: (env.PLAY_CERTIFICATES || "").split(",").filter(Boolean),
    versions: (env.PLAY_VERSION_CODES || "").split(",").filter(Boolean), cloudProject: env.PLAY_CLOUD_PROJECT_NUMBER
  };
  if ((!config.credentialsFile && !config.credentialsJson) ||
      !config.certificates.length || config.certificates.some(c => !/^[A-Za-z0-9_-]{43}$/u.test(c)) ||
      !config.versions.length || config.versions.some(v => !/^[1-9][0-9]*$/u.test(v) || !Number.isSafeInteger(Number(v)) || Number(v) < 71) ||
      !/^[1-9][0-9]{5,14}$/u.test(config.cloudProject || "")) {
    throw new Error("Production Play Integrity configuration is required");
  }
  return config;
}

// Credentials are a server-only service-account file, never an APK property or a database row.
export async function createIntegrityVerifier(config, request = fetch) {
  const credentials = config.credentialsJson
    ? JSON.parse(config.credentialsJson)
    : JSON.parse(await readFile(config.credentialsFile, "utf8"));
  if (credentials.type !== "service_account" || !credentials.client_email || !credentials.private_key) {
    throw new Error("A Play Integrity service account is required");
  }
  let cached;
  let inFlight;
  let signingKey;
  async function accessToken() {
    if (cached && cached.expires > Date.now() + 60000) return cached.token;
    if (inFlight) return inFlight;
    inFlight = (async () => {
      const now = Math.floor(Date.now() / 1000);
      const encode = obj => Buffer.from(JSON.stringify(obj)).toString("base64url");
      const claims = `${encode({ alg: "RS256", typ: "JWT" })}.${encode({
        iss: credentials.client_email, scope: "https://www.googleapis.com/auth/playintegrity",
        aud: "https://oauth2.googleapis.com/token", iat: now, exp: now + 3600
      })}`;
      signingKey ??= createPrivateKey(credentials.private_key);
      const signature = createSign("RSA-SHA256").update(claims).sign(signingKey, "base64url");
      const json = await boundedJson(await request("https://oauth2.googleapis.com/token", {
        method: "POST", redirect: "error", signal: AbortSignal.timeout(8000),
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: `${claims}.${signature}` })
      }), 32768);
      if (typeof json?.access_token !== "string" || !json.access_token || !Number.isFinite(json.expires_in)) throw new EvidenceError("Integrity unavailable.", 503);
      cached = { token: json.access_token, expires: Date.now() + json.expires_in * 1000 };
      return cached.token;
    })();
    try { return await inFlight; } finally { inFlight = null; }
  }
  return async (token, hash) => {
    if (typeof token !== "string" || token.length < 20 || token.length > 16000) throw new EvidenceError("Integrity token required.", 403);
    const access = await accessToken();
    const response = await request(
      `https://playintegrity.googleapis.com/v1/${config.packageName}:decodeIntegrityToken`, {
        method: "POST", redirect: "error", signal: AbortSignal.timeout(8000),
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${access}` },
        body: JSON.stringify({ integrity_token: token })
      }
    );
    // Retry with fresh authorization after a rejected OAuth credential. A slow
    // old-token failure must not invalidate a newer concurrent refresh.
    if (response.status === 401 && cached?.token === access) cached = null;
    // Google returns 400 for a malformed/invalid token. Retrying that token as
    // a service outage wastes requests; credential/quota/server failures stay retryable.
    if (response.status === 400) {
      void response.body?.cancel().catch(() => {});
      throw new EvidenceError("App/device verification failed.", 403);
    }
    const payload = await boundedJson(response, 32768);
    verifyIntegrityVerdict(payload.tokenPayloadExternal, hash, config);
  };
}
