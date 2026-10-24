# Road Conquest account service

Optional public community routes, moderation, the ten-minute account posting cooldown and Forum Plus
setup are documented in [COMMUNITY_FORUM.md](../COMMUNITY_FORUM.md). The feature defaults off; this
change does not provision payment products or deploy credentials.

Optional free monitoring, provider controls and friend notifications are described
in [FREE_SERVICES.md](../FREE_SERVICES.md), including deployment and migration steps.

This service backs Road Conquest accounts and the leaderboard-privacy preference. Release builds
and debug builds default to the existing Road Conquest Cloudflare Worker HTTPS endpoint. Automated tests override
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

The CI job runs the security tests against an isolated PostgreSQL cluster using the
Ubuntu runner's preinstalled binaries, avoiding container-registry download quotas. It includes a
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
With this production setting, the function skips schema changes. Initialization is lazy: public
health/privacy pages and the competition-availability response open no database connection,
including on a cold start. The first database-backed request coalesces initialization and
expired-state cleanup; subsequent active requests check cleanup at a one-hour interval.
There is no idle cleanup timer. Existing expiry checks still reject expired sessions/devices/
evidence immediately; physical cleanup resumes on the next database-backed request.
See [measured server optimizations](PERFORMANCE.md).
The runtime bundle never includes a pepper,
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
hobby deployment; changing IP headers cannot bypass it.

For independent per-client limits when usage grows, route requests through an independently
trusted HTTPS ingress that supplies the real client IP (not a forwarded value from the caller)
and signs it. Set the **same** random 32+-character `RATE_LIMIT_IP_SIGNATURE_KEY` secret at
the ingress and in the Neon Function. The ingress must strip any client-supplied
`X-RoadConquest-Client-*` headers and overwrite these three:
- `X-RoadConquest-Client-IP`: ingress-authenticated IPv4/IPv6 literal.
- `X-RoadConquest-Client-Timestamp`: current Unix time in milliseconds.
- `X-RoadConquest-Client-Signature`: lowercase hexadecimal HMAC-SHA256 under that
  secret over `METHOD + "\n" + pathAndQuery + "\n" + ip + "\n" + timestamp`.
The Function checks the signature and a 60-second freshness window. Forged, expired,
or absent signatures keep the shared rate-limit budget; neither
`X-Forwarded-For` nor `X-Real-IP` is trusted. Do not put this secret in Android,
public source, or an ordinary browser script. Simply setting the secret without
deploying a trusted signing ingress does **not** change the live shared limits.
Keep the public privacy and deletion pages accessible through the ingress.

### Optional Cloudflare Workers ingress ($0 within the free-tier quotas)

The repository includes a deployable module at `server/worker/ingress.mjs`. Its source
is **not** deployed by GitHub CI. It signs Cloudflare's edge-provided
`CF-Connecting-IP` with the exact HMAC that the Neon Function already verifies,
so unrelated legitimate users no longer share the fallback eight-signups/hour
budget. It does **not** trust client-supplied forwarding or signature headers.

1. Use a **new Cloudflare Worker on the Free plan**. The nonsecret configuration is
   `server/worker/wrangler.jsonc`; its `main` path is relative to that file, not the
   repository root. It enables the standalone `workers.dev` URL and disables preview
   URLs. First authenticate with `npx wrangler login`, run `npx wrangler whoami`, and
   inspect Workers & Pages and routes in the selected account. Confirm the account's
   Workers plan is Free and the configured name `roadconquest-ingress` is unused.
   If that name exists, choose a new unused name in the config. Do not overwrite,
   redirect, or edit `roadconquest-api` or another existing Worker.
2. Configure `API_ORIGIN` as the **base HTTPS invocation origin** of the active
   Neon `roadconquest` Function, without a path or query. Configure
   `RATE_LIMIT_IP_SIGNATURE_KEY` as a **Cloudflare Worker secret** with the
   exact same 32+-character random value held by the Neon Function. Never put
   the secret in Android, JavaScript delivered to browsers, source control or logs.
   Verify the origin against Neon's active function metadata before deployment.
   The checked-in hostname is public configuration, not a database credential.

   From the repository root, validate and deploy only the new Worker:

   ```sh
   node --test server/test/ingress.test.mjs server/test/fetch-adapter.test.mjs
   npx wrangler deploy --dry-run --config server/worker/wrangler.jsonc
   npx wrangler deploy --config server/worker/wrangler.jsonc
   npx wrangler secret put RATE_LIMIT_IP_SIGNATURE_KEY --config server/worker/wrangler.jsonc
   ```

   Enter the existing key only at Wrangler's hidden secret prompt or in Cloudflare's
   secret UI. Do not pass it as a command argument or put it in `vars`. A new Worker
   without the key returns `503`; no Android traffic should point to it yet. Record
   the actual URL returned by Cloudflare rather than deriving one from an account
   name. Do not declare a required secret before the initial Worker/secret setup.

   If the existing Neon key cannot be securely recovered, stop before setting an
   unrelated value or rotating Neon. Inventory existing trusted ingress dependents,
   then obtain the owner's explicit approval for a coordinated rotation. Preserve
   every other Neon environment variable, update all dependents, and verify them
   after rotation. Local `.env*`, `.dev.vars*`, and `.wrangler/` state are ignored;
   keep any secret-bearing scratch files outside the checkout.
