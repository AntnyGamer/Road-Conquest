# Server efficiency — October 16, 2026

These changes reduce repeated database work while preserving authentication,
anti-abuse limits, notification expiry/binding, privacy filtering and scoring.
No schema migration, provider-cap increase, added service, paid plan or Android
change is required.

## Database sleep and public requests

Creating the application pool opens no connection. Liveness, privacy/account-
deletion pages and competition availability work without a reachable database,
including on a cold start and with schema auto-apply enabled. The deep `/health`
endpoint remains a database readiness check.

The first real database operation shares a lazy initialization/cleanup promise
with concurrent requests. Successful cleanup is checked again by active requests
after one hour. There is no background timer to wake an idle database.
Initialization or cleanup failures retry on the next real request. Session and
device expiry, evidence freshness, foreign keys and explicit deletion retain
their existing checks; physical cleanup after an idle interval happens when
database activity resumes. No periodic job is added.

## Notification registration

An ordinary refresh now keeps the same device row and updates its encrypted FID,
binding and exact refresh time. A changed FID still removes the session's old
registration, and an existing FID transfers to its current valid session. Invalid
or expired sessions cannot remove another device. Logout/account deletion still
cascades registrations. No refresh interval or delivery preference changes.

Session validation and conditional removal share one SQL statement. One refresh
uses four statements including transaction boundaries, previously five; the
data statements fall from three to two. Repeated registrations remain encrypted
with fresh ciphertext and preserve the full thirty-day freshness window.

## Public leaderboard

Privacy/eligibility filters and the existing score/username ordering select the
top fifty before computing `RANK`. Rows below that cutoff cannot change any
returned rank: they have lower or equal scores. The boundary-tie regression test
checks this against all eligible scores, including hidden/ineligible high scores.
Friend rankings, totals, scoring, pagination length and tie ordering do not change.

## Before/after measurements

Compared with commit `d041ac7`, on the same local PostgreSQL fixture and desktop:
10,000 ranked accounts, one million stored cell rows, pool maximum five. Requests
were interleaved before/after to reduce timing bias; Google/FCM calls were mocked.
These are local measurements, not production latency or DAU guarantees.

| Check | Before | After |
|---|---:|---:|
| Cold public/liveness initialization SQL | 7 queries | 0 queries |
| Idle cleanup timers | 1 | 0 |
| 500 notification refreshes, SQL statements | 2,500 | 2,000 |
| Median database WAL bytes for 500 refreshes, three rounds | 377,624 | 324,000 |
| Ranking-stage rows, 10,000 tied accounts | 10,000 | 50 |
| Tied leaderboard p95, 160 interleaved samples | 10.14 ms | 6.39 ms |
| Varied-score leaderboard p95 | 1.008 ms | 1.003 ms |

Notification WAL decreased about 14%; tied leaderboard p95 decreased about 37%.
Varied-score latency was comparable (median 0.553 to 0.591 ms, p95 unchanged).
No claim of a general 37% API speedup or higher provider allowance follows.

All 62 server tests passed against isolated PostgreSQL, with no failures/skips.
Coverage includes offline cold public responses, shared maintenance/recovery,
expiry/deletion races, notification refresh/replacement/session transfer,
rank ties across the fifty-row cutoff, auth/privacy and existing map budgets.

Cloudflare's native Worker cache is already enabled for public map responses;
matching/errors remain `no-store`. No duplicate cache or stale ranking cache is
introduced. Google/Geoapify/LocationIQ limits and the remaining Nominatim/country-
population issues in the capacity review are separate from these optimizations.

## Deeper query review

A second pass compares with `a334910` and removes more repeated database work:

- Authentication for routes other than `/v1/me` selects only the session's user
  ID. The immediate foreign key and account-deletion cascade enforce the same
  ownership relationship. `/v1/me` still joins the current profile. Both paths
  enforce expiry on every request, and reuse the already-computed session hash
  locally instead of returning it from PostgreSQL. No identity/consent cache.
- New friend requests check existence, both fifty-relationship caps and insert
  in one statement, while holding both user locks in the same stable order.
  Existing/reciprocal requests remain idempotent at the cap, acceptance still
  requires consent, and only a newly inserted request triggers notification.
