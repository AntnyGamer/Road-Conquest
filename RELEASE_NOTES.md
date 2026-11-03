# Road Conquest 1.0.2

Tracking, road-reward, export, and service-efficiency fixes.

## Changes since 1.0.1

- **More reliable tracking and place discovery:** A failed optional municipal discovery enqueue no longer stops tracking after a driving fix has been saved. Delayed discovery and matched-road work preserve GPS history and existing verification rules.
- **Correct road rewards:** Newly discovered roads receive their points even when connecting road segments merge previously counted road groups. Rewards remain atomic with the geometry transaction; retrying or rolling back cannot duplicate points.
- **Safer exports:** A separate read-only SQLite snapshot allows ongoing GPS writes during an export on Android 12+. Secure backups recheck the history and account after the destination stream closes, including slow document providers.
- **Less redundant app work:** Cancel stale map/history reads, reuse CSV row buffers and already-evaluated achievements, and reduce repeat lookup, decoding and allocation work without changing map precision or road-matching eligibility.
- **Leaner optional services:** Identical overlapping forum moderation checks share in-flight work, while new attempts still get fresh results. Disabled push registration avoids PostgreSQL rate limiting/session lookup, and empty notification recipient lists avoid unnecessary promise fan-out. Authenticated delivery and device removal retain their protections.
- **Remove an unused legacy export writer:** The retired passphrase-backup creation method had no production callers. Older passphrase-protected backups **remain restorable**; the compatibility reader and its round-trip test remain.
- **Build and diagnostics:** Tighten Android lint regression checks, preserve readable sources, and stamp separate account-function and ingress bundles with their intended source revision for deployment verification.

**Build:** Android `versionName` **1.0.2**, `versionCode` **99**, Android 12+ (API 31). Application ID and permanent signing identity are unchanged. No schema migration or backup-format change.

**Release tag:** `v1.0.2`.

**Distribution:** After all required checks pass on the merged `main` commit, GitHub Actions builds and publishes the signed direct-install APK, signed Play upload AAB, source archive, server bundles, mapping and checksums. Google Play deployment is a separate manual step. Confirm version code 99 is unused in Play Console before uploading.

**Data compatibility:** Existing secure backups, readable export-only ZIPs, and older supported restore formats are documented in [backup documentation](BACKUP_AND_CHALLENGES.md).
