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
That device test requires emulator/device CI; compiling its APK is not execution.

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
