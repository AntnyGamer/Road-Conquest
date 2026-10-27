import { createHash, createPrivateKey, createPublicKey, sign } from "node:crypto";
import { createIntegrityVerifier, playIntegrityConfig } from "./integrity.mjs";
import { EvidenceError } from "./scoring.mjs";

export const backupMessage = hash => Buffer.from(`RoadConquestBackup/v1\n${hash}`, "utf8");

function publicEntry(key) {
  if (key.asymmetricKeyType !== "ec" || key.asymmetricKeyDetails?.namedCurve !== "prime256v1") {
    throw new Error("Backup keys must use P-256");
  }
  const spki = key.export({ type: "spki", format: "der" });
  return { id: createHash("sha256").update(spki).digest("base64url"), spki: spki.toString("base64url") };
}

/** Independent of competition: restoring/exporting never reads or writes scores. */
export async function createBackups(env, dependencies = {}) {
  let privateKey;
  let current;
  const keys = new Map();
  const pendingSignatures = new Map();
  // Retain old PUBLIC keys so key rotation does not make existing backups unusable.
  const oldKeys = JSON.parse(env.BACKUP_VERIFICATION_KEYS_JSON || "[]");
  if (!Array.isArray(oldKeys) || oldKeys.length > 7) throw new Error("Invalid backup verification keys");
  for (const spki of oldKeys) {
    if (typeof spki !== "string" || spki.length > 512) throw new Error("Invalid backup verification key");
    const entry = publicEntry(createPublicKey({ key: Buffer.from(spki, "base64url"), type: "spki", format: "der" }));
    keys.set(entry.id, entry);
  }
  let config;
  let verify;
  if (env.BACKUP_SIGNING_PRIVATE_KEY) {
    privateKey = createPrivateKey(env.BACKUP_SIGNING_PRIVATE_KEY);
    current = publicEntry(createPublicKey(privateKey));
    keys.set(current.id, current);
    config = playIntegrityConfig(env);
    verify = dependencies.verify || await createIntegrityVerifier(config);
  }
  return {
    status: () => ({ available: !!privateKey, keys: [...keys.values()],
      ...(config ? { cloud_project: config.cloudProject } : {}) }),
    async sign(body) {
      if (!privateKey) throw new EvidenceError("Protected backup signing is unavailable.", 503);
      if (!body || Object.keys(body).sort().join(",") !== "hash,integrity_token" ||
          typeof body.hash !== "string" || !/^[A-Za-z0-9_-]{43}$/u.test(body.hash) ||
          Buffer.from(body.hash, "base64url").toString("base64url") !== body.hash ||
          typeof body.integrity_token !== "string" || body.integrity_token.length < 20 || body.integrity_token.length > 16000) {
        throw new EvidenceError("Backup fingerprint and app verification are required.", 400);
      }
      const message = backupMessage(body.hash);
      const requestHash = createHash("sha256").update(message).digest("base64url");
      // Identical concurrent exports need one bound verdict, not competing
      // decodes of a replay-protected token. Never retain completed verdicts.
      // The HTTP boundary still reserves its normal budget for every request.
      const key = createHash("sha256").update(JSON.stringify([requestHash, body.integrity_token])).digest("hex");
      if (pendingSignatures.has(key)) return pendingSignatures.get(key);
      const task = (async () => {
        await verify(body.integrity_token, requestHash);
        return { version: 1, kind: "server-p256", key_id: current.id,
          signature: sign("sha256", message, privateKey).toString("base64url") };
      })();
      // Keep ordinary verification at capacity without evicting active work.
      if (pendingSignatures.size >= 32) return task;
      pendingSignatures.set(key, task);
      try { return await task; } finally { pendingSignatures.delete(key); }
    }
  };
}
