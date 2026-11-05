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