- Verified batches select explicit challenge columns, return one count of newly
  inserted cells instead of a row per cell, and combine nonzero total/checkpoint
  and weekly updates. A zero-credit batch returns no inserted-cell row and uses
  the existing direct total/checkpoint update without touching weekly storage.
  The user lock, Integrity checks, continuity checks and receipt transaction stay
  intact. The week is still derived from the final fix in UTC. Competition is
  still disabled by default; this change does not enable it.

| Second-pass measurement | Before | After |
|---|---:|---:|
| Identity-only auth, PostgreSQL response bytes/query | 266 | 73 |
| Profile auth, PostgreSQL response bytes/query | 266 | 165 |
| Warm identity lookup, shared-buffer hits | 6 | 3 |
| New friend request transaction, SQL statements | 6 | 4 |
| 500 friend transactions, PostgreSQL response bytes | 199,179 | 142,286 |
| New-credit verified batch, SQL statements | 13 | 12 |
| 200 starts + 200 new + 200 zero-credit + 200 exact retries, statements | 5,600 | 5,400 |
| Same mixed verified work, median response bytes over three rounds | 641,134 | 617,524 |

The auth queries returned about 73%/38% fewer bytes; friend transactions used 33%
fewer statements and about 29% fewer response bytes. Mixed verified work saved
about 3.6% of statements and 3.7% of response bytes. These are path-specific
results, not whole-app savings or an increase in external provider allowances.
Friend counts exclude HTTP authentication/throttling and notification delivery.
Verified counts include transaction boundaries and mocked Integrity verification.

Bytes were measured at established local PostgreSQL TCP streams, including result
metadata and command tags, excluding TLS, HTTP and connection handshakes. Fixtures
used the same 10,000-account synthetic database; calls were interleaved before/
after, with three mutation rounds, and the pool stayed at five. Desktop timing
varies: the final standalone run's median 500-friend duration fell 176.84 to
148.35 ms, while one scoring round was slightly slower. We claim reduced query
and transfer work, not a guaranteed production latency percentage.

All **64 server tests passed**, including concurrent cap enforcement, exactly one
notification for repeated requests, expiry/logout/account-deletion on both auth
paths, and a forced weekly-write failure that rolls back cells, totals,
checkpoint, drive sequence and receipt together before a successful exact retry.

The wider review also inspected matching retries/known-road reuse, provider
cooldowns and budgets, Cloudflare/KV caching, boundary/population caches,
notification refresh/delivery, account/session cleanup and OAuth token reuse.
Longer notification refresh intervals, stale account/ranking caches, bigger map
batches/allowances, new indexes and data-source replacements were not required
for these changes: each changes behavior, quotas or write/storage costs. Existing
cache and coalescing mechanisms were retained.

The combined writes use PostgreSQL's documented [data-modifying CTE semantics](https://www.postgresql.org/docs/current/queries-with.html#QUERIES-WITH-MODIFYING):
`RETURNING` communicates inserted/updated results within the statement. Unrelated
expiry-table cleanup remains separate to preserve its existing lock ordering.

## Wider service review

A third pass compares with `102650a` and adds seven related improvements:

1. Login IP and username reservations use one SQL statement. The username
   reservation depends on an allowed IP reservation; denied IPs do not spend the
   username budget. Limits, independent resets and saturated-row write avoidance
   remain unchanged. Password verification and session lookup are not cached.
2. Push-event reservation and eligible-device selection share one statement.
   Events still consume the same recipient budget even with no registered device;
   at most three events/five minutes and five fresh session-bound devices/event
   are selected. This remains best effort; on-device opt-out/account binding
   checks stay authoritative for display.
3. Remove unused result data from profile queries, session/challenge creation,
   friend acceptance and registration validation. Command row counts still check
   successful writes. Push selection returns no redundant token hash; invalid-
   token deletion derives the same SHA-256 hash from the authenticated decrypted
   FID and still compares the original ciphertext to protect a concurrent refresh.
4. Maps/optional ingress reuse non-extractable HMAC CryptoKeys. Caches retain at
   most two/one secret entries per handler, share concurrent imports, evict on
   rotation and retry failed imports. Every request is freshly signed; no IP,
   timestamp, GPS, signature or account response is cached. The original account
   Worker is preserved.
