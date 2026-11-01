import test from "node:test";
import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { createAccountBackupKeys } from "../src/account-backup-keys.mjs";

const first = randomBytes(32).toString("base64url");
const second = randomBytes(32).toString("base64url");

test("unconfigured account backups fail closed; partial or invalid keyrings are rejected", () => {
  assert.equal(createAccountBackupKeys({}), null);
  assert.throws(() => createAccountBackupKeys({ BACKUP_CURRENT_KEY_ID: "v1" }), /required/u);
  assert.throws(() => createAccountBackupKeys({
    BACKUP_CURRENT_KEY_ID: "v1", BACKUP_MASTER_KEYS_JSON: JSON.stringify({ v1: "bad" })
  }), /Invalid backup keyring entry/u);
  assert.throws(() => createAccountBackupKeys({
    BACKUP_CURRENT_KEY_ID: "v3", BACKUP_MASTER_KEYS_JSON: JSON.stringify({ v1: first })
  }), /not in the keyring/u);
});

test("keys are account-bound, versioned, stable and retain old versions on rotation", () => {
  const old = createAccountBackupKeys({
    BACKUP_CURRENT_KEY_ID: "v1",
    BACKUP_MASTER_KEYS_JSON: JSON.stringify({ v1: first })
  });
  const rotated = createAccountBackupKeys({
    BACKUP_CURRENT_KEY_ID: "v2",
    BACKUP_MASTER_KEYS_JSON: JSON.stringify({ v1: first, v2: second })
  });
  assert.equal(old.derive(12).key_id, "v1");
  assert.equal(rotated.derive(12).key_id, "v2");
  assert.equal(old.derive(12).key, rotated.derive(12, "v1").key);
  assert.notEqual(rotated.derive(12).key, rotated.derive(13).key);
  assert.notEqual(rotated.derive(12).key, rotated.derive(12, "v1").key);
  assert.equal(rotated.derive(12, "missing"), null);
  assert.match(rotated.derive(12).key, /^[A-Za-z0-9_-]{43}$/u);
});
