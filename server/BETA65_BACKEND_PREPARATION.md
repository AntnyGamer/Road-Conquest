# Beta 65 backend preparation (no deployment performed)

Target: Neon project `young-forest-18948389`, production branch
`br-withered-dew-b7u432kv`, existing function `roadconquest`, runtime `nodejs24`.
Android: `com.roadconquest.app`, `1.0-beta.65`, versionCode **91**.
This is a preparation/runbook document. Production deployment, secret changes,
merging, tags, GitHub releases and Play uploads require the owner's later instruction.

## Observed deployment state, October 27, 2026

Read-only Neon metadata identifies production deployment 32 (October 26), with the
existing account, billing, notification, Integrity and rate-limit environment names.
Neither `BACKUP_SIGNING_PRIVATE_KEY` nor `BACKUP_VERIFICATION_KEYS_JSON` is present.
No secret values were retrieved. The actual public Android API's `/health/live`
returns `200 {"ok":true}` without a revision, and `/v1/backups` returns 404. Thus the
protected-backup service is **not deployed** through the public ingress.

The existing `forum-billing-check-20261026` staging branch
(`br-late-waterfall-b7mbuwsk`) has a disabled database endpoint; its function URL
returns HTTP 410. It was not enabled, redeployed or changed. Native PostgreSQL tests
run against a new disposable localhost database instead. Production inspection was
limited to metadata and `information_schema` definitions, without reading user rows.

## Generate and retain the signing key

1. Use a trusted machine and an encrypted disk or secrets manager. Prepare a private
   parent directory **outside this repository and synced/shared folders**. On Windows,
   restrict its NTFS ACL to the operator and necessary administrators before use;
   Node's Unix mode bits do not enforce Windows ACLs. On Unix, use a private parent
   with mode 0700. Keep terminal/session recording away from secret entry.
2. From `server/`, run `node tools/generate-backup-key.mjs <new-private-directory>`.
   The directory must not exist. The tool creates a P-256 (`prime256v1`) key in
   unencrypted PKCS#8 PEM form as `backup-signing-private.pem`, plus public SPKI DER
   encoded as unpadded base64url in `backup-verification-key.json`. It prints only
   the public SHA-256 key ID. It never overwrites existing output. Unix directory
   and file modes are 0700/0600; files are flushed before success is reported.
   If generation is interrupted, inspect the partial directory securely and use a
   different new directory; do not discard the only saved private key blindly.
3. Store a durable encrypted/private copy and the matching public JSON. The PEM
   itself has no passphrase because the current runtime expects a secret value;
   storage/access protection is essential. Do not commit it, attach it to an issue,
   paste it into a command line, put it in an APK, or include it in the function ZIP.
4. At the separately authorized deployment step, supply the **entire PEM including
   real newlines and BEGIN/END lines** as `BACKUP_SIGNING_PRIVATE_KEY` through trusted
   secret input. Do not put literal backslash-n escapes in its place. Do not reuse
   an APK signing key, Google service-account key, password pepper, ingress HMAC key,
   or verified-evidence encryption key. Only synthetic test keys were generated
   during this preparation; no production key was generated or installed.

## Preserve the existing environment and accounts

Keep `PASSWORD_PEPPER`, `VERIFIED_EVIDENCE_KEY`, `RATE_LIMIT_IP_SIGNATURE_KEY`,
`DATABASE_URL`/`DATABASE_SSL`, all existing `PLAY_*`, `FORUM_*`, `FCM_*`,
`UV_THREADPOOL_SIZE` and feature settings unchanged except for explicitly reviewed
additions. Changing the pepper breaks password verification, purchase-token
decryption and account bindings. Changing the evidence key breaks stored checkpoints.
Use the existing function and ingress. No new identity provider, account migration,
database reset, receipt migration or server schema change is needed by this PR.
The production account/session, receipt/profile and cell-scoring column definitions
were present during read-only inspection. The Android database independently upgrades
to version 16, preserving old rows and adding local provenance flags.

Retain `NODE_ENV=production`, `REQUIRE_HTTPS=1`, `TRUST_PROXY=1`,
`APPLY_SCHEMA_ON_STARTUP=0` and verified database TLS. Do not run schema setup as
part of this release. Runtime SQL continues to use the existing privileges and
PostgreSQL MVCC `xmin`; no MVCC version is persisted as a long-term identifier.

The signer uses the existing **PLAY_SERVICE_ACCOUNT_JSON** (or supported file form),
not `FORUM_PLAY_SERVICE_ACCOUNT_JSON`. It works with `LEADERBOARDS_ENABLED` off.
The configured Google account needs Play Integrity access in the linked Cloud
project. Forum Plus also needs Android Publisher access, and the existing RTDN
audience/email must remain configured. Using the same approved account is supported;
OAuth tokens retain distinct Integrity/Publisher scopes.