5. Validate/parse each map timestamp once and reuse the same epoch seconds for
   fallback ordering and the LocationIQ URL. Accuracy, bounds, ordering, payload,
   quota, pacing and NoMatch rules do not change.
6. Clear provider-switch pending state in a Promise finalizer. A synchronous KV
   failure can no longer pin a resolved old promise indefinitely; last-known
   values, the existing timeout and concurrent-read coalescing are preserved.
7. Null/malformed waypoint entries return HTTP 400 before any quota reservation
   or upstream request, rather than escaping validation with an exception.

| Third-pass local measurement | Before | After |
|---|---:|---:|
| 500 allowed paired login reservations, statements | 1,000 | 500 |
| Same reservations, PostgreSQL response bytes | 77,000 | 37,500 |
| 500 push events with one device each, statements | 1,000 | 500 |
| Same events, PostgreSQL response bytes | 291,000 | 203,000 |
| 500 registration refreshes, PostgreSQL response bytes | 107,500 | 74,000 |
| 200 profile lookups, PostgreSQL response bytes | 33,000 | 27,200 |
| 200 session inserts, PostgreSQL response bytes | 15,600 | 7,400 |
| 200 drive starts, PostgreSQL response bytes | 16,000 | 7,400 |
| 2,000 map requests, signing-key imports | 2,000 | 2 |
| 1,000 optional ingress requests, signing-key imports | 1,000 | 1 |
| Timestamp parses/25-point LocationIQ request | 98 | 25 |

Login/push numbers refer to these database stages, not whole API traffic. An
ordinary successful login goes from four SQL statements to three. Registration
still uses four statements but returns about 31% fewer bytes. Profile responses
save another 18% versus the preceding lean-query change. Session/drive-start
results save about 53%/54% while preserving command counts. Friend acceptance is
covered by functional tests but is not separately timed here.

Database mutations were interleaved over three rounds on isolated synthetic
PostgreSQL. Byte counts include the local server protocol, excluding TLS/HTTP and
handshakes. Map/ingress requests used local budget/upstream stubs: the same 2,000
budget reservations and 2,000 upstream calls occurred in each map variant. No
provider credits, real pushes or production load test were used. Import counts
assume one warm handler and stable secrets; cold isolates still import keys.
Node desktop timing is not Cloudflare CPU or a production latency guarantee.

All **71 server tests passed**, zero failures/skips. Added tests cover concurrent
login caps/short-circuit/reset/saturated writes, push budgets and device expiry,
invalid-token replies racing refreshed registrations, independent signatures
across concurrent callers/rotation/import failure, null waypoints and recovery
after a synchronous KV failure. Both Worker bundles passed Wrangler dry runs.

App request scheduling, known-road reuse, provider cooldowns, boundary caches,
ads, Firebase consent and weekly reminder scheduling were reviewed without
changing their behavior. No Android source update, schema migration, paid plan,
weaker hashing, cached authorization or increased provider allowance is added.

## Account and response cleanup

A fourth pass compares with `8360092`:

- Friend listing returns one private integer status instead of two booleans.
  The Function maps it to the same accepted/outgoing/incoming JSON strings.
  Accepted priority, either user-ID orientation and username ordering are tested;
  no pagination, cap, privacy rule or result cache changes.
- An exact current-username save returns its profile from the existing locked
  password lookup after successful password verification and the same throttle.
  It skips only the redundant `UPDATE`, preserving the user row version. A
  conditional JSON column is populated only for an exact case-sensitive match;
  real renames use the original update, uniqueness checks and response. Case-only
  changes remain real renames. Row locking still writes WAL.
- Reauthentication no longer reads the unused username key. Deletion still
  reads it for the existing throttle-key cleanup. Rename lookup includes only
  the conditional profile needed to skip an unchanged write.
- `readJson` rejects a valid declared length above 32 KiB before reading the
  stream. Content-Type retains precedence; chunked/missing/understated lengths
  remain protected by the existing streaming byte guard. The limit is unchanged.

