import test from "node:test";
import assert from "node:assert/strict";
import { createHash, generateKeyPairSync, createPublicKey, verify } from "node:crypto";
import { createBackups, backupMessage } from "../src/backups.mjs";

const { privateKey, publicKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
const env = { BACKUP_SIGNING_PRIVATE_KEY: privateKey.export({ type: "pkcs8", format: "pem" }),
  PLAY_SERVICE_ACCOUNT_JSON: "{}", PLAY_CERTIFICATES: "A".repeat(43), PLAY_VERSION_CODES: "71",
  PLAY_CLOUD_PROJECT_NUMBER: "123456789" };
const hash = createHash("sha256").update("unchanged backup").digest("base64url");
const body = { hash, integrity_token: "official-app-token-12345" };

test("backup signatures bind exact contents and attestation without accessing accounts or scores", async () => {
  const checks = [];
  const service = await createBackups(env, { verify: async (...args) => checks.push(args) });
  const status = service.status();
  assert.equal(status.available, true);
  assert.equal(status.cloud_project, env.PLAY_CLOUD_PROJECT_NUMBER);
  const seal = await service.sign(body);
  assert.deepEqual(checks, [[body.integrity_token, createHash("sha256").update(backupMessage(hash)).digest("base64url")]]);
  assert.equal(seal.key_id, status.keys[0].id);
  const key = createPublicKey({ key: Buffer.from(status.keys[0].spki, "base64url"), format: "der", type: "spki" });
  assert.equal(verify("sha256", backupMessage(hash), key, Buffer.from(seal.signature, "base64url")), true);
  const changedHash = createHash("sha256").update("edited backup").digest("base64url");
  assert.equal(verify("sha256", backupMessage(changedHash), key, Buffer.from(seal.signature, "base64url")), false);
  assert.equal(verify("sha256", Buffer.from(hash), key, Buffer.from(seal.signature, "base64url")), false);
});

test("invalid or failed app verification never obtains a backup signature", async () => {
  let attempts = 0;
  const service = await createBackups(env, { verify: async () => { attempts++; throw new Error("app verification failed"); } });
  for (const candidate of [{}, { ...body, hash: "x" }, { ...body, integrity_token: "" }, { ...body, extra: true }]) {
    await assert.rejects(service.sign(candidate), /required/u);
  }
  assert.equal(attempts, 0);
  await assert.rejects(service.sign(body), /app verification failed/u);
  assert.equal(attempts, 1);
});

test("verification keys survive signing-key rotation and signing can be disabled safely", async () => {
  const spki = publicKey.export({ type: "spki", format: "der" }).toString("base64url");
  const service = await createBackups({ BACKUP_VERIFICATION_KEYS_JSON: JSON.stringify([spki]) });
  assert.equal(service.status().available, false);
  assert.equal(service.status().keys[0].spki, spki);
  await assert.rejects(service.sign(body), /unavailable/u);
  assert.deepEqual((await createBackups({})).status(), { available: false, keys: [] });
  await assert.rejects(createBackups({ BACKUP_SIGNING_PRIVATE_KEY: env.BACKUP_SIGNING_PRIVATE_KEY }), /Integrity/u);
  const wrong = generateKeyPairSync("ec", { namedCurve: "secp384r1" });
  await assert.rejects(createBackups({ ...env, BACKUP_SIGNING_PRIVATE_KEY: wrong.privateKey.export({ type: "pkcs8", format: "pem" }) }), /P-256/u);
});
