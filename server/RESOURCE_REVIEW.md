# Server, storage and phone resource review

## Additional community/tour PR review

The map budget now skips conditional health UPDATE/DELETE statements when its existing sole-writer
cache proves the probe ID cannot match. Cold-cache paths and matching recovery probes retain the
original writes. Successful no-op checks keep the valid cache entry, avoiding a subsequent read.
This changes no provider allowances, retry timing, health expiry, matching quality or tracking.
`maps-probe-noop.test.mjs` exercises 50 healthy cancelled reservations and their retries: zero health
UPDATEs, one initial health read, refunded credits and all 50 non-refundable attempts retained.
The existing cache, recovery and cancellation tests also cover cold and real recovery paths.
No fleet-wide billing or battery reduction was measured.

The new forum adds usage by design. Its specific bounds, caching, account cooldown and launch
requirements are documented in [COMMUNITY_FORUM.md](../COMMUNITY_FORUM.md). It does not introduce
background polling, store media bytes in PostgreSQL or weaken moderation to save work.

## Earlier review

This follow-up compares with `503a286296a638e69b70db545a244431ce1d9e5c`.
It removes repeated work in eight areas without changing GPS subscriptions,
recording quality, matching confidence, geometry, distance, provider cadence or
quota caps. Battery benefits below mean avoided CPU, I/O and callbacks; no
physical-device battery percentage or fleet-wide capacity gain was measured.

| Area | Change | Benefit and boundary |
|---|---|---|
| Tile quota accounting | Read matching-attempt counters only for managed matching requests | Retains useful credit balances inside the existing 512-entry cache. Matching still loads/enforces its durable attempt balances. |
| Country/region boundary backup | Replace an expired existing dataset before applying the two-entry cap | Refreshing one dataset keeps the other unexpired dataset. The 15-minute expiry, licensing, exact-name/containment checks and town exclusion remain. |
| Account profile refresh | Skip saving an unchanged session after reloading and checking its token | Avoids redundant AES-GCM encryption and preference writes. No plaintext-session cache; changed state still uses a fresh IV and logout/token guards remain. |
| Exploration duplicate suppression | Evict the oldest entry instead of clearing all 4,096 cells | Keeps recently visited cells from immediately generating duplicate SQLite work after turnover. The bound stays 4,096 per cache; failed writes remain retryable. |
| Overlay cancellation | Check generation before queued SQL, between cache reads and before conversion | Stops obsolete work earlier; it does not alter current polygons, partitioning, vertices or displayed detail. In-progress individual conversion/read operations still finish. |
| Retired boundary storage | Remove only `place-overlays-v2` and `place-overlays-v4-towns` during initial cache maintenance and explicit cache clearing | These are unused formats retired by earlier releases. Current caches, personal history, rewards, exports and unrelated files remain. |
| Export/restore copying | Reuse one cleared `ContentValues` buffer per table | Avoids a new row map for each GPS/other record. Schema, values, row order, transactions and CSV/ZIP format remain. |
| Explicit data deletion | After committed deletion, compact free database pages and attempt WAL truncation | Returns unused DB storage rather than only making it reusable inside SQLite. Runs during explicit stopped-data deletion, with no periodic task or driving-time compaction. |

## Reproduced results

The actual `MapBudget` ran before/after in local Cloudflare **workerd**. Two
passes of 200 synthetic client buckets made 400 uncached tile reservations:

| Runtime work | Before | After |
|---|---:|---:|
| Counter queries | 400 | 200 |
| All SQL executions, including initialization/maintenance | 808 | 608 |
| SQLite rows read | 2,409 | 1,407 |
| SQLite rows written | 1,411 | 1,411 |
| Cached counter entries | 512 | 403 |

All 400 complete responses and final durable-state hashes matched. A subsequent
matching request was rejected by the same preloaded non-refundable attempt cap,
including identical status, body and headers. This saves bookkeeping reads, not
tile provider credits or admitted-request writes. No provider or production
traffic was used.

The backup-boundary regression loads two region datasets, expires the second,
refreshes it and revisits the still-valid first. It requires six metadata/data
loads rather than eight. Cache size and containment rejection are also checked.

The exploration-cache turnover fixture first fills 4,096 cells, adds one new
cell and revisits 100 recent cells. Candidate database submissions after the
initial fill fall from **101 to 1**, without enlarging either cache. Separate
tests cover recency, retry after write failure and concurrent access. Eviction
only permits a later idempotent persistence check; it cannot remove exploration
or award a second cell.

The retired-cache fixture reclaims two 32-KiB obsolete files while preserving a
current readable polygon and an unrelated file. Reclaimed storage on a real
phone depends on which retired files exist. Clearing also covers recreated old
directories during explicit deletion/restore cleanup.

The deletion regression fills 20,000 exploration rows, deletes through the
actual stopped-data flow, checks reclaimed/free pages, preserves the generation
guard and successfully records new history afterward. Regular `clearHistory`
does not add a compaction step to test setup or normal repository maintenance.

