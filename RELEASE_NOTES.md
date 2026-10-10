# Road Conquest 1.0 Beta 37

- Fix town overlay fills darkening where municipal polygons intersect. Overlapping map shapes are clipped into mutually exclusive visible regions, with smaller boroughs taking precedence. Shared boundaries are dissolved into single thin lines instead of being drawn multiple times.
- Correct town-boundary matching so Gloucester City and Gloucester Township (and other different municipal legal types) are NEVER treated as the same municipality. Accept equivalent labels such as Borough of Pine Hill / Pine Hill Borough.
- Rebuild town overlay caches under stricter municipal-boundary matching. Older stored overlays are no longer trusted when displaying town boundaries. Cached state and country outlines are also refreshed; this affects visuals, not historical visits.
- Reject non-administrative places and, where available, U.S. county-level polygons mistakenly returned for municipality searches.
- Add regression tests for adjacent and intersecting municipalities, duplicate areas, boroughs surrounded by townships, disjoint islands, polygon holes and different legal municipal names.

**All saved roads, locations, visited towns, points, rewards, account data and purchases remain unchanged.** This update only corrects polygon display and verification. Previously earned town rewards are not retroactively removed. Town overlays may take time to reload once from the boundary provider after installation.

Version code **63**, minimum Android 12. Install the signed Beta 37 APK over Beta 36 without uninstalling or clearing app data.

For Play Store, upload only the signed `RoadConquest-1.0-beta.37.aab` from the matching GitHub release after all Android checks pass.