3. Verify `GET /health`, `GET /privacy`, `GET /delete-account`, two distinct
   client IPs' signup/login budgets, logout, username changes and account
   deletion from the Worker URL, using explicitly authorized disposable test accounts
   only. Include `GET /v1/competition`, status/body propagation, HTTPS and `no-store`.
   Test spoofed `X-Forwarded-For`, `X-Real-IP` and
   `X-RoadConquest-Client-*` request headers; no caller-supplied IP may bypass
   the verified signature check. Run `node --test test/ingress.test.mjs`
   from `server/` before publishing changes to the Worker.
   The distinct-IP check requires two real external clients (for example, home
   broadband and cellular), or safe backend metrics proving independent buckets.
   Header spoofing and local simulated-IP tests are not live attribution evidence.
   Confirm unsigned direct-to-Neon requests still receive the shared fallback budget.
4. Keep the existing Android default unchanged. Once all live checks pass, an
   internal test build can explicitly override `ROADCONQUEST_ACCOUNT_API_URL` with
   the actual tested Worker URL. Obtain the owner's separate approval before any
   production endpoint switch, merge, app release, or Google Play upload.

The ingress does not sign client identity for requests marked `CF-Worker` or for
Cloudflare's cross-zone Worker placeholder IP. Same-zone Worker subrequests can
alter `CF-Connecting-IP` through `x-real-ip`; those requests deliberately retain
Neon's shared fallback budget. All `X-RoadConquest-Client-*` and forwarding identity
headers are removed before a directly received edge identity is signed. See
[Cloudflare's header provenance documentation](https://developers.cloudflare.com/fundamentals/reference/http-headers/#cf-connecting-ip-in-worker-subrequests).

The direct Neon invocation URL may remain publicly reachable; unsigned direct
requests deliberately retain the conservative shared budget. Worker requests
consume both Cloudflare and Neon free-plan quotas, neither of which guarantees
unlimited use. A Worker is an account-service proxy, **not** a solution to
OSRM's separate public-demo map-matching limits.

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
it intact, while the separate in-app Delete local data action uses an exact typed confirmation phrase
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

## Friends and weekly competitions

Apply the updated `schema.sql` as the database owner before deploying the account function.
The additive hardened-score columns default to zero/false; migration never awards legacy credit.
Existing restricted runtime roles need `SELECT, INSERT, UPDATE, DELETE` on the friendship and cell-score
tables listed below; they do not need catalog or schema write access.

`GET /v1/friends` lists only the authenticated account's relationships. `POST /v1/friends`
accepts exactly `{username, action}` where action is `request`, `accept`, or `remove`.
Incoming requests require acceptance by the other person; either person can remove a
relationship. Pending and accepted relationships are bounded to 50 per account. Stable
user IDs preserve friendships through username changes; account deletion cascades them.

`GET /v1/friends/weekly?metric=cells` ranks first-ever newly verified exploration
cells in the current UTC Monday-to-Monday week. Only the caller and accepted
friends appear. Hidden/ineligible accounts are excluded; ties share a rank.
Revisits, retries and older weeks cannot inflate new-cell totals. Local weekly goals
(5 or 25 newly unlocked roads and 1 or 3 newly rewarded towns) still award points
offline in the current development branch. Friend management works while competitive
scoring is disabled; unavailable standings contain no entries.

## Verified scoring and leaderboards

Policy 3 has one metric: **cells explored**, using exactly Android's existing
`map/FogGrid.kt` squares (`fog-grid-v1`). Public rankings count unique verified
cells; weekly friend rankings count first-ever newly verified cells that week.
Personal roads, mileage, towns and local points are unchanged.

### Grid and evidence

Latitude step is `1609.344 / 111195`; there are 19054 longitude columns, calculated
as `round(360 * 111195 * cos(40 degrees) / 1609.344)`. Server and Android share
fixtures at cell edges, both hemispheres, date-line wrapping and Mercator limits.
Cells are approximately one mile per side near 40 degrees latitude; longitude
width varies with latitude. Counts do not claim uniform explored area or prove
car travel. Changing the grid requires a new version and scoring dataset.

Credit requires two consecutive accurate moving GPS fixes inside the same cell,
with their accuracy envelopes plus 5 meters clear of every edge. They must be
at least two seconds apart, move at least 10 meters or summed accuracy, average
at least 2.5 m/s, and report speed at least 3 m/s on one fix. Stationary jitter,
uncertain borders, gaps and interpolated cells receive no speculative credit.
Only the server calculates IDs; client cells/totals/imports are never accepted.

Fresh server runs/nonces/sequences bind the exact evidence to Google Play
Integrity. Approved package, app-signing certificate and version (71+), fresh
verdict, `PLAY_RECOGNIZED`, `LICENSED`, `MEETS_DEVICE_INTEGRITY` and
`MEETS_STRONG_INTEGRITY` are mandatory. Accuracy, speed, acceleration and
chronological/monotonic timing are checked. Moderation is rechecked under lock.
Some otherwise usable phones cannot meet strong integrity. Production has no
HTTP/environment-selectable test verifier.

Account locks serialize the unique cell insert, cached count, weekly count,
encrypted latest fix and retry receipt in one transaction. Exact retries need
no second Google decode. Overlaps, altered sequences, cross-account evidence,
impossible travel and corrupt checkpoints fail closed. AES-256-GCM checkpoints
are scoped to the account/grid. Keep the independent encryption key stable;
rotation requires a checkpoint migration. Legacy road-score records remain
retained but never contribute to policy-3 scores.

Android requires policy 3 and the exact grid ID. Sharing requires new explicit
consent; earlier policy-2 consent does not enable it. Evidence is sampled at
four-second intervals, bounded to 24 fixes/about 90 seconds, and kept in memory.
Stationary and already-confirmed-cell batches are skipped, using a bounded
8192-cell verification-session cache cleared on account/consent changes. After
skipping past challenge expiry, a fresh challenge authorizes later observations.
No extra location listener, polling job or history backfill is added. Fewer
uploads provide less continuous travel evidence, a deliberate cost/security tradeoff.

### Required production setup

No private OSRM, road snapshot/catalog import, H3 or global town dataset is needed
for competitive cells. Existing personal road/place matching remains unchanged.
In addition to existing account-service settings, configure:

```text
LEADERBOARDS_ENABLED=1
VERIFIED_EVIDENCE_KEY=<32 random bytes encoded as unpadded base64url>
PLAY_SERVICE_ACCOUNT_JSON=<service-account JSON stored as a secret env var>
# or PLAY_SERVICE_ACCOUNT_FILE=/run/secrets/play-integrity.json
PLAY_CLOUD_PROJECT_NUMBER=<numeric linked Google Cloud project>
PLAY_CERTIFICATES=<base64url SHA256 Play app-signing certificate without padding>
PLAY_VERSION_CODES=71,72,73,74,75,76,77,78,79,80,81,82,83,84,85
```

Apply `schema.sql` as the database owner. Restricted API roles need
`SELECT, INSERT, UPDATE, DELETE` on `cell_scores`, `competition_cells`,
`cell_runs`, `cell_receipts` and `cell_weeks`, plus existing account/friend
privileges. Runtime must remain unable to moderate eligibility or alter schema.
Link the Cloud project to this Play app, enable the strong-integrity label and
keep credentials in private server settings; none go in Git or the APK.

Flags remain disabled until actual setup works. Enable the server flag on a test
deployment and `LEADERBOARDS_VISIBLE` in an internal Play build, then test a real
eligible Play-installed phone. Check credit, borders, replay/teleport rejection,
privacy and deletion before enabling public competition. CI/emulator verifier
substitutes cannot establish real Google eligibility.

| Endpoint | Behavior |
| --- | --- |
| `GET /v1/competition` | Availability, policy/grid, definition and Cloud project number |
| `POST /v1/verified/start` | Authenticated fresh run/nonce/sequence |
| `POST /v1/verified/batch` | Verified evidence; privately returns count and accepted IDs |
| `GET /v1/verified/me` | Private verified cell count |
| `GET /v1/leaderboard?metric=cells` | Top 50 visible eligible profiles |
| `GET /v1/friends/weekly?metric=cells` | Current week's newly verified cells |

Unavailable rankings contain no scores; miles/roads metrics are rejected.
Public/friend entries expose only username, rank and count, with caching disabled.
Coarse visited-cell IDs remain private and are retained until account deletion,
alongside counts and one encrypted latest GPS fix. Full GPS routes are processed
transiently, not saved. Receipts/expired runs are cleaned after one day. Account
deletion cascades all current and legacy records; phone deletion leaves cloud scores.
Keep backups encrypted and avoid logging evidence, authorization or coordinates.

`npm test` checks the bundled function and security; CI supplies temporary real
PostgreSQL and runs database tests without skips. Android also checks shared grid
fixtures. Releases attach the tested account-function bundle. Actual Google
decoding and real-phone behavior still require field testing.

These are verified GPS-based visits, not proof of physical travel. External GNSS
spoofing and sophisticated attacks remain possible. Do not advertise this as
unfakeable. Monitor hosting and Integrity quotas; smaller backend requirements
do not guarantee unlimited free service.
