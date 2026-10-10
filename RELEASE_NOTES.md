# Road Conquest 1.0 Beta 38

- **Road visit counts:** Count separate GPS-supported visits across all stored segments of a connected road group. Earlier releases split an ordinary road into hundreds of separate OSRM segments, most with exactly one visit. The road details panel no longer forces every tap to show “About 1 time”; it now shows the estimated whole-road visit count explicitly.
- **Municipality overlay correctness:** Reject inaccurate historic town outlines whose saved grid-center location is inconsistent with accurate raw GPS fixes around first or last visit. Unlike earlier visual clipping alone, every displayed town polygon must correspond to recorded location evidence or the initial exact zero-point.
- **Municipal identity:** For New Jersey, restrict overlays to municipality-level administrative boundaries (OSM admin level 8, or municipality place rank when admin level is unavailable) and reject county/settlement substitutes. Choose between same-named townships using actual GPS containment, not merely text or nearest search centroid.
- **Fresh town overlay cache:** Invalidate the old cached town shapes from Beta 37. State and country caches remain untouched. When a trustworthy municipality boundary cannot be confirmed, skip its outline instead of drawing the wrong one.
- Add regression tests for true road-wide revisit counts, similarly named disconnected roads, inaccurate historical grid centers, later genuine town visits and duplicate Nominatim search results.

**Data safety:** No change to roads, raw GPS history, road visit windows, town-visit records, points or purchases. The new calculations are read-only.

**Note:** Whole-road visits are still estimates based on saved GPS and matching data. Missing GPS cannot be reconstructed as a verified extra trip. Some unconfirmed town outlines may remain hidden until usable boundary evidence is available.

Version code **64**; Android 12+. Install the signed Beta 38 APK over your existing app without uninstalling or clearing data.
