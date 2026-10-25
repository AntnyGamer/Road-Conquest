import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import { createIntegrityVerifier } from "../src/integrity.mjs";

const privateKey = generateKeyPairSync("rsa", { modulusLength: 2048 }).privateKey
  .export({ type: "pkcs8", format: "pem" });
const config = {
  credentialsJson: JSON.stringify({ type: "service_account", client_email: "verifier@example.iam.gserviceaccount.com", private_key: privateKey }),
  packageName: "com.roadconquest.app", versions: ["71"], certificates: ["A".repeat(43)]
};

test("invalid Google tokens are permanent failures while credential, quota and server outages remain retryable", async () => {
  for (const [upstream, expected] of [[400, 403], [401, 503], [403, 503], [429, 503], [500, 503]]) {
    const verify = await createIntegrityVerifier(config, async url => url === "https://oauth2.googleapis.com/token"
      ? Response.json({ access_token: "test-only-access-token", expires_in: 3600 })
      : Response.json({ error: {} }, { status: upstream }));
    await assert.rejects(verify("test-only-integrity-token", "hash"), error => error.status === expected);
  }
  const badCredentials = await createIntegrityVerifier(config, async () => Response.json({}, { status: 400 }));
  await assert.rejects(badCredentials("test-only-integrity-token", "hash"), error => error.status === 503);
});

test("concurrent verification shares OAuth refreshes and verifies each bound Google verdict", async () => {
  let oauthCalls = 0;
  const verify = await createIntegrityVerifier(config, async url => {
    if (url === "https://oauth2.googleapis.com/token") {
      oauthCalls++;
      return Response.json({ access_token: "test-only-access-token", expires_in: 3600 });
    }
    assert.equal(url, "https://playintegrity.googleapis.com/v1/com.roadconquest.app:decodeIntegrityToken");
    return Response.json({ tokenPayloadExternal: {
      requestDetails: { requestHash: "hash", requestPackageName: config.packageName, timestampMillis: String(Date.now()) },
      appIntegrity: { appRecognitionVerdict: "PLAY_RECOGNIZED", packageName: config.packageName,
        versionCode: "71", certificateSha256Digest: config.certificates },
      accountDetails: { appLicensingVerdict: "LICENSED" },
      deviceIntegrity: { deviceRecognitionVerdict: ["MEETS_DEVICE_INTEGRITY", "MEETS_STRONG_INTEGRITY"] }
    } });
  });
  await Promise.all([verify("test-only-integrity-token", "hash"), verify("test-only-integrity-token", "hash")]);
  assert.equal(oauthCalls, 1);
  await assert.rejects(verify("test-only-integrity-token", "changed"), error => error.status === 403);
  assert.equal(oauthCalls, 1);
});

test('rejected Integrity OAuth credentials refresh on retry without a delayed failure discarding the new token', async () => {
  let oauth = 0;
  const started = Promise.withResolvers(), delayed = Promise.withResolvers();
  const verify = await createIntegrityVerifier(config, async (url, init) => {
    if (url === 'https://oauth2.googleapis.com/token') {
      return Response.json({ access_token: 'access-' + (++oauth), expires_in: 3600 });
    }
    const token = JSON.parse(init.body).integrity_token;
    if (token === 'slow-test-integrity-token') { started.resolve(); return delayed.promise; }
    if (token === 'rejected-test-integrity-token') return Response.json({}, { status: 401 });
    assert.equal(init.headers.Authorization, 'Bearer access-2');
    return Response.json({ tokenPayloadExternal: {
      requestDetails: { requestHash: 'hash', requestPackageName: config.packageName, timestampMillis: String(Date.now()) },
      appIntegrity: { appRecognitionVerdict: 'PLAY_RECOGNIZED', packageName: config.packageName,
        versionCode: '71', certificateSha256Digest: config.certificates },
      accountDetails: { appLicensingVerdict: 'LICENSED' },
      deviceIntegrity: { deviceRecognitionVerdict: ['MEETS_DEVICE_INTEGRITY', 'MEETS_STRONG_INTEGRITY'] }
    } });
  });
  const slow = assert.rejects(verify('slow-test-integrity-token', 'hash'), e => e.status === 503);
  await started.promise;
  await assert.rejects(verify('rejected-test-integrity-token', 'hash'), e => e.status === 503);
  await verify('fresh-test-integrity-token', 'hash');
  delayed.resolve(Response.json({}, { status: 401 }));
  await slow;
  await verify('fresh-again-test-integrity-token', 'hash');
  assert.equal(oauth, 2);
});
