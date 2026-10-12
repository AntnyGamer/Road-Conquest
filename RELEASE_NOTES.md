# Road Conquest 1.0 Beta 42

- **Starting-location and place rewards:** More reliably preserve the first zero-point location while resolving unavailable geocoder results. Standardize U.S. country and state abbreviations across providers, and reuse historical place keys so the same real town, state, or country cannot be awarded twice when geocoder spelling changes.
- **Verified municipality boundaries, nationwide:** Give official U.S. Census incorporated places priority, keep genuinely different Census municipality identifiers separate, and retry malformed provider replies instead of incorrectly caching a missing boundary. Previously verified town polygons cannot be overwritten or displayed incorrectly by stale, weaker lookup results.
- **Deletion and account privacy:** Keep deleted GPS boundary caches from being repopulated by old network results, atomically disable optional verified-GPS uploads before local cleanup, and protect recent username and leaderboard-privacy changes against stale account responses.
- **Driving and verified scoring reliability:** Ensure a true on-road departure is not skipped when a two-minute visit interval contains many GPS fixes, and allow a pending verified-drive submission to retry after an outage without accepting stale GPS data as fresh evidence.
- **Map fog and efficiency:** Preserve the full-world fog while rapidly zooming, avoid unnecessary world-texture updates, and skip redundant fog opacity and bitmap work when the setting has not changed. The map and explored roads remain geographically anchored.
- **Testing:** Android 12, 15 and 17 map-integration checks; account-server tests; and expanded location, cache, rewards, history-deletion and driving regressions are part of the release verification.

**Version code 68, Android 12+.** Install the signed Beta 42 APK over the existing app to preserve its saved driving history, unlocked roads, points and purchases. Do not uninstall or clear app data.

This release does not invent an unresolved road, town, or missed corner; an actual verified road match and verified municipality remain necessary for new exploration and rewards.
