import test from "node:test";
import assert from "node:assert/strict";
import { createHash, generateKeyPairSync, createPublicKey, verify } from "node:crypto";
import { createBackups, backupMessage } from "../src/backups.mjs";
import { readFile } from "node:fs/promises";

const { privateKey, publicKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
const env = { BACKUP_SIGNING_PRIVATE_KEY: privateKey.export({ type: "pkcs8", format: "pem" }),
  PLAY_SERVICE_ACCOUNT_JSON: "{}", PLAY_CERTIFICATES: "A".repeat(43), PLAY_VERSION_CODES: "71",
  PLAY_CLOUD_PROJECT_NUMBER: "123456789" };
const hash = createHash("sha256").update("unchanged backup").digest("base64url");
const body = { hash, integrity_token: "official-app-token-12345" };

test("Node and Android share a server-generated P-256 signature fixture", async () => {
  const fixture = JSON.parse(await readFile(new URL("../../app/src/test/resources/backup-signature.json", import.meta.url), "utf8"));
  const key = createPublicKey({ key: Buffer.from(fixture.spki, "base64url"), format: "der", type: "spki" });
  assert.equal(verify("sha256", backupMessage(fixture.hash), key, Buffer.from(fixture.signature, "base64url")), true);
});

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

test("overlapping identical backup exports share one verification and return valid bound signatures", async () => {
  let calls = 0, release;
  const pending = new Promise(resolve => { release = resolve; });
  const service = await createBackups(env, { verify: async (token, requestHash) => {
    calls++;
    assert.equal(token, body.integrity_token);
    assert.equal(requestHash, createHash("sha256").update(backupMessage(hash)).digest("base64url"));
    await pending;
  } });
  const requests = Array.from({ length: 6 }, () => service.sign({ ...body }));
  assert.equal(calls, 1);
  release();
  const signatures = await Promise.all(requests);
  for (const seal of signatures) {
    assert.equal(verify("sha256", backupMessage(hash), publicKey, Buffer.from(seal.signature, "base64url")), true);
    assert.equal(verify("sha256", backupMessage(createHash("sha256").update("edited").digest("base64url")),
      publicKey, Buffer.from(seal.signature, "base64url")), false);
  }
  await service.sign(body);
  assert.equal(calls, 2, "A completed operation must verify again");
});

test("backup deduplication isolates fingerprints, tokens and instances and clears failures", async () => {
  let calls = 0, release, failed = true;
  const pending = new Promise(resolve => { release = resolve; });
  const verifier = async () => { calls++; await pending; if (failed) throw Error("verification failed"); };
  const first = await createBackups(env, { verify: verifier });
  const second = await createBackups(env, { verify: verifier });
  const requests = [first.sign(body), first.sign({ ...body }),
    first.sign({ ...body, hash: createHash("sha256").update("different").digest("base64url") }),
    first.sign({ ...body, integrity_token: body.integrity_token + "2" }), second.sign(body)];
  assert.equal(calls, 4);
  const settled = Promise.allSettled(requests);
  release();
  assert.ok((await settled).every(result => result.status === "rejected"));
  failed = false;
  await first.sign(body);
  assert.equal(calls, 5, "Failed shared work must not poison an ordinary retry");
});

test("backup pending capacity preserves active sharing and ordinary overflow verification", async () => {
  let calls = 0, release;
  const pending = new Promise(resolve => { release = resolve; });
  const service = await createBackups(env, { verify: async () => { calls++; await pending; } });
  const inputs = Array.from({ length: 33 }, (_, i) => ({ ...body,
    hash: createHash("sha256").update("backup " + i).digest("base64url") }));
  const requests = inputs.map(input => service.sign(input));
  requests.push(service.sign(inputs[0]), service.sign(inputs[32]));
  assert.equal(calls, 34, "Tracked work shares; overflow follows ordinary verification");
  release();
  assert.equal((await Promise.all(requests)).length, 35);
  await service.sign(inputs[0]);
  assert.equal(calls, 35);
});
