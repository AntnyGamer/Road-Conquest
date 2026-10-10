# Road Conquest 1.0 Beta 38

- **Correct NJ town overlay topology:** New Jersey municipalities now come from one consistent, statewide municipal boundary source published by the New Jersey Office of GIS, not independent Nominatim search results. Adjacent official towns share the same municipal border coordinates; the app does not clip or alter these official fill polygons to hide bad source matches.
- **Fix overlapping green polygons and unexplained map slivers:** Remove the older NJ overlay geometry partition, use the original official polygon for each selected town, and dissolve duplicate shared boundary strokes only. This corrects the source inconsistency that caused visually overlapping towns and artificial gaps.
- **Choose the proper municipality:** Match NJOGIS municipality names and legal types (Gloucester City versus Gloucester Township), and use geographic proximity to distinguish same-named townships in different counties.
- **Independent overlay cache:** Rebuild NJ municipality overlays in a new official-data cache. The place-discovery resolver's Nominatim caches, saved visit records, state and country overlays remain separate and unchanged.
- **Road visit display:** Use recorded driving episodes across all connected pieces of a road instead of reporting "About 1 time" for each of hundreds of tiny OSRM segments. Counts remain explicitly identified as GPS-based estimates.

**Unvisited towns remain unshaded.** If a third municipality lies between two visited towns, its legitimate unvisited area is intentionally not colored; the app no longer creates gaps or overlaps through inconsistent source boundaries.

NJOGIS describes its dataset as the most accurate delineation available from its sources, **not a legal land survey**. Other states currently continue using the existing overlay provider.

No tracking, fog, roads, raw GPS history, visits, points or purchases are altered by this update. Existing history remains intact.

**Version code 64, Android 12+.** Install the signed Beta 38 APK over your existing installation without uninstalling or clearing data.