| Fourth-pass local measurement | Before | After |
|---|---:|---:|
| 200 empty friend lists, database response bytes | 23,600 | 17,800 |
| 200 ten-friend lists, database response bytes | 80,200 | 64,400 |
| 200 fifty-friend lists, database response bytes | 312,200 | 256,400 |
| 200 reauthentication lookups, database response bytes | 65,000 | 56,000 |
| 500 unchanged-name transactions, SQL statements | 2,000 | 1,500 |
| Same transactions, database response bytes | 248,000 | 202,000 |
| Median WAL bytes for those transactions, three rounds | 86,728 | 48,120 |
| 100 case-only rename transactions, database response bytes | 49,600 | 48,300 |
| Declared 64-KiB rejected body, bytes read by `readJson` | 65,536 | 0 |

Friend protocol data fell about 18–25%; reauthentication lookup data about 14%.
Unchanged-name transactions saved 25% of statements, 19% of result data and 45%
of WAL. Actual renames retain four transaction statements and comparable WAL.
In the standalone sample, case-only rename work was about 0.03 ms/call slower;
unchanged-name work was faster. Timing is not a production latency promise.

Figures use captured real SQL on isolated synthetic PostgreSQL, established TCP
connections, and three interleaved mutation rounds. Protocol bytes exclude TLS,
HTTP and handshakes. Rename measurements include the transaction and locked
password lookup, but exclude HTTP, hashing and throttle costs; those still run
unchanged in the API. The body experiment called the real Fetch adapter with
controlled local streams. Both versions still rejected missing/understated-length
oversized streams with 413 and zero SQL. No player account was renamed or queried.

All **72 server tests passed**, with zero failures/skips. New coverage checks
friend status/order equivalence, unchanged row versions, case-only renames,
content-type precedence and zero stream reads/SQL on cold oversized requests,
with schema auto-apply both enabled and disabled. No Android source, Worker,
schema, service plan, provider quota or credential change is required.

## Minor weekly-friends query reduction (draft PR #96)

- The verified weekly friends ranking now uses `UNION ALL` rather than
  `UNION` to combine the user with their accepted friends. The existing
  `friendships` primary key and `user_low < user_high` constraint guarantee
  that the user cannot be their own friend and every accepted counterpart
  occurs at most once. Removing the unnecessary de-duplication stage preserves
  participant counts, score ranks, privacy/eligibility, and the 50-friend cap.
  The friendship integration test explicitly checks distinct ranking entries.
  This reduces PostgreSQL ranking work but does not reduce HTTP request count.

## Reject syntactically unauthenticated private routes before database work (draft PR #96)

- Notifications-device and friends routes now reject missing/malformed bearer tokens before allocating a PostgreSQL-backed IP rate-limit entry. Enabled verified endpoints do the same; disabled verified endpoints continue to return their existing 404.
- Every syntactically valid 43-character bearer token still passes the original persistent IP rate limiter, current-session verification, user-specific limits and permission/scoring checks. Anonymous public rankings retain their original rate limits.
- Under saturation, malformed authentication returns a cheap 401 rather than spending a 429 rate reservation; no valid authentication can bypass the existing limit. The offline/cold Function regression test asserts that such requests return without opening any PostgreSQL connection, even with startup schema application enabled.
- This primarily saves Neon database work during unauthenticated probes; it does not reduce authenticated users' normal API request counts.

## Shared sign-in budget and credential lookup (draft PR #96)

- A normal login now retrieves the username's salt/hash/profile fields as part of the same PostgreSQL statement that reserves the per-IP and per-username budgets. Previously every permitted, validly formatted username needed a separate SELECT. This saves **one database round trip per permitted sign-in attempt**, not one password-hash operation.
- The existing ordered IP/username budget CTEs and short-circuit/saturation behavior are unchanged. Denied budgets cannot return password hash fields; unknown or malformed usernames still consume their ordinary budgets and execute the full dummy scrypt password verification. Signup, login limits and session creation are unchanged.
- Regression coverage verifies matched/missing users, saturated budget denial, no leaked hash fields on denial and null usernames. Existing end-to-end login and 100-way concurrency limit tests continue to apply.

## Merge private-route IP budgets and live session lookup (draft PR #96)

