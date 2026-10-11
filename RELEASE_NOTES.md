# Road Conquest 1.0 Beta 41

- **Recover recorded turns:** Retry an accurately tracked but unmatched junction even when another unrelated unmatched GPS fix appears earlier in the same road-matching request.
- **Keep accurate safety gates:** Eligible corrections still require confirmed approach and exit points, continuous precise movement, a real change of direction, and OSRM geometry that passes the existing confidence, snapping and detour checks.
- **Preserve history:** Previously recorded unmatched points remain stored and eligible for another match attempt. No roads are invented just to close a visual gap; an unresolved GPS trace will remain pending when a valid road path cannot be established.
- **Regression tests:** Cover the observed multi-point corner failure pattern and a negative straight-road case.

This update targets missed intersections such as the Market Street / North Broadway turn in Camden. Successful reconstruction depends on OSRM returning trustworthy geometry; the repair cannot guarantee every corner will fill.

**Version code 67, Android 12+.** Install the signed Beta 41 APK over your existing app to preserve driving data. Do not uninstall or clear your app data.
