# Road Conquest account service

This service backs Road Conquest accounts and the leaderboard-privacy preference. Release builds
and debug builds default to the live Road Conquest Neon HTTPS endpoint. Automated tests override
the endpoint to avoid production accounts. Override either build with a Gradle property:

```properties
ROADCONQUEST_ACCOUNT_API_URL=https://accounts.example.com
```

## Security model

- Usernames are 3-24 ASCII letters, numbers, or underscores. They are normalized to lowercase for
  uniqueness, so `DriverOne` and `driverone` cannot be separate accounts.
- PostgreSQL enforces the normalized username with a `UNIQUE` constraint. This is the final
  authority, so simultaneous signup requests cannot create duplicate usernames.
- Passwords are never stored directly. They are HMAC-peppered, independently salted, and hashed
  with scrypt. The server refuses to start without a 32+ character `PASSWORD_PEPPER` secret that
  is not stored in the database.
- Login performs a dummy scrypt verification for unknown usernames to reduce username-dependent
  timing differences and has PostgreSQL-backed per-IP plus per-username attempt limits. In
  production those rate-limit identifiers are HMACed before storage so the database does not keep
  raw IP addresses or reversible low-entropy username keys.
- Session tokens contain 256 bits of randomness. Only SHA-256 hashes of session tokens are stored
  in PostgreSQL. The Android client encrypts its token with Android Keystore AES-GCM.
- The Android client rejects non-HTTPS account endpoints. `android:allowBackup="false"` keeps app
  account state out of Android backup.
- Leaderboard visibility for newly created accounts defaults to **on**; users can hide themselves immediately.

Use a TLS reverse proxy in production and set `REQUIRE_HTTPS=1`. If `TRUST_PROXY=1` is used,
configure the proxy to replace, not merely append untrusted client forwarding headers.

## Required environment

```text
DATABASE_URL=postgresql://...
PASSWORD_PEPPER=<long random secret>
NODE_ENV=production
REQUIRE_HTTPS=1
TRUST_PROXY=1
```

Set `DATABASE_SSL=1` only when the PostgreSQL endpoint presents a certificate trusted by the
Node runtime. Keep the database private to the service when possible.

## Run

```sh
npm install --ignore-scripts --no-audit --no-fund
npm test
npm start
```

The CI job runs the security tests against a temporary PostgreSQL 17 service, including a
concurrent duplicate-signup constraint test.

## Free Neon deployment

The same account API can run as a Neon Function beside its persistent PostgreSQL database.
Use a **Free** Neon account, without upgrading or enabling paid products. This is a quota-limited
free plan, not a 30-day database trial. Verify the current plan and limits in the Console before
provisioning. Quota exhaustion can interrupt service; no provider promises unlimited free hosting
or unchanged terms forever. Do not create Render's expiring free PostgreSQL for this deployment.

Create a dedicated Road Conquest project in a Functions-supported region, such as AWS US East
(N. Virginia). Use the smallest 0.25 CU database compute and keep the Free plan's provider-managed
idle suspension enabled; some Free accounts cannot change that timeout.
Apply `schema.sql` once with the administrative database connection before deploying. Set these
function environment variables in the provider's secret configuration:

```text
NODE_ENV=production
REQUIRE_HTTPS=1
TRUST_PROXY=1
APPLY_SCHEMA_ON_STARTUP=0
DATABASE_SSL=1
PASSWORD_PEPPER=<independent random secret of at least 32 characters>
```

Neon injects `DATABASE_URL`. Use a dedicated API role with only the account/competition table
privileges described below when overriding it; keep schema-owner credentials out of the APK.
Function startup deliberately skips schema changes. The runtime bundle never includes a pepper,
database password or signing key.

```sh
npm ci --ignore-scripts --no-audit --no-fund
npm test
npm run build:function
```

Package `dist/index.mjs` as the root `index.mjs` in the function ZIP and deploy it with runtime
`nodejs24` and slug `roadconquest`. CI tests the **bundled** Fetch handler against real PostgreSQL,
including signup, duplicate usernames, login, privacy, logout, oversized bodies and spoofed headers.
The native Node HTTP server remains available through `npm start`.

The function validates HTTPS from the platform's request URL and discards caller-supplied IP
forwarding headers. Without a documented trusted client-IP source, anonymous requests share a
conservative database-backed budget: eight signup attempts per hour and twenty login attempts per
15 minutes across the function, plus the existing per-username login limit. This suits a small
hobby deployment; changing IP headers cannot bypass it. Review that limit before broader use.

After deployment reports success, test the returned HTTPS invocation URL through actual signup,
login, session revocation and profile privacy. Only then build the Android release with that
actual `ROADCONQUEST_ACCOUNT_API_URL`; never guess or precompile an unclaimed hostname.
Account hosting alone does not enable competitive rankings.