- Friends, notification-device and enabled verified-scoring endpoints now combine their existing PostgreSQL-backed IP allowance reservation and currently valid session check in **one database query instead of two**. This saves one round trip on each normal authenticated call, including the combined friends/weekly-ranking screen request. Session hashes and user identities are not cached, and account deletion/logout/expiration checks stay in place.
- Requests without a syntactically valid bearer token are still cheaply rejected as 401 before PostgreSQL; syntactically valid expired or forged tokens still consume the same IP budget. An exhausted window still returns 429 without disclosing account identity, and per-user write budgets remain separate and unchanged.
- PostgreSQL regressions check allowed, nonexistent, exhausted and expired sessions, as well as accounting. An end-to-end Function test counts only two database calls for the authenticated friends screen (rate/session and friends list).

## Public/verified leaderboard rate-limit consolidation (draft PR #96)

- An authenticated optional `GET /v1/leaderboard?metric=cells&mine=1` now reuses the same single SQL statement to reserve the global leaderboard IP allowance and check the user's current session. The subsequent combined ranking/private-total SQL remains unchanged. This removes **one more PostgreSQL round trip per signed-in leaderboard refresh**.
- Anonymous rankings, unsigned `mine=1` requests, and disabled competition still use the original public IP budget and response shape. Valid-but-expired session tokens remain rate limited and see only public results. No score, privacy filter, or cache rule changes.

## Single-chunk JSON parsing (draft PR #96)

Compared with PR head `df04c50`, `readJson` uses a Buffer view of a single
received chunk instead of copying it through `Buffer.concat`. The view preserves
the chunk's byte offset and length for both Node Buffers and Fetch Uint8Arrays.
Multiple chunks still join before decoding, preserving split UTF-8 characters.
The byte cap, Content-Type checks, JSON validation and all API rules stay intact.

An isolated comparison of the actual old/new parsing functions checked 64-byte,
4-KiB and 32-KiB JSON bodies. For 100 single-chunk 32-KiB requests, concatenation
copies fell from 3,276,800 bytes to zero; multi-chunk copies were unchanged.
This eliminates one body-sized allocation/copy per single-chunk request, not
UTF-8 string allocation, JSON object allocation or platform buffering. Database
and external-service request counts do not change.

Five interleaved local timing rounds showed faster single-chunk medians for
4-KiB and 32-KiB bodies. Small-body and multi-chunk timing varied, with measured
increases below 0.0001 ms/call in this run. The additional length branch has a
cost; this is not a promise of zero CPU overhead or production latency.
No functional regression was found. All **74 server tests passed**, zero skips
or failures, on the latest PR base. New built-bundle checks cover offset
Buffer/Uint8Array bodies and split UTF-8, with schema initialization enabled and
disabled, while retaining the newer anonymous/malformed authentication checks.
Malformed JSON, non-object JSON, empty bodies and size-limit behavior also
matched in the isolated comparison. No merge, deployment or Android change is
part of this follow-up.

## Additional matching and verification work reductions (draft PR #96)

This pass compares with `99f63f5` and preserves the preceding PR changes:

- **LocationIQ parsing:** reuse the already-parsed provider JSON for geometric
  validation instead of parsing the same response twice. The string entry point
  remains available, and the object entry point treats provider evidence as
  read-only. Cache hits still validate against the current point IDs/accuracy.
- **Geoapify normalization:** pass normalized JSON directly into the same shared
  validator, removing a serialization/parse round trip for each normalized
  matching. Confidence, geometry, blocked seams and fallback decisions are
  unchanged.
- **Geoapify multi-leg waypoints:** copy top-level waypoint properties directly
  before changing the local leg index. Nested values remain read-only; the
  original waypoint is not modified. This avoids one text serialization/parse
  round trip for each copied waypoint while retaining all properties.
- **Scoring rejection order:** check the existing two-second duration and
  three-meters/second reported-speed gates before computing great-circle
  distance. Passing pairs still execute every existing distance/accuracy gate;
  rejected pairs cannot earn credit merely because a calculation was skipped.
- **Zero-credit batches:** compute the UTC week only when new cells require a
  weekly write. Zero-credit batches still persist the checkpoint, sequence and
  retry receipt in the same transaction. Positive batches still belong to the
  UTC week of their final fix.
