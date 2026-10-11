# Road Conquest 1.0 Beta 40

- **Less redundant town-discovery work:** Decode and reuse cached official municipality polygons once per foreground GPS discovery batch rather than reading and parsing all visited town boundaries repeatedly for each recorded fix. Newly confirmed polygons become available immediately within the same batch.
- **Single-read verification:** Preserve strict verified-only town discovery and invalid-cache cleanup while eliminating a second read and JSON parse of a cached polygon.
- **Faster municipality name checks:** Reuse compiled whitespace patterns, official U.S. country names, and unchanged legal-name lookup tables instead of allocating equivalent patterns and lists for every place.
- **Lighter map processing:** Remove an unused camera read in fog scheduling and reuse each town's bounding envelope during overlay partitioning. Fog reveal, native camera transforms, and municipality clipping remain unchanged.
- **Preserve driving accuracy:** No changes to GPS sampling intervals, accuracy checks, road matching, turn recovery, fog visibility calculations, score values, or existing history. The preexisting U.S./USA spelling compatibility is retained.

This update is a targeted reduction in CPU allocations, local file access, and repeated geometry work while processing discovery backlogs. Battery gains are expected to be small and have not been measured on a physical phone. Historic town awards, including South Harrison, are not automatically revoked.

**Version code 66, Android 12+.** Install the signed Beta 40 APK over your existing app. Do not uninstall or clear your data.