Provider references: [Neon plans](https://neon.com/docs/introduction/plans),
[Functions deployment](https://neon.com/docs/compute/functions/deploy), and
[Functions runtime limits](https://neon.com/docs/compute/functions/reference/runtime-limits).

### Play policy web pages

The deployed HTTPS account service also serves:
- `GET /privacy` — Road Conquest privacy policy for the app and Play Console.
- `GET /delete-account` — external browser flow that signs in and permanently deletes the account through the same authenticated API used by the app.

Keep these URLs publicly reachable when publishing. They are intentionally static/same-origin pages and do not add another hosting dependency.

## Account changes and deletion

`PUT /v1/username` accepts `{username, password}`, `POST /v1/reauth` accepts
`{password}`, and `DELETE /v1/account` accepts `{password}` under the current bearer session.
All current-password operations apply a per-account attempt limit and lock the user row in a
transaction. Reauthentication verifies the password without changing account or session state.
Username changes preserve the stable account ID and all scores; duplicate names return 409.
Deletion cascades through every session, score, competitive road, live run and receipt. Other
accounts and the shared road catalog are preserved. Device history is local: Delete account leaves
it intact, while the separate in-app Delete all data action uses an exact typed confirmation phrase
and then clears device-only data without contacting or deleting the cloud account. No deletion request
accepts a target user ID.

An existing restricted API role also needs these grants, applied by the database owner with
`your_api_role` replaced by the actual role in the function's database connection:

```sql
GRANT UPDATE (username_display, username_key) ON public.users TO your_api_role;
GRANT DELETE ON public.users, public.auth_rate_limits TO your_api_role;
```

Keep the API role unable to alter schema, change password hashes directly, or modify shared map
catalogs. The HTTP and serverless integration tests run with a restricted role rather than the
database owner to exercise these permissions.

## Verified scoring and leaderboards

The implementation is included, but **disabled by default** (`LEADERBOARDS_ENABLED` is unset).
No uploaded total can enter a ranking. There is no endpoint accepting mileage, road IDs, local
matching results, saved-history imports, or client road names as scores.

The Android upload switch is separate from profile visibility and defaults off. Account creation does not enable it; the user must explicitly opt in from Settings. It collects
only live non-mock GPS fixes with measured speed, binds each exact evidence string to a SHA-256
Play Integrity request hash, and submits batches under a server-issued run/nonce/sequence.
A small final batch is attempted on a normal tracking stop. Missing networks, killed processes,
low-quality GPS, ambiguous matches, failed attestation and short/slow motion earn no credit;
there is no offline-history backfill or estimated-credit fallback.

The server requires the exact Android package `com.roadconquest.app`, the correct request hash, an approved version and signing certificate, a fresh token timestamp, and `MEETS_DEVICE_INTEGRITY`. Play-installed builds must be `PLAY_RECOGNIZED` and `LICENSED`; the exact official signed sideload build may use Google's `UNRECOGNIZED_VERSION` verdict with an `UNLICENSED` or `UNEVALUATED` licensing verdict while still matching the approved certificate/version. Production has **no test verifier or client-provided verdict endpoint**.
CI injects a deterministic verifier directly into the scoring module to test database behavior;
that dependency is never selectable through environment variables or HTTP.

Independent OSRM matching must have at least 0.95 confidence, complete unambiguous tracepoints,
consistent distances/annotations, reasonable snap distances and speed, and the pinned map version.
Only the private HTTPS server chosen by the operator is contacted; clients cannot choose a URL.
Unknown or ambiguous catalog edges reject the batch. Distance is summed from validated matched
legs, rounded to millimeters per leg and stored as PostgreSQL BIGINT. Repeat drives add mileage.
A road unlock is a distinct **OSM way ID** with at least 20 matched meters in an accepted batch;
separate streets with the same name remain distinct, and driving in reverse cannot create a new ID.
One real street may contain multiple OSM way sections: the UI states this definition explicitly.

Each account's score mutations are serialized inside a database transaction. Unique receipts
make response-loss retries idempotent; altered sequence reuse, cross-account runs, expired
challenges, overlapping time ranges and changed boundary anchors are rejected. Starting another
run never clears the account's replay watermark. Road unlocks have a database UNIQUE key.
Public results filter current visibility and eligibility, have deterministic tie ordering, equal
ranks for equal scores, and return only usernames, ranks and aggregate metrics. No route, user ID,
last coordinate or hidden-profile rank is exposed. Responses disable caching.

These are **server-authoritative verified estimates**, not a proof of actual car travel. GPS can
be inaccurate, honest drives can lose credit, cycling can resemble driving, and sophisticated
external GNSS spoofing is not prevented by Play Integrity. Do not market this as fraud-proof or
exact odometer mileage. Keep public rankings disabled if those limitations are unacceptable.

### Required production setup

Public `GET /v1/leaderboard?metric=miles|roads` is available even before verification setup,
returning empty entries and `verification_available: false`. It never substitutes phone totals
or fabricated scores. Drive-writing endpoints remain disabled until the strict configuration
and pinned catalog checks pass. The Android page displays this availability explicitly.

Besides the account service environment, configure:

```text
LEADERBOARDS_ENABLED=1
VERIFIED_OSRM_URL=https://your-private-osrm.example.com
OSRM_DATA_VERSION=<exact data_version ISO timestamp>
ROAD_DATASET_SHA256=<SHA256 of the immutable OSM XML used for both builds>
PLAY_SERVICE_ACCOUNT_FILE=/run/secrets/play-integrity.json
# or PLAY_SERVICE_ACCOUNT_JSON=<service-account JSON stored as a secret env var>
PLAY_CLOUD_PROJECT_NUMBER=<numeric linked Google Cloud project>
PLAY_CERTIFICATES=<base64url SHA256 app-signing certificate, without padding>
PLAY_VERSION_CODES=26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,52,53,54,55,56,57,58,59,60,61,62,63,64
```

Configure and authorize the Google Cloud project for Play Integrity token decoding. Play-installed
builds require Play recognition/licensing. The official signed sideload APK can pass the separate
allowlisted policy only when Google supplies its exact package, version, certificate and device verdict.
Credentials, database passwords and pepper belong in your deployment's secret manager; none go
in Git or the APK. The APK only receives the HTTPS account URL as a Gradle property. The default
unsigned or debug-signed build cannot pass the approved-certificate policy. Validate token decoding
from the actual release APK before enabling rankings; the deterministic test verifier does not
establish that the deployed Google configuration works.

Build OSRM with the car profile from one trusted immutable `.osm` XML snapshot. Enable a matching
`data_version` during extraction. Import identities from exactly that same XML, before startup:

```sh
python3 tools/catalog.py snapshot.osm --data-version 2026-10-01T00:00:00Z > catalog.sql
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f catalog.sql
```

Apply `schema.sql` first. The importer emits `ROAD_DATASET_SHA256` to stderr and refuses an empty
catalog. Duplicate node pairs from different ways stay ambiguous and cannot earn credit. Restrict
catalog tables to the importer/admin; the API database role needs SELECT only on road catalogs
and edges, plus the existing account/competition write privileges. Keep OSRM private to the API,
never silently change its extract, and monitor coverage/annotation compatibility with actual
reference drives before enabling public rankings. Changing the snapshot creates a new map season
and starts separate scores; it never adds incompatible IDs to the old season. Only the operator
may change dataset, approved signing identities/versions or `leaderboard_eligible` moderation.

Endpoint summary:

| Endpoint | Behavior |
| --- | --- |
| `GET /v1/competition` | Availability and scoring definitions, never credentials |
| `POST /v1/verified/start` | Authenticated fresh challenge; replaces the account's old run |
| `POST /v1/verified/batch` | Authenticated `{evidence, integrity_token}`; verified credit only |
| `GET /v1/verified/me` | Authenticated private scores for the current map season |
| `GET /v1/leaderboard?metric=miles` | Top 50 visible eligible profiles by verified distance |
| `GET /v1/leaderboard?metric=roads` | Top 50 visible eligible profiles by unique way sections |
| `POST /v1/reauth` | Authenticated current-password check without changing account/session state |
| `PUT /v1/privacy` | Existing authenticated visibility setting; immediate public exclusion |

Precise evidence is processed in memory, not saved as a route. Boundary validation persists only
the last accepted timestamp plus a server-keyed HMAC-SHA-256 fingerprint of that fix; the precise coordinate is not
stored. Receipt hashes/responses and expired runs are cleaned after one day; cleanup runs at
process/function startup as well as periodically. Aggregate scores/road IDs
remain until account deletion. Configure proxy/access logging to avoid request bodies, authorization
headers and OSRM coordinate-bearing URLs, and set a 32 KiB body limit at the proxy as well.
Back up the database encrypted and keep catalog/version configuration with backups. Configure
monitoring for quota failures and 503/422 responses: failures must never grant speculative credit.

### Verification and remaining deployment checks

`npm ci --ignore-scripts && npm test` runs security/scoring tests. With `DATABASE_URL`, it also
runs real PostgreSQL constraint races, transaction/retry races, visibility, ties, road de-duplication,
overlap and account-boundary tests. CI always supplies PostgreSQL, so those tests are not skipped.
`python3 -m unittest discover -s test -p '*_test.py'` checks the streaming catalog importer.
Each GitHub app release also attaches the exact tested `account-function` bundle. Deploy that artifact (or a byte-identical build) before treating the hosted API as matching the release.\n\nActual Google token decoding, your OSRM extract/annotation behavior, geographic coverage, and
physical-phone GPS still require production integration/field verification. Do not enable public
rankings solely because deterministic automated tests passed.

Primary protocol references: [Play Integrity standard requests](https://developer.android.com/google/play/integrity/standard),
[Google verdicts](https://developer.android.com/google/play/integrity/verdicts), and
[OSRM HTTP API](https://project-osrm.org/docs/v26.4.0/http).
