# Road Conquest 1.0.4

## Changes since published 1.0.3

- **Clear account errors:** Sign-in distinguishes incorrect username/password, network failures, server errors, temporary rate limits and local session-persistence failures. Prevent stale authentication responses from overwriting later sign-in actions, and preserve the useful message after screen recreation. No account, password, or server-schema migrations.
- **Trip history and GPX:** Reduce per-fix boxing during replay and simplify GPX segment-break checks, retaining Android 12 compatibility, route breaks, timestamps, privacy cutoffs, and byte-for-byte export output.
- **Faster map bookkeeping:** Cache constant fog-grid row limits; avoid repeated town-boundary cache lookups; detect the need for Census layer partitioning without allocating intermediate collections. Authoritative municipal boundaries and exploration scoring are unchanged.
- **Lower background overhead:** Avoid redundant distance-preference reads and map-budget cache lookups. Reuse permitted friend actions; retained dynamically sized SQL updates for future map quota changes.
- **Forum Plus account switching:** Discard pending Play purchase verification tickets from the previous account session so returning users can retry without waiting for an obsolete result.
- **Data-reset race:** Reject a population service response that completes after Delete all data, rather than displaying or caching pre-reset information.
- **Precise GPS ordering:** Reject a location fix that is even a fraction of a millisecond older than the newest accepted fix; retain same-time accuracy preference and ordinary newer-fix behavior.
- **Malformed GPS speed uncertainty:** Ignore impossible negative speed-accuracy readings rather than treating them as reliable driving speed. Keep valid measured speed and stop readings unchanged.
- **Failed restore safety:** If an already-validated temporary backup preview was deleted or reclaimed before confirmation, reject the restore before changing tracking mode, pausing the service, revoking verified-driving consent, or producing a recovery file.
- **Account sign-in race:** Only an authentication request that is still pending may save a bearer session; a finished failed attempt cannot sign in later through a stray completion. Regression-tested.
- **EU consent recovery:** If the UMP consent-information refresh fails with no permission to request ads, try refreshing consent again once while the screen is active; never request ads without valid consent. A pre-existing valid cached consent remains usable.
- **AdMob and consent verification:** Keep Google Mobile Ads SDK 25.5.0 and Google UMP SDK 4.0.0. Preserve debug/test IDs and production release IDs, and verify release App ID, Advertising ID permission, and SDK integration in CI. Consent messages themselves must be created and published separately in AdMob Privacy & messaging.
- **Build tooling:** Update the Gradle wrapper from 9.8.2 to 9.9.0 using the official SHA-256 checksum to resolve the failing out-of-date Gradle lint check without suppressing it.

**Build:** Android versionName **1.0.4**, versionCode **101** (API 31+). Intended release tag `v1.0.4`. Google Play publication is separate from the GitHub release; verify code 101 remains unused in Play Console.

**Compatibility:** No change to package name, signing key, GPS evidence, exploration history, payments, backup format, database schema, or account/map service URLs. Do not uninstall an existing installation to switch between Google Play and GitHub signing channels; create a verified backup first.

---

# Road Conquest 1.0.3

## Changes since the published 1.0.2 release

- **Replay survives rotation:** Keep the selected drive and replay slider position when the Trips screen is recreated, including when a selected drive gains additional fixes.
- **Fresh trip details after matching:** Update the selected drive's estimated road, town, tile and pending-matching counts when returning to Trips, even if matching changes without a new GPS fix.
- **Website and legal links:** The app's Settings links for the Road Conquest website, Privacy Policy and Terms of Service now open `https://roadconquest.pages.dev/`. The website and its `app-ads.txt` were already published on Cloudflare Pages in the separately merged PR #138; version 1.0.3 is the first planned Android release containing those updated links.
- **Existing app compatibility:** The Cloudflare account and map service Worker addresses are unchanged, so previously installed versions can continue using their original endpoints. The new website does not replace either backend service.
- **System-bar theme cleanup:** Remove six deprecated `android:statusBarColor` and `android:navigationBarColor` items from the light, gold and dark themes. Keep existing AndroidX edge-to-edge setup, insets handling and light/dark system-bar icon configuration. This change alone does not guarantee that every edge-to-edge warning in Google Play Console disappears.

**Compatibility:** No database or account migration, no changes to backup formats, application ID, signing identity, minimum Android version or account/map service URLs.

**Build:** Android `versionName` **1.0.3**, `versionCode` **100**, Android 12+ (API 31).

**Release tag:** `v1.0.3`. GitHub release publication requires an approved merge to `main`, passing required checks and working release-signing credentials. Uploading the Play App Bundle to Google Play is a separate step.

---

## Road Conquest 1.0.2

Tracking, road-reward, export, and service-efficiency fixes.

### Changes since 1.0.1

- **More reliable tracking and place discovery:** A failed optional municipal discovery enqueue no longer stops tracking after a driving fix has been saved. Delayed discovery and matched-road work preserve GPS history and existing verification rules.
- **Correct road rewards:** Newly discovered roads receive their points even when connecting road segments merge previously counted road groups. Rewards remain atomic with the geometry transaction; retrying or rolling back cannot duplicate points.
- **Safer exports:** A separate read-only SQLite snapshot allows ongoing GPS writes during an export on Android 12+. Secure backups recheck the history and account after the destination stream closes, including slow document providers.
- **Less redundant app work:** Cancel stale map/history reads, reuse CSV row buffers and already-evaluated achievements, and reduce repeat lookup, decoding and allocation work without changing map precision or road-matching eligibility.
- **Leaner optional services:** Identical overlapping forum moderation checks share in-flight work, while new attempts still get fresh results. Disabled push registration avoids PostgreSQL rate limiting/session lookup, and empty notification recipient lists avoid unnecessary promise fan-out. Authenticated delivery and device removal retain their protections.
- **Remove retired backup formats:** Deleted the passphrase writer/reader and unlock UI, plus the old Play-signed ZIP exporter, P-256 verification path, server signing endpoints, public-key fixture and associated tests. **Neither legacy format can be restored.** Current account-bound secure backups and private on-device recovery remain supported.
- **Remove retired implementation paths:** Deleted road-only fog rendering and its obsolete raster tests; current mile-grid fog, its fades and cloud texture, and road-overlay geometry remain. Removed the standalone friends-weekly HTTP route and old-server fallback; Challenges uses the current bundled friends-and-weekly response.
- **Build and diagnostics:** Tighten Android lint regression checks, preserve readable sources, and stamp separate account-function and ingress bundles with their intended source revision for deployment verification.
- **Tooling and dependency maintenance:** Android Gradle Plugin 9.5.0, pinned `gradle/actions/setup-gradle` 6.4.1 (three workflow references), and emulator replay `grpcio` 1.85.1; the other dependencies and signing identity are unchanged.

**Build:** Android `versionName` **1.0.2**, `versionCode` **99**, Android 12+ (API 31). Application ID and permanent signing identity are unchanged. No database migration or new backup format; old signed ZIP/passphrase restore support is intentionally retired.

**Release tag:** `v1.0.2`.

**Distribution:** After all required checks pass on the merged `main` commit, GitHub Actions builds and publishes the signed direct-install APK, signed Play upload AAB, source archive, server bundles, mapping and checksums. Google Play deployment is a separate manual step. Confirm version code 99 is unused in Play Console before uploading.

**Backup formats:** Only current account-bound encrypted .rcbackup files and locally authenticated pre-restore recovery ZIPs are restorable. Retired passphrase-protected and Play-signed ZIP formats are unsupported. Readable ZIPs remain export-only. See [backup documentation](BACKUP_AND_CHALLENGES.md).
