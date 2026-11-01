import { createHmac } from "node:crypto";

// A versioned, dedicated keyring permits deliberate rotation without breaking
// existing account backups. Every old version must remain configured until its
// backups are no longer needed; no backup content is stored on the server.
export function createAccountBackupKeys(env) {
  const source = env.BACKUP_MASTER_KEYS_JSON;
  const current = env.BACKUP_CURRENT_KEY_ID;
  if (!source && !current) return null;
  if (!source || !current) throw new Error("Both BACKUP_MASTER_KEYS_JSON and BACKUP_CURRENT_KEY_ID are required.");
  let supplied;
  try { supplied = JSON.parse(source); }
  catch { throw new Error("Invalid BACKUP_MASTER_KEYS_JSON."); }
  if (!supplied || typeof supplied !== "object" || Array.isArray(supplied) ||
    Object.keys(supplied).length < 1 || Object.keys(supplied).length > 16) {
    throw new Error("Invalid backup keyring.");
  }
  const keys = new Map();
  for (const [id, encoded] of Object.entries(supplied)) {
    if (!/^[A-Za-z0-9_-]{1,32}$/u.test(id) ||
      typeof encoded !== "string" || !/^[A-Za-z0-9_-]{43}$/u.test(encoded)) {
      throw new Error("Invalid backup keyring entry.");
    }
    const key = Buffer.from(encoded, "base64url");
    if (key.length !== 32 || key.toString("base64url") !== encoded) {
      throw new Error("Backup master keys must be canonical 256-bit base64url values.");
    }
    keys.set(id, key);
  }
  if (!keys.has(current)) throw new Error("Current backup key ID is not in the keyring.");
  return {
    derive(userId, requestedId = current) {
      const secret = keys.get(requestedId);
      if (!secret) return null;
      return { key_id: requestedId, key: createHmac("sha256", secret)
        .update("RoadConquestAccountBackup/v2\0", "utf8")
        .update(String(userId), "utf8")
        .digest("base64url") };
    }
  };
}