- **Checkpoint cryptography:** reuse one per-batch derived key and authenticated
  scope buffer for continuity verification and sealing. Keys are not cached
  between batches/accounts. AES-GCM, random IVs, fingerprint checks and the
  version-2 ciphertext format are unchanged; old/new ciphertext interoperability
  was checked in both directions. The small per-batch context adds an object
  and closures while removing duplicate cryptographic derivation.
- **Password bounds:** avoid materializing passwords as code-point arrays.
  UTF-16 bounds handle guaranteed cases; remaining counting stops after the
  maximum. Signup/current-password validation still accepts 8–128 Unicode code
  points, and login retains its existing maximum-only check and exact input.
  Password text, scrypt parameters, salting, peppering and login budgets do not
  change. Tests compare both paths with the prior policy across 1,510 inputs,
  including emoji, mixed text, lone surrogates, wrong types and long input.
- **FCM token race:** a delayed 401 clears the cached authorization only when it
  rejected the currently cached token. It cannot discard a newer refresh.
  Rejection of the current token still invalidates it. No message retry, extra
  push or notification timing change is added.

| Local before/after work | Before | After |
|---|---:|---:|
| Distance calculations for 24 one-second GPS pairs inside one cell | 24 | 0 |
| Distance calculations for 24 pairs below the reported-speed gate | 24 | 0 |
| Distance calculations for 24 eligible moving pairs | 24 | 24 |
| Key derivations for 1,000 verify/seal pairs with an existing checkpoint | 2,000 | 1,000 |
| Scope buffer allocations for those checkpoint pairs | 2,000 | 1,000 |
| Week-date allocations per zero-credit submission | 1 | 0 |
| Password arrays for 100 validation/login pairs | 200 | 0 |
| OAuth calls in a delayed-old-401 race, three push attempts | 3 | 2 |

All **77 server tests** and **155 focused Android matcher tests** passed with
zero failures or skips. Scoring results also matched the previous code across
10,000 generated cases. Existing multi-leg, corrupt-sibling, Date Line, junction,
turn, confidence, cache-ID remapping and provider-fallback tests passed; added
coverage checks object-path equivalence and unchanged provider evidence.

Measurements use the actual before/after source locally, synthetic data, a stub
verifier/database for the submission counters, and mocked Google requests.
No player data, real provider quota or real push was used. Local checkpoint
timings were comparable across five interleaved rounds; these are operation
reductions, not a production latency, battery or user-capacity guarantee.
The pass adds no SQL queries, persistent cache, schema, service plan, paid
dependency or changed provider allowance. SQL counts stayed identical in the
instrumented zero/positive submission flows. Android changes require an app
build; the PR remains draft and this pass does not merge or deploy it.


## Driving allowance and recovery � October 17, 2026

PR #97 adds credit-boundary primary batches, complete-cache preflight, accurate
network timing, compatible deadlines and conservative shared matching recovery.
New reservation tables retain no traces. Public tiles keep their existing native
cache and lightweight allowance path. See [capacity and measured evidence](maps/CAPACITY.md)
for behavior, extra bookkeeping costs, free-limit headroom and conditional driving
hour estimates. Android and gateway changes each need their own build/deployment.
The earlier sections describe their original passes; this work does not deploy
services, raise provider caps or migrate the account database.


## Further server and driving work in PR #100 - October 17, 2026

This pass starts at `c208d09`, the consolidated PR #100 head. It preserves that
PR's replay, contrast, town-polygon proof, rendering, population-locking changes
and test cleanup. Earlier optimizations already merged into main remain in place.

- A verified batch combines account eligibility, an exact retry receipt and the
  active challenge in one indexed read instead of three database round trips.
  Receipts skip challenge lookup. Malformed evidence retains the eligibility-first
  error. Account locking, a second receipt check under the lock, live challenge
  expiry, Google attestation, continuity checks, scoring and atomic writes remain.
  An expired/missing challenge still rechecks for a receipt committed after the
  preflight snapshot. Neither identity nor eligibility is cached.
