# Repository audit — October 15, 2026

Status: final verification in progress. This log describes this investigation only; earlier branch changes are not counted as new fixes.

## Baseline and constraints

- Repository: `AntnyGamer/RoadConquest-Beta`, existing branch `ui-modernization-tile-milestones`, existing PR #94.
- Starting commit: `51d8907bfccfce9964f758b974c55e4a8668c65f`.
- No repository AGENTS.md files found. Initial checkout clean.
- No new branch, version bump, merge, release, production data operation, or deployment is authorized by this audit.
- Maintain existing history, purchases, tracking accuracy, reward payouts and verification requirements.

## Coverage and verification ledger

| Area | Work performed | Status / outstanding evidence |
| --- | --- | --- |
| Backend accounts, rate limits, authentication, privacy, deletion | Read application/security/database configuration and Fetch adapter; trace deletion and session behavior | Review in progress |
| Competitive cells, receipts, continuity, friendships | Read all scoring, Integrity, checkpoint and competition code and schema; inspect locks and retry behavior | Review in progress; real Google/Play behavior requires credentials and eligible hardware |
| GPS/background service | Trace location acceptance, subscriptions, shutdown flushing, discovery scheduling | Review in progress; physical battery measurements require a device |
| Storage, backup and restore | Read schema/upgrades, reset flow, importer and exporter | Review in progress |
| Purchases and ads | Read ownership revisions, acknowledgement, lifecycle and consent handling | Review in progress; live purchases/ads require Play/AdMob testing |
| Android build and release | Inspect Gradle, manifest and workflow gates; locate local JDK 21 and SDK 37 | Baseline tests/lint running |
| Maps, matching, places, UI, progression, cumulative diff | Pending further passes | Not yet complete |

## Iterative review record

1. **Discovery pass — data flow and backend:** inventory all tracked files and source sizes; trace account API, Fetch adapter, all SQL/schema, verification/receipts, friendship locks, tracking callbacks/teardown, SQLite migrations and generations, backup validation and progression. Investigated A1–A4 using source traces and isolated database measurements.
2. **Discovery pass — rendering and build boundaries:** inspect grid-to-Mercator projection, world/detail/regional fog handoffs and mask drawing, town cache/partition/lookup paths, replay sampling, CI artifact consumers, release gates and Windows baseline failures. Found A5–A7. Re-read fixes and their neighbors after implementing them.
3. **Substantive follow-up — security, tracking and storage:** recheck auth/rate-limit query semantics, exact retry/continuity/Integrity checks, friend consent/visibility/moderation, deletion cascade dependencies, stale repository/cache generations, import/export locking, 32KB request bounds, location source/age/speed filters, matching confidence/geometry gates, shutdown flushing and retry timers. No further change justified. Keep existing GPS intervals and sampling, strict verification and lossless data paths.
4. **Substantive follow-up — UI, lifecycle and release:** review cumulative branch changes against main in backend, account/achievements/challenges/leaderboard/shop/main/trips, progression, population cache and XML/theme/navigation resources; inspect ad/VIP ownership and lifecycle cleanup, large-history/replay bounds, Gradle/manifest/R8 and release workflow gates. No additional credible change justified so far; final tests/static-analysis results still pending. No arbitrary design or economy changes made by this audit.
5. **Documentation consistency:** update Beta 49's existing compatibility notes to explicitly describe the fog projection correction and additive backend indexes. Remove the now-inaccurate blanket statement that fog geometry/database schema are unchanged. No version bump or additional functional change.

The last two passes deliberately use different areas and checks. Passing tests alone is not the stopping criterion. Physical-device/credential-dependent checks below remain explicitly limited.

## Findings and fixes

| ID | Classification / severity | Root cause and impact | Change / evidence |
| --- | --- | --- | --- |
| A1 | Confirmed performance defect / medium, scaling | Weekly friend standings scan all users with a correlated relationship predicate, despite a 50-friend limit | Build participant IDs from the indexed friendship edges first, then join users. Local PostgreSQL synthetic workload: 100,000 users, 25,000 relationships; original query 453.412 ms / 426,910 buffer hits, replacement 0.154 ms / 15 hits, identical rows. These are warm local measurements, not a production latency promise. |
| A2 | Confirmed storage/query optimization / medium, scaling | Foreign-key cascades check unindexed `cell_receipts.user_id`, `competition_receipts.user_id` and `friendships.requested_by`, scanning unrelated users' rows on account deletion | Add three idempotent indexes. At 50,000 receipts/table and 25,000 relationships, empty-account FK probes dropped from 2.965/4.913/2.014 ms to 0.012/0.011/0.011 ms; buffer hits 770/770/209 to 2 each. Tradeoff: extra index storage and insert maintenance. No production schema was applied. |
| A3 | Confirmed exploration reliability defect / medium | Census state fallback assumes one Reader.read fills the response. Legal short reads are incorrectly rejected as oversized | Read to EOF with the existing 4096-character bound; regression covers fragmented reads and both sides of the size limit. |
| A4 | Confirmed exploration metadata defect / medium | Cached municipal identity copies the new visit timestamp but reuses the old visit's latitude/longitude | Copy the current verified seed coordinate with its timestamp. No change to polygon containment or reward eligibility. Strengthen the existing batch-cache regression with distinct coordinates. |
| A5 | Confirmed rendering defect / high visual impact near wrap seams | Per-corner longitude wrapping puts opposite edges of one tile in different world copies. A date-line tile becomes a nearly world-wide reveal strip | Wrap a grid square by its midpoint, preserving its four corners in one world copy. JDK 21 numerical reproduction: original x edges 512 and 0.026871 on a 512px world raster (width -511.973129), versus the intended 0.026871px tile width. Add seam/antipodal regression and verify physical positions modulo one world. No stored exploration changes. |
| A6 | Confirmed avoidable CI bandwidth / low | Both release-startup jobs download the full APK+AAB+mapping archive, although only the APK is used | Publish a separate APK-only artifact and consume it in startup jobs. Initial full archive was 189,974,631 bytes. Exact savings pending APK measurement. This does not claim to solve GitHub's external artifact-host HTTP 503 egress failures. |
| A7 | Confirmed development-host restore defect / medium | Existing recovery ZIP cannot be overwritten by `File.renameTo` on Windows, so repeated restore fails before updating live data | Use `Files.move` with `ATOMIC_MOVE` and `REPLACE_EXISTING`, in the same private directory. No copy/delete fallback; failure keeps prior recovery data and stops before replacing the database. Existing repeat-restore regression failed on both SDK configurations before the fix. Add a failure-path test retaining live tables and an existing target. Android's Unix provider implements atomic move with POSIX rename; Windows JDK 21 replacement was also reproduced directly. |

