# Server, storage and phone resource review

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