The Android Keystore device regression now repeats twenty unchanged profile
refreshes and requires the encrypted token preference to remain identical.
Existing cases still require fresh IVs for actual saves and reject old tokens.
The [combined CI at 587b8ae](https://github.com/AntnyGamer/RoadConquest-Beta/actions/runs/39274583015)
executed all nine native cases on Android 12, 15 and 17, including that Keystore
test and actual border continuity at three zoom levels. All 27 cases passed.

## Compaction tradeoff

SQLite documents that `VACUUM` rebuilds the database to reclaim deleted pages,
needs an available write connection and can need temporary disk space. It runs
after the user-requested deletes commit, outside their transaction. It may make
that explicit deletion take longer. Busy/low-space failures are logged without
turning an already-committed deletion into a failure; freed pages remain reusable.
An active read snapshot may defer WAL truncation. There is no claim of universal
immediate filesystem reclamation or forensic deletion from device flash.
[SQLite VACUUM](https://www.sqlite.org/lang_vacuum.html),
[SQLite WAL checkpoint modes](https://www.sqlite.org/pragma.html#pragma_wal_checkpoint).

## Accuracy and validation

Fourteen source invariants were rechecked against the chat's starting commit
`137e398706c0caf591ac13c091634a3711fc15e2`. Complete provider clients/parsers,
geometry validator, distance calculator, database schema, GPS acceptance,
tracking constants and the five-fix reuse rule still match. No raw history is
deleted by these optimizations except through the existing explicit deletion UI.

All **1,073 Android unit tests across 67 suites** pass with zero failures,
errors or skips, including the existing matching, turn, distance, deletion and
export/restore regressions. Android lint passes with zero errors (299 warnings);
both debug app and device-test APKs build successfully. This includes compilation
of the updated Keystore device regression; it still requires emulator/device
execution in CI.

All **122 server tests** pass against isolated PostgreSQL, including warm/cold
quota equivalence, refunds, rollback, pacing, replay and UTC rollover. Wrangler
accepts the gateway bundle in a dry run. Results and limits are recorded in
[resource-efficiency-evidence.json](resource-efficiency-evidence.json).

Other audited paths already have useful guards: bounded gzip boundary caches,
streamed exports/imports, export-snapshot cleanup, hidden-map pause handling,
viewport query reuse, car-source duplicate suppression, optional-service consent,
notification registration caching and request-driven server maintenance. No
sampling reduction, shortened evidence retention, map-detail loss or weakened
quota/verification rule was used for an additional saving.

Nothing is deployed or released. Android changes need a new app build; the tile
accounting change needs a maps-gateway deployment.

## Further review based on 587b8ae

Five additional changes remove repeated work outside the matching algorithm:

| Area | Change and protection |
|---|---|
| Recent trip browsing | Read descending IDs in keyset pages, initially 512 rows and growing to 8,192 for long drives. Keep grouping across pages, exact IDs/times/counts/distances, singleton exclusion, free/VIP limits and reset-generation checks. New GPS appends cannot enter an older page. |
| Provisional route rendering | Compare complete immutable GeoJSON collections before another native source update. Changed coordinates or properties still publish; style installation resets the comparison. Missing sources and synchronous failures remain retryable. Expiry scheduling and all matching/confirmed geometry stay unchanged. |
| Private GPX exports | Apply the existing inclusive endpoint time bounds in SQL as well as retaining the writer's guard. Hidden endpoints need not cross the cursor/JVM boundary. Trip-ID bounds, row ordering, privacy padding, output format, entitlement and reset checks stay unchanged. |
| Weekly reward refresh | Read owned reward keys before inserting and omit already-owned milestones. Keep the same write transaction, unique keys, thresholds, payouts, current/previous-week grace and historical records. Missing rewards still earn normally. |
| Verification JSON buffers | Decode a single bounded response chunk through its exact byte-range view. Multi-chunk UTF-8 still joins before decoding; size checks and error classification remain. No verification rule, attestation call or quota changes. |

Android's `SQLiteCursor` requests the complete SQL result count during its first
window fill. Stopping the Kotlin loop therefore did not stop the old unbounded
history query. The official [Android implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/database/sqlite/SQLiteCursor.java)
supports the bounded-query change. This does not trim stored history or downsample
replay/driving data.

A local SQLite result-projection probe with 200,000 fixes and 100 requested
two-point trips evaluates **200,000 rows before versus 512 after**, in one query
in both versions, with identical 100 trip results. This is query-work evidence,
not an Android latency, physical I/O or battery measurement.

Long scans can issue more bounded queries. A 20,000-point drive, three singleton
stops and one older trip keep all 20,023 scanned rows and both trips, using six
queries instead of one. Growing pages to 8,192 limits cursor churn; no row limit
is used to discard an unfinished trip. Android regression cases separately cover
20,000-row archives and a whole 20,000-point drive across pages on SDKs 31 and 37.

The publication fixture sends 100 newly constructed identical collections: the
publisher invokes the native-update callback once. A one-billionth-degree
coordinate change, property change, clearing and style reset still update.
One prior collection is retained for exact comparison; no coordinate rounding,
lossy fingerprint or reduced refresh cadence is used.

A SQLite BEFORE INSERT probe records **40 conflicting reward attempts before
versus zero after** across 20 refreshes of two owned road goals. Deleting one
reward then produces exactly one replacement award and the original balance.
Both old SDK-expanded cases failed the zero-attempt assertion before the fix.
The probe counts attempted statements/rows, not a production WAL saving: SQLite
could already ignore conflicts without rewriting the reward rows themselves.

The verification-buffer regression records **one joined-buffer copy before
versus zero after** for a single-chunk reply. Sliced buffers, split multibyte UTF-8,
exact byte limits, oversized bodies and malformed/error responses remain covered.
It failed against the original implementation. All 123 server tests pass against
isolated PostgreSQL, including the optional verified-scoring paths.

The same fourteen driving/matching source invariants still match the chat's
starting commit. Full Android validation is recorded in the PR and
[follow-up evidence](resource-followup-evidence.json). These changes do not
increase supplier credits or change the DAU/driving-hours planning assumptions.

## Deeper review based on 31e0984

Five more changes remove repeated writes and phone work:

| Area | Change and protection |
|---|---|
| Road visit persistence | After the existing merge/departure algorithm finishes, keep a single unchanged interval or update its endpoints in place. New visits and merges of multiple existing visits retain insertion/deletion. Always recount and repair road visit metadata, including legacy inaccurate counts. |
| Friend acceptance retries | Update only pending incoming requests. An already accepted incoming relationship keeps its successful response without another PostgreSQL tuple version. Keep both participant locks, the same database round trips, reciprocal consent, missing-request errors and notification behavior. |
| Municipal discovery cache | Load town metadata once per batch, but read/decompress/parse a polygon only after the existing state filter accepts it. Cache geometry only within that batch and precompute each town's state code. Preserve exact containment, holes, boundary points, state aliases, ambiguity fallback and immediate reuse of newly verified towns. Subdivisions and legacy keys still cannot bypass authoritative lookup. |
| Trip replay | Index existing recording breaks once when loading a route. Seeking uses those ranges rather than rescanning every previous point. Keep every existing sampled coordinate, route prefix, backward-seek behavior and recording gap. |
| Weekly reminders | Setting the same PendingIntent replaces its alarm, avoiding a separate cancel call when scheduling. Disabling reminders or revoking permission still cancels. Keep Monday/time-zone calculation, inexact timing, idle behavior and boot/package rescheduling. |

The Android SQLite trigger probe observes **20 deletes plus 20 inserts before,
zero interval writes after** for 20 contained match completions. The old code
fails this assertion on SDKs 31 and 37. Extending one interval now uses one UPDATE;
merging two intervals still deletes both and inserts their union. The fixture
also deliberately corrupts a road's count/exactness metadata and confirms it is
repaired even when its interval does not change. These are interval-row operation
counts; road metadata and point resolution still write when required.

The PostgreSQL regression submits 20 concurrent repeated accepts for each ID
orientation. The accepted row's `xmin`, requester, creation timestamp and consent
remain unchanged. The old code changes `xmin` after the retries. Requester-side
self-acceptance and acceptance after removal still fail; only newly created
requests send a notification. This avoids retry-related tuple churn and maintenance,
not request rate-limit reservations or GPS-provider credits.

The municipal-cache fixture has 200 entries across four states. Six fixes in
one state invoke only its 50 geometry loaders once, leaving the other states unread.
Moving to a state with two overlapping incorporated towns loads those two
polygons and immediately requires authoritative lookup. Holes still exclude a
fix and exact outer-boundary fixes remain contained. The production context
loader uses the same lazy geometry wrapper; fixture load attempts do not measure
physical file I/O or battery. Cache metadata/lazy wrappers add a small per-town
allocation in exchange for avoiding unrelated full polygon allocations.

For a 5,000-point replay, preparation reads 4,999 break flags once and 10,000
forward/backward seeks read no further point objects. An exhaustive fixture
compares every break combination for routes of up to nine points, at all prefixes
and backward positions, against the frozen original algorithm. The index retains
one range per drawable segment; native source serialization still occurs when
the visible route changes. No replay accuracy or native-rendering speedup is
inferred from the break-scan count.

Android documents that an alarm with the same IntentSender is replaced by a new
`set` call, and that `setAndAllowWhileIdle` follows `set` scheduling semantics:
[AlarmManager reference](https://developer.android.com/reference/android/app/AlarmManager).
Existing notification tests also cover duplicate initialization, opt-out,
permission revocation and Monday/DST calculation.

The fourteen original driving/matching source invariants still match
`137e398706c0caf591ac13c091634a3711fc15e2`. Ten additional comparisons against
`31e0984` confirm unchanged visit merging/departure checks, visit metadata repair,
known-road completion, road upsert decisions, exact containment, official Census
lookup, map renderer, trip queries and resolver behavior after the cache class.
Regression outcomes and final validation are recorded in
[resource-deep-review-evidence.json](resource-deep-review-evidence.json).

All **1,088 Android unit tests across 68 suites** and **124 server tests** pass
with zero failures, errors or skips. Android lint has zero errors and 299 warnings,
unchanged from the prior run. Debug app/device-test APKs and the account Function
bundle build successfully. Server cases use isolated local PostgreSQL without
production database or map-provider traffic. Previous complete CI at `31e0984`
also passed release packaging, 27 native map/Keystore/persistence cases across
APIs 31/35/37 and optimized-release startup on APIs 31/37; new-head CI is tracked
separately in the PR.

No production service, quota, sampling rate, confidence threshold, geometry
precision, history retention, version number or release configuration changes.
These targeted savings do not establish a new DAU/driving-hours capacity or a
measured physical-device battery percentage.

## Exact write and buffer cleanup based on 221a0cb

Four further changes preserve existing results while avoiding repeated work:

| Area | Change and protections |
|---|---|
| Road metadata | Update an existing road only when one of its nine persisted metadata/geometry values differs. Compare the exact geometry string in SQLite; grouping, collision handling, bounds calculation and earliest/latest timestamps still run. Always recount visits, but write count/exactness only when they need to change. A late known-road completion updates last-driven time only when it advances. All point resolution, history-generation guards and write transactions remain. |
| CSV restore staging | Reuse one cleared ContentValues buffer per CSV table. Keep every field conversion, derived bounds/cells, validation, checksum, row/byte limit, insertion, transaction, cancellation and reset check. This extends the existing buffer-reuse approach to the CSV staging path. |
| Notification binding hashes | Format the same SHA-256 bytes directly into lowercase hexadecimal instead of constructing 32 formatters per digest. No hash input, algorithm, stored value, session binding, consent check, refresh interval or registration request changes. |
| Rejected notification OAuth | Cancel an unread rejected response body without awaiting cleanup. Keep the same authorization error, uncached failure, shared refresh behavior, retry, device selection, notification payload and delivery limits. Cleanup rejection is swallowed; slow cleanup cannot delay the original failure. |

A native-SQLite-backed Android trigger probe observes **40 road-row updates
before versus zero after** for 20 identical matching completions on each SDK
(31 and 37). A one-billionth-degree coordinate change and a display-name case
change still persist. The fixture also checks derived bounds, earliest/latest
times, legacy count/exactness repair and resolving six older locally matched
fixes without moving a newer last-driven timestamp backward. The tiny circular
longitude arithmetic difference is tolerated only in the bounds assertion; the
geometry string comparison remains exact.

A separate isolated Node 24.19.0 / SQLite 3.53.3 WAL fixture uses the same road
indexes and a 2,000-coordinate geometry. Across 20 unchanged-update transactions,
the affected statements produce **329,632 WAL bytes before and zero after**, with
identical final road data. No triggers are installed in this WAL fixture. This
is a targeted SQLite measurement, not Android filesystem, physical-device battery,
whole-app speed or DAU capacity evidence. Changed road values still write normally.
The predicates add comparisons to identify unchanged values; no geometry is
rounded or omitted and no additional database query is introduced.

The restore regression round-trips 500 varied GPS rows, mixed matching/retry
fields and four distinct quoted/Unicode road rows through real ZIP export,
staging and restore. Every CSV entry remains identical. Existing schema 9/10
compatibility, malformed inputs, checksum/size limits, stale-generation rejection
and recovery-copy tests remain in the suite. The allocation change is inferred
from the loop: one row buffer per table replaces one per record. No allocation
profiler or device speed percentage is claimed.

The digest regression compares a standard known SHA-256 value and 259 additional
inputs, including empty and Unicode strings, against the original formatter.
The OAuth regression first failed because cancellation was never requested;
it now confirms rejected and never-finishing cleanup do not change or delay the
authorization failure, and a later successful refresh recovers normally.

SQLite documents the comparison and column-affinity rules used by the metadata
predicates: [IS / IS NOT](https://www.sqlite.org/lang_expr.html),
[comparison affinity](https://www.sqlite.org/datatype3.html#type_conversions_prior_to_comparison).
The fourteen original driving/matching source invariants still match `137e398`.
Twelve additional comparisons against `221a0cb` confirm unchanged grouping,
geometry/bounds identity, visit merging and departure checks, strict local reuse,
restore validation, notification consent/registration/delivery, renderer and
municipal resolver. Final validation and limitations are recorded in
[resource-exact-write-evidence.json](resource-exact-write-evidence.json).

All **1,094 Android unit tests across 68 suites** and **125 server tests** pass
with zero failures, errors or skips. Android lint passes with zero errors and the
same 299 warnings. The debug app, device-test APK and account Function bundle
build successfully. These are local results; native-device and release CI status
is recorded separately in the PR. No release, deployment, version bump, schema,
quota, GPS sampling, matching confidence or history-retention change is included.

The following pass specifically targets actual quota consumption rather than
phone-only resources. See [QUOTA_REVIEW.md](QUOTA_REVIEW.md) for in-flight duplicate
Google-decode savings and compact counter storage, with complete local workerd
measurements, migration/retry safeguards and their capacity limits.

## Further phone work reduction

This pass compares with `d4a8f8a28231a7084c55d5bfacb07203644c8b44` and changes
three phone paths. It does not change server or provider requests, quota caps,
GPS recording, matching decisions, geometry generation or history retention.

| Area | Removed work | Preserved behavior |
|---|---|---|
| Previously driven road completion | Stop invalidating the shared road-count summary after a visit | This transaction changes matched flags, visit intervals, visit counts and last-driven time, none of which participate in the road-summary aggregate. New/grouped roads and history resets still invalidate or replace the cache; raw DB access still disables caching. |
| CSV backup export | Write escaped fields directly to the existing buffered UTF-8 writer instead of joining another complete row string | Same fields, order, escaping, spreadsheet protections, schema, snapshot, ZIP entries and restore validation. Applies to roads, road visits, visited places and place candidates. Geometry escaping still allocates its quoted field; backup size is not reduced. |
| Nearby exploration | Skip native publication when each complete JSON string is identical to that source's last successfully applied data | Every changed coordinate/property, transition to empty and missing/failed-source retry remains. Each newly installed source seeds the comparison with its exact constructor data. Camera fitting, callbacks, SQL, geometry generation, marker images and town boundaries are unchanged. |

On both Android SDKs 31 and 37, 20 repeated known-road completions retain the
same warmed shared road summary across two repository instances. Each result
also equals a fresh SQL summary, with the same pending-fix resolution and
last-driven time. A failed transaction rolls back and remains retryable, and a
subsequent new road invalidates counts. Both new tests first failed against the
old invalidation at the successful-completion cache assertion: four expected
failures across the two SDKs. This proves avoided cache clearing; it is not a
physical disk-read measurement.

The CSV regression compares original and streamed UTF-8 bytes for 202 rows,
including Unicode/control characters, literal apostrophes, formulas, quotes and
multiline fields, and round-trips all fields through the existing reader and
schema-12 unescaping. A separate 5,000-coordinate row verifies that the writer
receives individual escaped fields rather than an assembled row. Real ZIP
export/restore and the 5,000-road/5,000-fix archive tests also pass in the focused
suite.

The standalone [JDK allocation probe](../tools/CsvExportAllocationProbe.java)
calls the compiled app's `CsvUtil` and compares it with the original Kotlin
`joinToString` expression. Both use buffered UTF-8 output and the default ZIP
compression. Fixed ZIP timestamps let the fixture compare complete compressed
byte hashes. Three warmed, alternating rounds produced:

| Fixture | Median allocated bytes before | Median allocated bytes after |
|---|---:|---:|
| 20,000 short rows | 14,768,832 | 5,008,064 |
| 1,000 rows with 400 coordinates | 28,266,528 | 9,482,800 |
| 100 rows with 5,000 coordinates | 35,873,920 | 12,035,520 |

All nine before/after ZIP hashes match. These are thread-allocated JVM heap
bytes for this writing fixture, not peak app memory, Android allocation data,
backup storage savings or physical-device battery results. Timing varies across
rounds and is not used to claim a speed improvement. The writer makes more
small buffered calls while removing complete-row copies.

To reproduce after compiling the debug app, compile the probe with JDK 21 using
the app's `app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes`
and matching `kotlin-stdlib` JAR on the classpath. Run `CsvExportAllocationProbe`
with the same classpath plus its output directory, using `-Xms64m -Xmx256m`.
It requires no Android runtime, database, network or supplier credentials.

The nearby publication fixture sends 100 identical three-source snapshots and
observes three native-update callbacks instead of the former unconditional 300.
A one-billionth-degree JSON coordinate change and a property change still
publish; repeated clearing publishes once per source. Style installation seeds,
missing sources, synchronous failures and retry are checked. Comparison is exact
string equality and retains one applied value per source; a later equal string
may coexist with that prior string. The 25-cell overlay bounds this small retained
payload. JSON generation and DB queries are still performed; callbacks count
publication requests, not GPU time or rendered-frame speed.

All 196 focused tests across seven suites pass without failures, errors or skips.
Fourteen original source invariants still match `137e398`. Ten further checks
against `d4a8f8a` compare the entire tracking repository class except the removed invalidation,
the entire renderer except the three nearby publishers/publication/seeds, the
exporter except its four row writes, unchanged escaping/unescaping, and the
complete matching reuse, grouping, database, importer and tracking service files.
The account/gateway implementation and configuration are unchanged. The measured
fixtures and focused validation are recorded in
[resource-phone-work-evidence.json](resource-phone-work-evidence.json). Full-suite
and current-head CI status are tracked separately in the PR.

## Additional allocation reductions

This pass compares with `491e4e59bb7fca1aea9edc64b675eb17af3953fb`. It removes
six forms of redundant copying/retention. These are CPU/allocation improvements;
they do not increase a supplier allowance, change sampling, or reduce stored
history. There is no new persistent cache, background task, schema or network call.

| Area and fixture | Before | After |
|---|---:|---:|
| 10,000 achievement refreshes, 43 owned cards with current progress still above their goals | 59,600,000 JVM allocated bytes | 35,520,000 |
| 10,000 nearby projections of 25 cells, without a world-copy shift | 250,000 array copies / 20,000,000 JVM allocated bytes | 0 copies / 0 bytes in the projection loop |
| The same cell fixture with every cell requiring a world-copy shift | 20,000,000 median allocated bytes | 20,000,000; exact shifted coordinates retained |
| Equal 128,000-character snapshots jointly referenced by the current display field and publisher | Two distinct equal payload objects | One; same single native callback |
| Seal and verify a checkpoint with a 140-byte JSON payload | Three Buffer concatenations / 448 output bytes copied | One / 168 bytes |
| Decode four valid 100-byte notification plaintexts and reject a fifth corrupted token | Four plaintext concatenations / 400 bytes | Zero; same four deliveries, corrupted token dropped |
| One password hash plus one correct and one incorrect verification | Three extra 64-byte Buffer copies / 192 bytes | Zero; same authentication outcomes |

The JVM table uses medians of three warmed rounds, with the same JDK 21.0.10
fixture and separately saved baseline/current app classes. All 25 complete
achievement snapshot signatures match, including low progress after deduplication,
historical payout amounts, earned retired battery cards, an unknown ledger key,
and NaN/infinite/signed-zero distance. When historic rewards actually require
reconstructing the displayed progress, the objects still copy; that low-progress
fixture's median allocation is unchanged at 59,360,000 bytes. Each of the 43
unnecessary card copies in the high-progress case accounts for 56 observed JVM
bytes per refresh. This is not an Android heap layout or device-speed claim.

The grid regression compares raw coordinate bits across 2,161 center longitudes
and 41 geographically distributed cells, leaving its input unchanged. The only
production caller reads the returned corners when building nearby JSON; it never
mutates them. Allocate on the first actual shift and keep the original array
otherwise. Grid identity, cell size, wrapping arithmetic and output coordinates
are unchanged. The publisher now replaces its remembered reference when a new
immutable value compares equal. Failed updates retain the last applied value;
native publication counts and every changed-value update remain the same. The
reference fixture counts these two owners, not total live process memory or GC.

Checkpoint sealing now includes the cipher's final chunk directly in the final
IV/ciphertext/tag assembly. Checkpoint and notification opening still call
`final()` successfully before parsing or exposing plaintext, but use the update
buffer directly when the final chunk is empty. A nonempty final chunk still joins.
Tests reconstruct the original ciphertext assembly using the same IV and check
exact bytes, UTF-8 round trips, old ciphertext, tampered tags, wrong keys/scopes,
replay and impossible travel. No cryptographic parameter, IV generation, AAD,
fingerprint, nonce, token format, delivery gate or rate limit changes.

The password change returns/compares scrypt's existing owned Buffer directly.
[Node's scrypt contract](https://nodejs.org/docs/latest-v22.x/api/crypto.html#cryptoscryptpassword-salt-keylen-options-callback)
already returns a Buffer; the removed wrappers only cloned those same 64 bytes.
Password validation, salt generation, peppering, scrypt cost/memory parameters
and constant-time comparison are byte-for-byte unchanged.

The five allocation/retention regressions first failed against the baseline:
two Android tests and three server tests. All 157 focused Android tests and all
137 server tests subsequently pass with zero failures/errors/skips, and the
account Function builds. The server fixture is reproducible with:

```
node server/tools/check-buffer-resources.mjs 491e4e59bb7fca1aea9edc64b675eb17af3953fb
```

Compile [PhoneAllocationProbe.java](../tools/PhoneAllocationProbe.java) with JDK 21,
the compiled app classes, its matching Kotlin stdlib and Android SDK JAR; run it
against baseline/current class directories using `-Xms64m -Xmx256m`. No Android
stub methods execute. All fixture work is local, without provider/production
traffic. [resource-copy-reduction-evidence.json](resource-copy-reduction-evidence.json)
records the raw rounds and operation counts. Buffer counts measure affected copy
operations/output bytes rather than physical/slab allocations, storage, peak
memory or quota use. The comparisons/branches needed to omit copies remain;
no whole-app speed, battery percentage or DAU/driving-hours gain is inferred.

Fourteen original driving/matching source scopes still match `137e398`. Eighteen
additional comparisons against `491e4e5` constrain this pass to the six copy
changes and confirm unchanged complete map renderer, tracking/database/distance,
strict local reuse, scoring/Integrity/competition, account routing and gateway.
The full local run passes **1,104 Android tests across 68 suites**, with zero
failures/errors/skips. Lint passes with zero errors and the same 299 warnings;
debug app/device-test APKs and the account Function build successfully. The
previous commit's CI passes every verification job after Android 35's unchanged
job-only rerun passed all nine native cases following a lifecycle-transition
timeout. Current-head CI status is tracked separately in the PR.

## Further local lookup reductions

This pass compares against `d34f7c6` and changes three phone-only resource paths.
The server/gateway implementation and provider quotas remain identical. Review
of account/session, friend, push, verified submission and budget SQL found no
additional quota change with a demonstrated behavior-preserving benefit.

| Path and condition | Before | After |
| --- | --- | --- |
| Six theme/map/overlay/car/road enum lookups, last or unknown choice in each | Up to 35 preference acquisitions/string reads (3+4+4+6+9+9) | Six, one current read per getter |
| Complete starting-place baseline | Six completeness statements/cursors | One |
| Country-only incomplete baseline, first retry | Two completeness cursors | Two; still recheck after the retry write |
| Partial baseline release | Four completeness cursors, plus three missing-kind queries | Two completeness cursors, plus the same three queries |
| JVM: 10,000 ordinary `Pine Hill` municipal-name lookups | 10,720,000 allocated bytes | 4,480,000 |
| JVM: 10,000 `Township of Pine Hill` lookups (earliest prefix match) | 6,320,000 allocated bytes | 5,760,000 |

The six preference getters read once before searching their existing enums.
Every saved choice, unknown/removed fallback and next-call preference edit is
covered on SDKs 31/37. There is no persistent selection cache. Worst-case old
counts are derived from the six existing loop lengths; the baseline regressions
reproduce multiple acquisitions at the second choice, and current tests measure
one acquisition/string read for every choice, unknown value and removal.
These are API-call counts rather than physical disk reads.

Completeness uses the original predicates, binding values and enum order in a
single CASE statement. [SQLite documents lazy CASE evaluation](https://www.sqlite.org/lang_expr.html#the_case_expression),
so later kinds are still skipped when an earlier kind is absent. A complete
result is reused only when no writes intervene; incomplete paths still recheck
after their retry or missing-marker writes. All 64 resolved/missing combinations
match the original query algorithm on each SDK. Actual CursorFactory probes
reproduce six/four old cursors and one/two new cursors. Ordinary discoveries
still perform zero completeness queries. Rewards, original starting fix,
pending-candidate lifecycle, missing-town markers and transaction/locking rules
retain their original behavior. Small SQL/binding templates are retained for
the process lifetime; the fixture does not measure physical I/O or query time.

Municipal identity matching compares the existing type text and separator in
place rather than constructing two temporary strings per rejected type. The
original normalization, type order and exact matching boundaries remain.
The old algorithm agrees on 2,250 combinations per SDK, including casing,
whitespace, Unicode, empty/truncated names and overlapping municipal labels.
[MunicipalityAllocationProbe.java](../tools/MunicipalityAllocationProbe.java)
runs the same actual compiled app function against old/new class directories.
Its JDK 21 ThreadMXBean rounds include unchanged normalization, reflection and
Pair output costs. Unsafe is used only by the standalone fixture to bypass the
Android database constructor; no Android stub methods execute. The probe is
not part of the Android app. These allocations are not Android peak memory,
storage, timing, battery or server-capacity measurements.

All 22 focused tests pass. Sixteen regression cases first fail against the old
code with only the expected repeated-read/cursor assertions. Full local
validation is recorded in [resource-lookup-evidence.json](resource-lookup-evidence.json).
Sixteen comparisons from [check-lookup-sources.mjs](../tools/check-lookup-sources.mjs)
constrain this pass to the stated changes, alongside the fourteen original and
eighteen preceding source checks. GPS acceptance/retention, matching, distance,
road storage, municipal geometry/containment, drawing, all preference setters,
consent, equipment entitlements and server code remain unchanged in this pass.
No provider credits or production traffic were used.

The full local run passes **1,126 Android tests across 70 suites**, with zero
failures/errors/skips. Lint passes with zero errors and the same 299 warnings;
debug app and device-test APK builds pass. The preceding `d34f7c6` CI passes
every verification job, including server tests, release packaging, native maps
on APIs 31/35/37 and release startup on APIs 31/37. Downloaded reports confirm
1,104 unit cases, 27 native cases and four successful release launches. Fresh
head CI is tracked separately in the PR. The server implementation is unchanged
in this pass, so its previously passing 137-test local suite was not repeated.

## Session, shop and GPX follow-up

This pass compares against `93ff4e6`. The three changes reduce local work and
preserve existing behavior; no additional provider/server quota saving was found.

| Path | Before | After |
| --- | --- | --- |
| Load an encrypted account session | Two explicit temporary input-array slices | None; read the same IV/ciphertext ranges directly |
| Check shop balance for an item not yet owned | Two total-query statements/cursors | One; both SUM scans remain |
| Already-owned shop item | Zero balance queries | Zero |
| JVM/SDK 31 fixture: actual GPX export of 10,000 points | 13,770,704 median allocated bytes | 13,530,904 (239,800 fewer) |
| JVM/SDK 37 fixture: same GPX export | 13,292,784 median allocated bytes | 13,052,984 (239,800 fewer) |

Session loading uses the documented range overloads of
[GCMParameterSpec](https://developer.android.com/reference/javax/crypto/spec/GCMParameterSpec)
and [Cipher.doFinal](https://developer.android.com/reference/javax/crypto/Cipher).
The two omitted explicit copies together held the decoded encrypted record's
byte count. This is a source-derived copy count, not a measurement of all
provider/internal allocations. The GCM spec still makes its own required IV
copy. AES-GCM, key alias, 12-byte IV, 128-bit tag, stored format, synchronization,
saving/fresh IVs and failure handling are unchanged. Authentication still
finishes before a Session can receive plaintext. Local tests exercise empty,
ASCII, Unicode/NUL and long tokens, plus 32 invalid-IV/tag/key/truncation cases
per SDK. Invalid data still clears the session and revokes verification consent.
This JVM fixture supplies a fixed software key through a fixture KeyStore;
real JVM AES-GCM executes, but it is not an Android hardware-Keystore test.
The existing device test now also checks Unicode and corrupted-tag rejection;
fresh-head device execution is tracked separately in CI.

Shop totals are returned in separate columns of one statement, inside the
existing purchase transaction and history lock. Subtraction remains Kotlin Long
arithmetic; it is not moved into SQLite, where overflow can change numeric type.
Empty totals, free purchases, ownership, insufficient funds, exact balances,
negative balances and individual SUM overflow retain their behavior. Actual
CursorFactory probes reproduce two old cursors versus one new cursor on each
SDK. This does not halve rows scanned or change entitlement/reward rules.

GPX formatting reuses one small export-local StringBuilder and still creates
an immutable point String for one buffered write per point. The existing 32-KB
writer, original doubles/timestamps, snapshot query, privacy cutoffs, validity
checks, segment gaps, reset cancellation and final flush checks remain. Tests
compare exact old/new XML bytes and the same 10,000-point SHA-256. The modest
allocation saving above measures the calling thread around actual full exports,
including SQLite, formatting and byte-array output, after three warmup exports
and across three measured rounds per SDK. It is not Android peak memory, device
speed, battery, backup-size or quota evidence.

All 12 focused tests and the full **1,138 Android tests across 73 suites** pass
with zero failures/errors/skips. The balance-cursor regression first fails
against old code on both SDKs. Lint passes with zero errors and the same 299
warnings; debug app and device-test APK builds pass. Seventeen comparisons in
[check-session-export-sources.mjs](../tools/check-session-export-sources.mjs),
fourteen original driving checks and eighteen preceding copy-scope checks pass.
GPS recording, road matching, geometry/rendering, all server/gateway code and
the previous lookup improvements remain unchanged. The unchanged server's
previously passing 137-test local suite was not repeated. Raw measurements,
validation and CI context are recorded in
[resource-session-export-evidence.json](resource-session-export-evidence.json).
The preceding `93ff4e6` CI now passes every verification job. Android 35 passes
on an unchanged-source rerun after its original emulator refused the adb
connection before instrumentation started. Downloaded reports confirm 1,126
unit cases, 27 native cases and four successful release launches. These are
the preceding head's results; fresh-head native/release results are tracked
separately in the PR.
No version bump, release, deployment, provider credits or production traffic
are involved.

## Final road-overlay buffers

One further change, against `e1a718c`, avoids copying final prepared-road arrays
when their allocated length already equals the returned logical length. The
producer is local to one preparation and has no remaining writer after return.
There is no shared cache or retained spare capacity. Part buffers that are
cleared/reused while splitting roads still use their original defensive copies;
final buffers that need trimming still copy to their original exact length.

| Actual OverlayRoads.prepare fixture | Before | After |
| --- | --- | --- |
| 128 separate roads, 256 points each; 65,536 coordinate values and 129 chain starts | Two final array copies | Zero final array copies |
| Same fixture, calling-thread JVM allocation median | 15,315,352 bytes | 14,790,512 (524,840 fewer) |
| Joined-road control, requiring both trim copies | 945,112 bytes | 945,112 bytes |

All three measured rounds give those exact counts on both SDKs 31/37, after
four warmup preparations per fixture. The omitted raw payload copies total
524,804 bytes; the measured JVM allocation difference also includes array
headers/alignment. The allocation measurement includes unchanged JSON parsing
and graph work. It is not Android peak memory, speed, battery, storage, provider
quota or driving-hour evidence. Savings are conditional on a final array
already fitting; the joined-road control retains its original allocations.

Before/after signatures match every coordinate's raw bits and every ordered
chain start across 240 scenes per SDK: separate/joined roads, repeated date-line
crossings, T junctions, loops and duplicate points. Ownership tests mutate one
preparation's returned arrays and confirm that other preparations stay intact.
All **204 targeted tests across six suites** pass with zero failures/errors/skips,
including existing native-graphics fog/road rendering, turn geometry, travel,
date-line and progression checks. Lint passes with zero errors and the same 299
warnings; debug app/device-test APK builds pass. The full new-head suite and
device/release checks run in CI; they are not presented as a completed local run.

[check-overlay-buffer-sources.mjs](../tools/check-overlay-buffer-sources.mjs)
verifies the entire renderer outside the two final ownership decisions and
every other app source/asset/manifest. All graph/geometry math, snap/join/visit
gates, split handling, GPS/matching, requests, town-border code, server/gateway,
build and workflow configuration remain unchanged. The preceding `e1a718c` CI
passes every verification job; downloaded reports confirm 1,138 unit cases,
27 native cases and four successful release launches. Raw rounds, signatures,
scope and validation are in
[resource-overlay-buffer-evidence.json](resource-overlay-buffer-evidence.json).
No other proposed quota/resource change was retained in this pass. No version
bump, release, deployment or provider/production test traffic is involved.

## Direct UTF-8 response decoding

This pass compares against `889ba9c`. Five response paths previously called
`ByteArrayOutputStream.toByteArray()` solely to decode that copy as UTF-8:
account replies, service switches, Geoapify matching, LocationIQ matching and
the fallback geoBoundaries country/region display client. They now decode the
accumulator directly with its API-31-compatible `toString(String)` overload.
There is no retained buffer or new cache. The shared reader is named
`readBoundedUtf8` to make its string result explicit.

The same byte read loops, chunk sizes and caps remain: 64 KiB for accounts,
4 KiB for switches, 4 MiB for matching and downloaded fallback boundaries,
and 64 KiB for fallback metadata. Switches still reject a 4,097th byte before
parsing or saving. Oversized matching responses still fail before decoding;
replacement of malformed UTF-8, exceptions and caller ownership of input
streams remain unchanged. Requests, provider calls, HTTP rate reservations,
response caches, freshness, redirects, parser/geometry decisions, confidence,
GPS recording, turns, gaps and town borders retain their existing behavior.

This avoids one payload-sized byte-array copy per decoded response. Network
bytes, provider credits, server quotas and maximum driving hours do not change.
No additional quota reduction with a demonstrated equivalent result was found.
Other candidates requiring altered freshness, cooldowns, retry semantics or
geometry behavior were left alone.

`ResponseDecodingTest` exercises real account requests and asynchronous service
refreshes through local fake HTTPS connections: split Unicode, malformed UTF-8,
exact byte caps, oversized replies, HTTP errors, invalid/absent bodies, read
failure, offline defaults, stream closure and disconnect. Both implementations
pass the same checks. `BoundedInputTest` additionally compares complete decoded
strings, including every two-byte combination, split four-byte Unicode and
truncated/surrogate sequences. Its byte-limit cases include empty streams,
zero/negative limits, chunk boundaries, exactly 4 MiB and one extra byte, plus
unchanged read amounts and propagation of the original I/O exception.

The before/after fixture runs the actual compiled account request and shared
reader on JDK 21 with Robolectric SDKs 31/37. It measures calling-thread JVM
allocations with inputs built outside the measurement, warmups and three rounds.
These results are not Android peak memory, battery, speed or provider-quota
measurements. The exact raw rounds and final validation are recorded in
[resource-response-decoding-evidence.json](resource-response-decoding-evidence.json).

[check-response-decoding-sources.mjs](../tools/check-response-decoding-sources.mjs)
reverses just the decoding edits and compares each entire affected file with
`889ba9c`. It also verifies that every other app source, asset and manifest,
the complete server/gateway, build configuration and workflows are unchanged.
This is the current source guard; earlier guards that require completely
unchanged provider-client files describe their respective earlier passes.

| Per decoded response in the JVM fixture | Allocated bytes avoided |
| --- | ---: |
| 4 KiB account reply or shared-reader input, both SDKs | 4,112 |
| 64 KiB account reply or shared-reader input, both SDKs | 65,552 |
| 1 MiB shared-reader input, both SDKs | 1,048,592 |
| 4 MiB shared-reader input, SDKs 31/37 | 4,194,320 / 4,194,296 |

The small difference between the 4 MiB JVM samples is retained in the raw
rounds; the source-derived payload-copy removal remains exactly 4,194,304
bytes. These conditional fixture savings are not a per-drive/fleet estimate.
All **293 targeted tests across twelve suites** pass, including local provider
network/cache/fallback fixtures and turn/geometry validation. Both new test
classes also pass against their original decoding pipelines. After hardening
the isolated HTTPS fixture to permit missing Robolectric SDK downloads, its
eight account/config cases pass again. Lint passes with zero errors and the
same 299 warnings; debug app/device-test APK builds pass. Eleven source checks
preserve the implementation outside the decoding edits. Full fresh-head CI is
tracked in PR #105. The preceding `889ba9c` native reports confirm 27 cases
across APIs 31/35/37 with no failures/errors/skips; they are not presented as
fresh-head device results. No version bump, release or deployment is included.


## Batch-local geometry parsing and town feature construction

Compared with `a21c395`, two local paths reuse JSON that already exists without
changing candidate queries, matching decisions or geographic geometry.

- `KnownRoadReuse.find()` holds one parsed saved-road geometry for that call.
  Equality includes the entire original JSON string, so a changed shape is
  reparsed even when its segment ID stays the same. Every eligible fix still
  queries current SQLite candidates, including newly added parallel roads.
  Only bodies of at most 16,384 UTF-16 characters are retained. Larger bodies
  follow the original parser path; the cache is released after the batch.
- A town partition's freshly encoded polygon is passed into its feature instead
  of immediately parsing the serialized copy again. The serialized copy remains
  available for the shown overlay. Rare signed-zero coordinate tokens use the
  original parser path, preserving its exact `-0` to `0` normalization. Border
  union, line merging, clipping, holes, geometry repair and containment are unchanged.

Actual before/after production methods were compared under local Robolectric
SDKs 31 and 37 on JDK 21. After eight warmup pairs, eleven rounds alternated
old/new ordering and measured sixteen operations per sample; the table contains
calling-thread allocation medians for each complete sample.

| Fixture | SDK | Before | After |
| --- | --- | --- | --- |
| 16 batches of 100 fixes on one 128-coordinate saved road | 31 | 75,673,088 bytes | 15,332,640 bytes |
| Same saved-road fixture | 37 | 75,089,952 bytes | 15,083,136 bytes |
| 16 complete renders of one 2,001-vertex town polygon | 31 | 111,304,064 bytes | 85,302,560 bytes |
| Same town fixture | 37 | 111,303,008 bytes | 85,301,408 bytes |

The local allocation reductions are 79.74-79.91% and 23.36%, respectively.
Every saved-road result and all 100 accepted IDs match baseline. Fourteen town
cases per SDK compare complete fill strings, outline strings and shown overlays,
including official overlaps, holes, islands, signed zero and complex polygons.
Their combined fill/outline SHA-256 is
`5da5c342281426db1a29b42a2b13638d986973823cf26eabb1ea800f1aee17c1`
on both SDKs. The source comparison reconstructs all four changed production
files after removing only these parsing/handoff edits; the remaining GPS,
continuity, distance, acceptance, containment and border algorithms match baseline.

The saved-road cache adds a small object and retains one bounded parsed tree
until its batch ends; it does not cache road candidates or completed decisions.
These fixtures are not measurements of Android peak RAM, physical battery life,
phone wall time, provider credits, storage saved or fleet driving capacity.
The recorded CPU samples have a coarse host clock. See
[resource-local-geometry-evidence.json](resource-local-geometry-evidence.json)
for the actual samples, methodology, bounds and limitations.

A fixed-row fog-noise precomputation was also evaluated and reverted: although
all pixels matched, its timing did not improve consistently across SDKs. No new
disk cleanup was selected. Current boundary caches are already compressed and
bounded; exports already remove orphaned snapshots. History, valid offline
boundaries and restore safety backups remain retained.

The same pass fixes deletion-accounting drift in the boundary-cache budget.
Removing a zero-byte corrupt entry previously left its file count charged;
a failed deletion could instead subtract a file and its bytes while it remained
on disk. Update the counters only when a previously present file is actually
gone, including empty files. Two regression tests fail before the fix: one
retained valid file is incorrectly counted as two after empty-file removal,
and as zero after a simulated deletion failure. The final assertions compare
both counters to actual directory entries and retain readable valid polygons.
The AtomicFile deletion call, failure handling, cache caps and eviction policy
remain unchanged. This is a bookkeeping fix, with no claimed disk-space or
battery-life reduction.

Validation covers 533 unique targeted Android cases across 21 suites (531 in
the broad pass, then 55 affected cases after the additional cache fix), all with
zero failures/errors/skips. Four additional baseline comparison cases pass.
Final debug lint has zero errors and the same 298 warning IDs/messages; app
and device-test debug APK builds pass. Five source checks reconstruct the
changed algorithms and confirm another 207 production/configuration/supporting
files byte-for-byte unchanged, excluding this review and its new evidence file.


## Exact overlay state and repeated viewport work

Compared with `3ce5059`, the map's overlay state compares complete immutable
`PlaceOverlayData` values instead of allocating `key:geometryHash` strings.
This fixes missed updates for hash collisions and popup-only metadata changes.
The bounds cache uses complete values too, so colliding shapes cannot share an
envelope and be incorrectly culled. Unknown envelopes stay included; remember
the null result until the existing next-load clear instead of parsing it again
on every camera idle callback.

Publication records a snapshot only after both fill and boundary sources exist
and both uploads complete. Missing sources and failed uploads remain retryable.
An atomic snapshot pairs data and cache generation. Equal worker refreshes adopt
the current payload references, and compare-and-set prevents resurrection of
old publication state after a concurrent style/reset. Snapshot records reference
already-loaded immutable overlay objects; no coordinate/string/tree copy is added.
The applied record retains those references until a replacement or reset.

A comparison of the actual previous and changed `postPlaceOverlay` methods on
SDKs31/37 reproduces three missed changes per SDK: a different valid polygon
with the same Java String hash, a changed name and changed population. The old
renderer queues zero updates; the new renderer queues all three. The collision
fixture contains two valid polygons in different hemispheres, both with hash
`997868208`; exact geometry comparison distinguishes them.

For 100 unchanged refreshes of 1,000 overlays, median calling-thread allocation
falls from 7,203,200 to 409,600 bytes on both SDKs: 67,936 fewer bytes per refresh,
or 94.31%. Both versions still queue zero updates for identical data. This
measures the actual unchanged refresh method with equal reflection overhead,
30 warmup pairs and eleven alternating rounds. Host CPU timing is too coarse
to report a reliable percentage. These numbers do not measure Android peak
memory, battery life, disk storage, provider credits or fleet capacity.

All 190 focused cases across ten suites pass, alongside four temporary actual
baseline-comparison cases. Tests include exact geometry/metadata, retry/reset,
a controlled worker/reset race, equal-payload retention and unknown-envelope
reuse. Final lint has zero errors and the same 298 warning IDs/messages; app
and device-test debug APK builds pass. Two source checks reconstruct the whole
prior renderer outside publication/bounds wiring and preserve another 211
protected files, allowing only checkout newline normalization. Geometry,
clipping, survey-border merging, containment, GPS, matching, fog, data and
server/gateway algorithms remain unchanged.

See [resource-overlay-state-evidence.json](resource-overlay-state-evidence.json)
for the complete fixtures, allocation values and limits. The preceding CI run
at `3ce5059` is fully green after an unchanged-code API31 job rerun. Its first
API31 attempt failed a Terrain-imagery source assertion; the rerun passes all
nine native cases. Downloaded unit reports confirm 1,167 cases across 77 suites
with zero failures/errors/skips. Both release-startup jobs also pass. New-head
CI is tracked separately in the PR.

## Local road identity formatting and boundary-cache metadata

Compared with `1b5600b`, road identity formatting reuses one local builder and
Java Formatter across the coordinates in one canonical key. Locale, decimal
precision, input doubles, reversed-route handling and lexical selection stay
identical. The complete old/new compiled helpers produce identical text for
200 seeded traces per SDK; signed-zero/rounding, alternate-locale and real
SQLite collision-ID regressions also pass. No saved ID or geometry changes.

For four 1,000-coordinate helper calls, median calling-thread allocation drops
from 5,112,424 to 4,653,912 bytes on SDK31 and from 5,336,424 to 4,696,808 on
SDK37: 8.97% and 11.99%, or 114,628 and 159,904 fewer bytes per call. One small
builder/formatter lives until the call ends, replacing per-coordinate objects.

Over-budget boundary-cache ordering snapshots each timestamp once under the
existing mutation lock. It keeps the same timestamp/name order, stable ties,
File objects, cache caps, keep-current-write exclusion and deletion accounting.
Every later cleanup reads fresh timestamps. Within-budget checks retain their
early return, so they never build this snapshot. The 6,000-file fixture reduces
File.lastModified calls from 135,888 to 6,000 with identical eviction order.
Sorting 1,024 real temporary files on this Windows host takes medians of
1.921/1.942 seconds before and 0.105/0.111 seconds after across SDK31/37 runs.
These timings cover the sorter, not complete cleanup or Android performance.

The sorting tradeoff is a temporary record per file plus list buffers during
over-budget maintenance. This is worthwhile for the reduced metadata calls;
measured total allocation decreases, but peak retained RAM was not measured.
No persistent metadata cache or retention-policy change is introduced.

A review of recent changes retains their disclosed costs: per-batch road parsing
holds one bounded small geometry tree; exact overlay state holds one applied
snapshot referencing existing data; explicit stopped-data compaction can take
longer and require temporary disk space after deletion commits. None justifies
claiming that every optimization has literally zero resource cost. GPS capture,
matching acceptance, distance, town geometry, provider calls and server quotas
are unchanged by this pass.

All 190 targeted cases across four suites and four additional temporary
comparison cases pass on SDKs31/37. Final lint has zero errors and the same
298 warning IDs/messages; app and device-test debug APK builds pass. Three
source checks reconstruct the two production files outside these exact edits
and preserve another 212 protected files. See
[resource-formatter-cache-evidence.json](resource-formatter-cache-evidence.json)
for raw measurements, equivalence fixtures, bounds and limitations. The prior
`1b5600b` CI passes every verification job, including native maps and release
startup; downloaded unit XML confirms 1,181 cases across 78 suites
with zero failures/errors/skips. New-head CI is tracked separately in the PR.

## Exact repeated-road text and paired overlay serialization

Compared with `7c186af`, repeated road saving first checks whether the stored
primary geometry is exactly the incoming text. Input parsing, coordinate validity
and bounds calculation still happen before this check. Exact equality proves
the same canonical geometry, so skip its redundant full formatting and stored
JSON reparse. Different text keeps the entire original canonical/collision path,
including reversed routes and recovering an alternate after primary deletion.
No road candidate, completed decision or geometry is cached between calls.

The added cost is one exact string comparison. For four full repeated upserts of
one 1,000-coordinate road, median calling-thread allocation changes from
14,317,000 to 2,850,440 bytes on SDK31 and from 14,476,936 to 2,818,376 on SDK37:
80.09% and 80.53%, or 2,866,640 and 2,914,640 fewer bytes per save. Both complete
upsert implementations are invoked reflectively with the same argument
structure; input validation, grouping and visit/update logic still run. The
whitespace-different control has identical allocation before/after, retaining
full canonical work. Actual old/new helpers return identical IDs for 105 inputs
per SDK, with combined SHA-256
`00ab3629baf693bb38838d0e1cda9c5dd2a4855c2e6b07d7788d56f869162432`.

Country/state feature conversion now parses a polygon locally once for both
fill and raw outline collections. Shared coordinate arrays are read-only and
never escape as mutable trees. The existing signed-zero normalization fallback
remains. Map branches without official Census towns also skip the unused empty
Census collections. Actual complete preparation calls allocate 20,065,768 to
13,571,944 bytes on SDK31 and 20,065,352 to 13,571,816 on SDK37 for four builds
of one 2,001-vertex state polygon: 32.36% fewer bytes. The small paired string
result is included; no persistent cache or new snapshot retention is added.

All 56 complete renderer fixtures per SDK have identical fill and outline
strings, including ordinary country/state and empty, non-US, official
single-layer, mixed-layer and mixed-country town paths. Their combined SHA-256
is `b8aa56e27a71d2bb20cbfa9c319cff38ecae9f422885c705e57646644da1ccca`.
Permanent tests additionally preserve holes, islands, signed zero, extra
dimensions, malformed input errors and unsupported/short-ring behavior.

All 334 targeted repository cases across eleven suites pass. Four additional
actual compiled-baseline comparison cases pass; the twelve new repository cases
are rechecked as well. Final lint has zero errors and the same 298 warning
IDs/messages; app and device-test debug APK builds pass. Five source checks
reconstruct the three production files outside these exact edits, preserve the
old raw-outline traversal and confirm another 212 protected files unchanged.
No GPS, matching, distance, grouping, turn/gap, JTS border, provider, schema or
server/gateway algorithm changes. No additional storage cleanup was selected.

See [resource-exact-geometry-evidence.json](resource-exact-geometry-evidence.json)
for raw median measurements, operation counts and measurement bounds. These
host fixtures are not physical battery, Android peak memory, disk storage, provider quota or
fleet capacity measurements. Map measurements stop after actual preparation
queues its exact strings; they do not measure native map rendering. New-head CI
is tracked separately in the PR.

## Large backup CSV fields without an escaped copy

Compared with `528813a`, a large quote-free array field is handed directly to the
existing buffered CSV writer between two quote characters. Eligibility requires
at least 1,024 UTF-16 code units, enclosing square brackets, a comma and no double
quote. The initial `[` is already safe under spreadsheet formula rules, and the
comma already requires CSV quoting. Other values keep the unchanged escaper,
including short arrays, quoted coordinates, formulas and unusual road names.
No CSV schema, UTF-8, ZIP buffer, flush or restore-validation changes.

The actual compiled old/new road-CSV export methods write 1,000 SQLite roads
with 400 coordinates each and finish their ZIPs. Median calling-thread allocation
falls from 13,820,296 to 7,884,296 bytes on SDK31 and from 13,818,792 to 7,882,792
on SDK37: 5,936,000 fewer bytes per fixture, or 42.95-42.96%. With fixture entry
times fixed, all 81,978 ZIP bytes agree. Both SDKs produce SHA-256
`54c8e15d3682430e203b1be5590c65b7e6510f40be53f1054c0be61143e0598a`.

The tradeoff is an eligibility check per field and two additional buffered quote
writes per eligible field. The 10,000-row small-field control allocates the same
bytes and takes median host wall times of 3.971 to 4.237 ms on SDK31 and 4.066 to
4.396 ms on SDK37. That is about 0.27-0.33 ms extra per 10,000 rows, accepted for
removing large field copies. No new buffer, persistent cache or extra flush is
added. These local results do not establish physical battery, Android peak RAM,
archive-size, provider-quota or fleet-capacity gains; no speed gain is claimed.

A separate direct road-update argument-array experiment was evaluated and fully
reverted: it saved only about 33 KB per 100 short-road updates without a convincing
timing improvement. The complete final TrackingRepository matches baseline.
The retained production patch changes only the CSV field handoff.

After that reversion, all 196 targeted cases across seven suites pass, covering
tracking/road IDs, CSV, export, restore and multi-window large history. Four
additional actual CSV baseline-comparison cases pass. One new property case
compares 2,066 fields across thresholds, quote/formula/control/Unicode/surrogate
inputs and exact parser round trips. The existing 5,000-coordinate writer test
now requires the unassembled field length. Final lint has zero errors and the
same 298 warning IDs/messages; app and device-test debug APK builds pass.
Three source checks preserve the entire repository, constrain CSV changes to
this handoff and confirm 215 protected files unchanged, including all
GPS/matching, distance, town borders, schema, exporter/importer and server code.

See [resource-csv-field-evidence.json](resource-csv-field-evidence.json) for raw
median measurements, the rejected experiment and limitations. The preceding
`528813a` CI passes every verification job, including all three native-map jobs
and both release-startup jobs. Downloaded unit XML confirms 1,207 cases across
82 suites with zero failures/errors/skips. New-head CI is tracked separately
in the PR.
