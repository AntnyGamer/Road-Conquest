# Road Conquest 1.0 Beta 38

- **Correct town overlay topology across the U.S.:** All 50 states, including New Jersey, use the **same U.S. Census TIGERweb** incorporated-place and county-subdivision layers. No state receives a separate provider or different validation logic. New official boundaries are not clipped or altered to hide incorrect search results.
- **Fix overlapping green polygons and unexplained map slivers:** Draw official municipal polygons as supplied, dissolving only repeated shared border strokes. Source identity and separate municipal types determine which layer to use; we do not fill genuinely unvisited municipalities.
- **Worldwide support:** Where no official U.S. source is available, use administrative OpenStreetMap boundary polygons, restricted by region-appropriate administrative levels and verified country/state identity. Worldwide coverage depends on available OSM municipal polygons; neighborhood/place-label outlines are rejected, and no fabricated outlines are drawn.
- **Choose the proper municipality:** Match Census municipality names and legal types (Gloucester City versus Gloucester Township), and use geographic proximity to distinguish same-named townships in different counties.
- **Independent overlay caches:** Rebuild town outlines in two caches: one nationwide U.S. Census cache and one worldwide administrative OSM cache. Place-discovery caches, saved visit records, and existing state/country overlays remain separate.
- **Road visit display:** Use recorded driving episodes across all connected pieces of a road instead of reporting "About 1 time" for each of hundreds of tiny OSRM segments. Counts remain explicitly identified as GPS-based estimates.

**Unvisited towns remain unshaded.** If a third municipality lies between two visited towns, its legitimate unvisited area is intentionally not colored; the app no longer creates gaps or overlaps through inconsistent source boundaries.

U.S. Census geographic datasets are reference boundaries, not legal land surveys. In places where municipalities overlap as separate types of jurisdiction, the source may legitimately contain different administrative layers; the app avoids pretending these are one municipality. Global OSM data is best-effort and may be incomplete.

No tracking, fog, roads, raw GPS history, visits, points or purchases are altered by this update. Existing history remains intact.

**Version code 64, Android 12+.** Install the signed Beta 38 APK over your existing installation without uninstalling or clearing data.