- A saved-road departure scan decodes the same road geometry once per scan,
  reusing it for every GPS fix in that scan. It adds no lasting cache and retains
  every distance calculation, invalid-geometry rejection and early exit. The
  scan still examines all accurate fixes, including departures beyond fix 120.
- Rejected Google OAuth/Integrity responses cancel their unread body streams
  before returning the same retryable error. This releases response resources
  promptly during an outage without adding requests or changing verification.

| Measured work | PR #100 baseline | This pass |
|---|---:|---:|
| New-credit verified batch, statements including BEGIN/COMMIT | 12 | 10 |
| Zero-credit verified batch, same transaction guarantees | 12 | 10 |
| Exact committed retry, including live eligibility | 2 | 1 |
| 200 starts + 200 new + 200 zero + 200 exact retries, statements | 5,400 | 4,400 |
| Same mixed work, median database response bytes, three rounds | 608,911 | 576,116 |
| Geometry decodes in a 120-fix scan fixture | 120 | 1 |
| Geometry output comparisons with exact equality | - | 2,000 passed |

The database fixture uses 200 synthetic accounts, 20,000 existing receipts,
PostgreSQL 17.11 and pool maximum five, with three interleaved before/after rounds.
Each variant submits 1,800 batches across those rounds. Google is mocked. Counts
exclude HTTP authentication/throttles; bytes include PostgreSQL result metadata
but exclude TLS, HTTP and connection handshakes. Mixed statements decrease
18.5% and result bytes decrease
5.4%. Database writes and provider credits
are unchanged. Desktop timing is mixed: new/zero batches improved slightly, while
exact retries were slightly slower locally despite fewer round trips. These are
work reductions, not a promised production response-time percentage.

The geometry comparison invokes actual compiled before/after distance methods.
The baseline file is unchanged between compiled commit `276af0c` and `c208d09`.
Its desktop JDK 21 / standalone org.json fixture has 500 vertices and 120 fixes,
15 scans per round and five interleaved rounds. Median calculation time was
477.83 ms versus 61.45 ms for those 15 scans. This measures that narrow loop,
not Android parser/device speed, battery life, SQLite time or additional driving
hours. The existing API 31/37 repository tests cover real SQLite visit behavior.

At a **hypothetical** 500 hours/day with 40 new verified batches/hour, two saved
reads per batch remove 40,000 database round trips/day, 280,000/week,
1,200,000 per 30-day month and 14,600,000/year. This is arithmetic, not tested free
capacity. Optional verified competition remains disabled by default. Personal
road matching uses separate map-provider allowances, which this pass does not
increase. Google currently documents a default 10,000 server token decryptions
per day; 20,000 verified batches/day would need fewer genuinely new-cell batches
or an approved higher quota. Its separate token-request bucket covers classic
requests and standard preparations, not every standard token request.
See [official quota details](https://developer.android.com/google/play/integrity/setup#understand_api_usage_limits).
No attestation gate is relaxed to avoid this quota.

All 100 server tests and all 979 Android unit tests (including the 145 focused
repository/reuse tests) passed with no failures or skips. `lintDebug` and
`assembleDebug` passed. Regression coverage includes concurrent scoring,
moderation during attestation, account deletion, stale exact retries, old scoring
policies, altered evidence, transaction rollback, late receipts, oversized sequence
values on ineligible accounts, and cancellation of failed upstream streams.
[Raw measurements and assumptions](pr100-performance-evidence.json) accompany this
pass. No schema, new service, paid dependency, production configuration or
provider-cap change is introduced. Nothing is merged, released or deployed.


### Double-check follow-up

A controlled slow-cancellation test found that awaiting error-stream cleanup
could postpone the 503 response. Cleanup now starts without waiting for its
completion; rejected cleanup is still caught. The regression test failed before
the fix and passes afterward. A second test uses an actual local HTTP server
that sends error headers but never finishes its body, and confirms cancellation
closes that upstream response. All 100 server tests pass. The Android source is
unchanged from the passing 979-test run.

The measured competition function's SHA-256 still matches the benchmarked
implementation. Raw statement/data totals and daily/week/month/year arithmetic
were independently recalculated. All 13 original PR #100 files and the complete
older performance-report prefix are unchanged. No production service was tested
or changed, and the remaining provider-capacity caveats still apply.
