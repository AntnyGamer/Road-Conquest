import test from "node:test";
import assert from "node:assert/strict";
import {
  boundedLoginPassword,
  createSessionToken,
  hashPassword,
  hashSessionToken,
  normalizeUsername,
  validatePassword,
  verifyPassword
} from "../src/security.mjs";

test("usernames are ASCII-only, normalized, and case-insensitive for uniqueness", () => {
  assert.deepEqual(normalizeUsername("  Road_User9  "), { display: "Road_User9", key: "road_user9" });
  assert.equal(normalizeUsername("road_user9").key, normalizeUsername("ROAD_USER9").key);
  assert.equal(normalizeUsername("ab"), null);
  assert.equal(normalizeUsername("road-user"), null);
  assert.equal(normalizeUsername("rоaduser"), null); // Contains Cyrillic о.
});

test("password policy accepts eight or more characters without composition rules", () => {
  assert.equal(validatePassword("1234567"), false);
  assert.equal(validatePassword("12345678"), true);
  assert.equal(validatePassword("correct horse battery staple"), true);
  assert.equal(validatePassword("x".repeat(128)), true);
  assert.equal(validatePassword("x".repeat(129)), false);
});

test("bounded password checks preserve Unicode code-point limits and the exact login value", () => {
  const values=[undefined,null,12,{},'x'.repeat(32000)];
  for(let length=0;length<=300;length++) {
    for(const unit of ['a','😀','a😀','\ud800','\udc00'])values.push(unit.repeat(length));
  }
  for(const value of values) {
    const length=typeof value==='string'?Array.from(value).length:-1;
    assert.equal(validatePassword(value),length>=8&&length<=128);
    assert.equal(boundedLoginPassword(value),length>=0&&length<=128?value:'');
  }
});

test("password hashes are salted, peppered, and verified with constant-time comparison", async () => {
  const password = "correct horse battery staple";
  const pepper = "test-pepper-that-is-long-enough-for-security-tests";
  const first = await hashPassword(password, pepper);
  const second = await hashPassword(password, pepper);
  assert.notDeepEqual(first.salt, second.salt);
  assert.notDeepEqual(first.hash, second.hash);
  assert.equal(await verifyPassword(password, first.salt, first.hash, pepper), true);
  assert.equal(await verifyPassword("wrong password that is long enough", first.salt, first.hash, pepper), false);
  assert.equal(await verifyPassword(password, first.salt, first.hash, "different-pepper"), false);
});

test("session tokens are random and only their hashes need storage", () => {
  const first = createSessionToken();
  const second = createSessionToken();
  assert.notEqual(first.token, second.token);
  assert.deepEqual(first.tokenHash, hashSessionToken(first.token));
});

test("scrypt returns its owned 64-byte result without copying password hashes", async () => {
  const from = Buffer.from;
  let copies = 0;
  Buffer.from = function (value, ...args) {
    if (Buffer.isBuffer(value) && value.length === 64) copies++;
    return from.call(this, value, ...args);
  };
  let result;
  try {
    result = await hashPassword('Unicode password 🛣️', 'local-test-pepper');
    assert.equal(await verifyPassword('Unicode password 🛣️', result.salt, result.hash, 'local-test-pepper'), true);
    assert.equal(await verifyPassword('different password', result.salt, result.hash, 'local-test-pepper'), false);
  } finally { Buffer.from = from; }
  assert.equal(Buffer.isBuffer(result.hash), true);
  assert.equal(result.hash.length, 64);
  assert.equal(result.salt.length, 16);
  assert.equal(copies, 0);
});
