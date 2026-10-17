import {
  createHash,
  createHmac,
  randomBytes,
  scrypt as scryptCallback,
  timingSafeEqual
} from "node:crypto";
import { promisify } from "node:util";

const scrypt = promisify(scryptCallback);
const USERNAME_RE = /^[A-Za-z0-9_]{3,24}$/u;
const SCRYPT_OPTIONS = Object.freeze({ N: 2 ** 17, r: 8, p: 1, maxmem: 256 * 1024 * 1024 });
const PASSWORD_MIN = 8;
const PASSWORD_MAX = 128;

export function normalizeUsername(value) {
  if (typeof value !== "string") return null;
  const display = value.normalize("NFKC").trim();
  if (!USERNAME_RE.test(display)) return null;
  return { display, key: display.toLowerCase() };
}

export function validatePassword(value) {
  if (typeof value !== "string" || value.length < PASSWORD_MIN || value.length > PASSWORD_MAX * 2) return false;
  // Each code point uses one or two UTF-16 units. This middle range is
  // guaranteed to meet both bounds, including passwords made only of emoji.
  if (value.length >= PASSWORD_MIN * 2 && value.length <= PASSWORD_MAX) return true;
  const length = passwordLength(value);
  return length >= PASSWORD_MIN && length <= PASSWORD_MAX;
}

// Count Unicode code points without materializing the whole password as an
// array. Stop once it cannot satisfy the existing maximum; never normalize it.
function passwordLength(value) {
  let length = 0;
  for (const character of value) if (++length > PASSWORD_MAX) break;
  return length;
}

export function boundedLoginPassword(value) {
  if (typeof value !== "string" || value.length > PASSWORD_MAX * 2) return "";
  // UTF-16 length is an upper bound on code-point length, so most passwords
  // need neither a scan nor an allocation to establish their maximum length.
  return value.length <= PASSWORD_MAX || passwordLength(value) <= PASSWORD_MAX ? value : "";
}

function passwordInput(password, pepper) {
  if (!pepper) return password;
  return createHmac("sha256", pepper)
    .update("roadconquest-password\0", "utf8")
    .update(password, "utf8")
    .digest();
}

export async function hashPassword(password, pepper = "") {
  if (!validatePassword(password)) throw new Error("invalid password");
  const salt = randomBytes(16);
  const hash = await scrypt(passwordInput(password, pepper), salt, 64, SCRYPT_OPTIONS);
  return { salt, hash: Buffer.from(hash) };
}

export async function verifyPassword(password, salt, expectedHash, pepper = "") {
  if (typeof password !== "string" || !Buffer.isBuffer(salt) || salt.length !== 16 ||
      !Buffer.isBuffer(expectedHash) || expectedHash.length !== 64
  ) return false;
  const actual = Buffer.from(await scrypt(passwordInput(password, pepper), salt, 64, SCRYPT_OPTIONS));
  return timingSafeEqual(actual, expectedHash);
}

export function createSessionToken() {
  const token = randomBytes(32).toString("base64url");
  return { token, tokenHash: hashSessionToken(token) };
}

export function hashSessionToken(token) {
  return createHash("sha256").update(token, "utf8").digest();
}