## Test record

- Android baseline: requested `:app:testDebugUnitTest :app:lintDebug :app:lintRelease` with repository wrapper, JDK 21 and local SDK; results pending.
- Backend baseline: dependencies installed with the checked-in npm lockfile, scripts disabled; test results pending. PostgreSQL integration tests must be distinguished from skipped tests.
- Existing remote CI at the initial commit was in progress when inspected. This is not evidence that this audit's eventual changes pass.
- Backend with isolated PostgreSQL 17.11: all 27 tests passed, zero skips, both before and after A1/A2 and the strengthened privacy/moderation/pending-friend checks. No production database or endpoint was used.
- A second synthetic database benchmark confirmed the query improvement (653.993 ms to 0.352 ms in that run). New B-tree sizes: 1,138,688 bytes per 50,000-row receipt index; 581,632 bytes per 25,000-row requester index. Absolute timing varies with the host; both runs show the same plan/buffer-work reduction. Indexes add about 2.73 MiB for this synthetic population.
- Focused Android regressions for A3–A5: all 48 tests passed (8 projection, 12 municipality, 28 place resolver). The associated lint/build invocation hit an internal Kotlin `ExperimentalDetector` crash. Sources were modified while that invocation was active; whether this caused the crash is unproven. Retrying the frozen final tree without disabling any detectors. Do not treat that invocation as a lint pass.
- `npm audit --omit=dev` and full `npm audit`: zero reported vulnerabilities in either run. This is an advisory-database result, not a guarantee against undisclosed vulnerabilities.
- Initial remote CI: account server, verify, and Android device integration on APIs 31/35/37 succeeded. API 31 release-startup failed before installing an APK because GitHub's artifact storage returned HTTP 503, "Egress is over the account limit". API 37 startup evidence exists; final status pending inspection.
- Local Android baseline remains running; two existing restore-test failures were reported at repeated recovery-backup replacement. Investigate Windows `File.renameTo` overwrite behavior versus Android before changing production restore logic.
- Completed local Android baseline: 815 tests, 813 passed, 2 failed, 0 skipped. Both failures were the repeat-restore case (`Could not save recovery backup`) at API 31/37. Lint analysis had started, but the failed test task prevented a completed lint result. A7 addresses this confirmed Windows failure while preserving Android atomic replacement semantics. References: [Android Files API](https://developer.android.com/reference/java/nio/file/Files), [Android UnixCopyFile implementation](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/refs/heads/androidx-constraintlayout-release/android-35/sun/nio/fs/UnixCopyFile.java).

## Rejected or deferred changes

- No speculative GPS interval/accuracy changes: battery savings have not been measured and tracking coverage takes priority.
- No deletion or lossy compaction of history, exploration, purchases or weekly progress.
- No dependency churn or visual redesign based only on age or style preference.
- No live battery savings claimed: tracking intervals, requested accuracy, listeners and service behavior are unchanged. The new backend savings do not reduce existing live hosting bills until an independently authorized deployment/schema update.
- Retain the importer's existing 512 MiB compressed/expanded safety bounds. Very large multi-year backups above that size remain a known limitation; simply removing limits would create disk/memory exhaustion risk. A streaming large-backup design warrants separate validation.
- Keep full raw history, all reward ledger entries and existing purchases. Do not retune pricing or reduce ad milestone payouts without empirical economy problems.
- Do not weaken strong Integrity/device licensing/certificate/nonce/clock checks. External GNSS spoofing remains possible; tested server validation does not prove physical travel.
- No blanket exception suppression or disabled lint checks for development-tool crashes.
- Default public OSRM/Census/map/boundary providers have their own availability and usage limits. This audit does not establish unlimited worldwide hosting capacity or authorize new paid infrastructure. Outside the U.S., a device without usable geocoder results still defers place discoveries rather than guessing identities.

## Completion criteria

At least two consecutive substantive passes with no further credible worthwhile change, cumulative review against main, appropriate backend/Android tests and static analysis, and explicit reporting of device/credential/environment gaps. These criteria have not yet been met.