Ensure `PLAY_VERSION_CODES` includes **91 while retaining all supported older codes**.
`PLAY_CERTIFICATES` must contain the base64url SHA-256 **Play app-signing** certificate,
not the upload/debug certificate. Preserve the linked numeric
`PLAY_CLOUD_PROJECT_NUMBER`. Strong/device integrity, `PLAY_RECOGNIZED`, `LICENSED`,
freshness and exact request-hash binding remain required. Local/emulator tests cannot
prove real Google permissions, device eligibility or a Play-installed build's verdict.

Use a private environment file or securely injected process environment to run:

```text
node --env-file=<private-env-file> tools/check-backup-config.mjs 91
```

The file must be outside source control with restricted access; Node supports quoted
multiline PEM values. This offline preflight validates the production startup flags,
version allowlist, credential JSON/RSA key, current P-256 key and previous public keys.
It outputs only availability, version, public Cloud project number and public key IDs.
It makes no Google/database/network calls and cannot certify live service permissions.
Its fixed error messages never print JSON parser snippets or private keys.

## Prepare the reviewed artifact

From the clean reviewed PR commit, run `npm ci --ignore-scripts --no-audit --no-fund`,
`npm test`, then `npm run build:function`. Package **only** `dist/index.mjs` as root
`index.mjs` in the function ZIP. Retain its SHA-256 and the source commit. The build
embeds that Git revision; an exported source archive can supply the full commit via
`ROADCONQUEST_SERVER_REVISION`. No environment file or signing material belongs in it.
The code requires Node 22+ for tests and targets Neon's Node 24 runtime.

For the later deployment, preserve existing environment values. Neon documents that
unmentioned variables carry over, supplied values update them, and empty assignments
delete them. Never turn a missing local setting into an empty deployed value. A
prebuilt bundle should be shipped without rebundling (`--no-bundle` in the current
Neon CLI) or as the reviewed ZIP via the API. Explicitly select the project, branch
and existing slug; do not rely on a default branch or an old linked context.

Reference: [Neon deployment](https://neon.com/docs/compute/functions/deploy) and
[environment-variable semantics](https://neon.com/docs/compute/functions/environment-variables).
No deployment command was executed during this review.

## Verification and rotation

After an explicitly authorized deployment, compare `/health/live` through the
actual Cloudflare public account URL with the artifact's source revision. Check
`GET /v1/backups`: `available:true`, the expected Cloud project and expected public
key ID/SPKI. These reads require no database connection. Check ordinary login and
Forum Plus restore with dedicated test accounts; never reset existing accounts.

On an eligible, licensed Play-installed Beta 65 build, export and restore unchanged
data, transfer the protected ZIP to another installation, fetch keys once there,
then repeat restore offline. Check tamper/repack/stripped/unsigned rejection, private
same-installation recovery, interrupted restore, and fresh versus restored weekly
credit. The signer receives only a digest and attestation, never the ZIP/GPS history.
Android permits 30 seconds for the signing response, covering the two sequential
8-second Google requests plus database/ingress overhead. This changes only that call.

Before rotating, add the retiring public JSON's **spki string** to
`BACKUP_VERIFICATION_KEYS_JSON`, a JSON array of at most seven previous public SPKI
strings, while installing the new private key. Keep every still-supported previous
public key; the endpoint publishes up to eight total keys. Key IDs are derived from
SPKI SHA-256. Test restoration of a backup signed by the retired key on a new install
and on a phone with cached keys. To disable new signing while retaining restores,
keep the former active public key in this array when removing the private key.
Do not assume redeploying an older bundle preserves the new backup endpoints.

This is key rotation/retention, not a compromised-key revocation protocol: clients
can verify offline with previously cached trusted keys. A compromise needs a separate
client/server recovery decision. The local pre-restore HMAC key remains installation
specific and excluded from exports and Android backup.

## Remaining owner-controlled release checks

- Approve and perform backend/key/version configuration and verify the public revision.
- Complete real Play-installed backup transfer/restore and physical driving checks.
- Complete actual Forum Plus checkout, duplicate callbacks, restore, pending payment,
  refund and authenticated RTDN delivery checks. Mocked provider tests are not a real
  purchase or proof of current Play permissions.
- Confirm versionCode 91 is unused in Play Console; preserve the existing application
  ID and release signing identity. Sign the final AAB/APK using the existing private
  release workflow only after authorization.
- Confirm the intentional compatibility change: old unsigned progress backups are
  rejected. Existing installed progress/accounts/purchases stay usable; previously
  restored rows cannot be retroactively identified as imported.

Until these checks are complete, Beta 65 is **not ready for general distribution**,
even when every CI job passes. PR #122 must remain open and unmerged until instructed.
