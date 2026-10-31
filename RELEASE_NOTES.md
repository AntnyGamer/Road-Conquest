# Road Conquest Release 1.0

## Changes since version 1.1

- **Recover missed towns from saved drives:** Revisit accurate saved GPS positions, including compressed-out driving fixes, with resumable, bounded scanning after the recorded starting location. Towns are counted and shaded only after normal municipality verification; drawn roads alone do not prove a visit.
- **Map controls and settings:** Match the settings gear color to the other map controls, move Show fog to the top of Map & appearance, and use the standard selection dialog for map overlays with a separate Explore Nearby action.
- **Harder town-badge achievements:** Bronze goals are now 5, 20 and 75 towns; silver goals are 3, 15 and 50; gold goals are 2, 8, 20 and 50. The combined possible reward for these ten achievements falls from 9,725 to 3,990 points. Already-earned achievements and payouts are retained.
- **Less unnecessary local work:** Check both history-scan completion markers with one indexed query, avoid repeatedly saving completed cursors, and stop reading baseline/visited-place records after scanning finishes. Simplify Settings search by removing redundant filtering and condition checks.
- **Tests and cleanup:** Cover driving-fix recovery, scanning limits, town-badge goals, gear color and fog placement. Remove obsolete backup formats, pre-release database upgrades, notification registration variants, old boundary-cache cleanup, and the unused map-choice dialog helper.

**Build:** Android `versionName` **1.0**, `versionCode` **97**, Android 12+ (API 31). The application ID and signing identity are unchanged; this release introduces no database-schema migration.

**Release naming:** This is deliberately labeled **Release 1.0** as a public-release reset after the previously published 1.0 (code 95) and 1.1 (code 96) builds. It has a distinct GitHub tag, `release-v1.0`, so older releases are not overwritten.

**Data formats:** Current SQLite schema 16 and authenticated backup schema 12 remain supported; older beta database upgrades and backup formats are no longer supported. Newly recovered town candidates may need normal Census requests; no additional GPS sampling or road-matching calls are introduced.

**Distribution:** GitHub publishes signed artifacts only after required CI and device checks pass. Uploading the signed AAB to Google Play is a separate step; confirm versionCode 97 is unused in Play Console before uploading.
