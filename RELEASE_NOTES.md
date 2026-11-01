# Road Conquest 1.0.1

Portable private backups, reliable discovery rewards, and cancellation fixes.

## Changes since Release 1.0

- **Portable private backups:** Create an encrypted `.rcbackup` file and restore it on Google Play or GitHub installations. Sign in to the same Road Conquest account and connect to the internet to back up or restore; no separate backup password is needed. Readable ZIP exports remain available for analysis and cannot be restored.
- **Reliable starting-town rewards:** A town identified after repeated partial starting-location lookups now stays a zero-point starting place, including when it is recovered within the same discovery batch.
- **Local-data deletion:** Deleting local data stops further requests from an old discovery batch and prevents its results from recreating cleared boundary caches. Cancelled lookups also stop the remaining batch instead of continuing after a provider delay.
- **Delayed weekly rewards:** Older town visits and road matches now trigger the correct weekly evaluation even when the unique town or road total stays unchanged. This includes newly matched fragments of an existing road and the previous week's grace period.
- **Map and matching efficiency:** Confirmed roads remain visible farther out on the map. Fog-edge rendering skips unnecessary work for fully covered or revealed pixels, and matching reuses unchanged response data without weakening GPS or geometry validation.
- **Regression coverage:** Added tests for starting-town recovery, deletion and cancellation during lookup, Census fallback requests, and delayed town/road weekly rewards.

**Build:** Android `versionName` **1.0.1**, `versionCode` **98**, Android 12+ (API 31). The application ID and permanent signing identity are unchanged. SQLite schema 16 and backup CSV schema 12 remain unchanged.

**Release tag:** `v1.0.1`.

**Data formats:** Supported restore formats and current export options are listed in [backup documentation](BACKUP_AND_CHALLENGES.md).

**Distribution:** GitHub publishes the signed APK, signed Play upload AAB, Android Studio source archive, account-server bundle, R8 mapping and SHA-256 checksums only after required CI and device checks pass. The AAB includes full native debug symbols and the R8 mapping. Uploading the signed AAB to Google Play is a separate step; confirm version code 98 is unused in Play Console before uploading.
