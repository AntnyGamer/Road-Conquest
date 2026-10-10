# Road Conquest 1.0 Beta 36

- Fix accidental town unlocks and points from nearby postal localities. Municipal discoveries now require the actual recorded GPS fix to fall inside a correctly named administrative boundary polygon, with state and country cross-checks.
- Reject inaccurate, stationary GPS exploration and remove temporary fog clearings caused solely by the map's live location preview. Genuine walking continues to reveal fog without counting as driving.
- Stop automatically replaying all historical drives to create new place candidates. Existing queued discoveries are filtered using saved accepted GPS evidence in a one-time database update; late confirmations no longer appear as new arrival notifications.
- Retain existing road history, road matching, distances, time driven, accounts, progression purchases, and verified scoring. Optional town-storage errors can no longer interrupt accepted driving samples.
- Preserve valid historical walking and driving exploration during old fog-cell cleanup, and add tests for stationary GPS scatter, legitimate walking, administrative polygons, nearby towns, polygon holes, and database upgrades.

Existing visited-town records and points are preserved rather than deleted without positive evidence, because older releases sometimes recorded approximate grid centers rather than exact entry coordinates.

Version code **62**, minimum Android 12. Install the signed Beta 36 APK over the existing app; do not clear its storage or uninstall first.

For Play Store upload, use only the signed `RoadConquest-1.0-beta.36.aab` from the release after GitHub's full verification passes.
