# Service order and free allowances

Services are ranked within the work they can perform. Tiles cannot replace road matching, and a region boundary cannot establish a municipality visit. This is an operational preference order, not an accuracy benchmark.

| Work | Preferred service | Backups / exhausted allowance |
|---|---|---|
| Street-map display | OpenFreeMap, with bundled styles and native cached tiles | Geoapify raster street tiles through the map gateway, then a local background with saved exploration/roads. Retry the preferred provider after its cooldown. |
| Satellite display | Esri imagery with OpenFreeMap labels | OpenFreeMap streets, then Geoapify streets, then the local progress view. A toast explains a degraded map. |
| Local road matching | Conservative on-device re-drive recognition against previously verified and stored roads; Geoapify for new roads | LocationIQ for a Geoapify outage/exhausted allowance, then persistent GPS queue. Complex or ambiguous traces retry with more evidence; they do not query a second provider for an easier result. |
| Countries / regions | Existing cached boundaries, then the configured OpenStreetMap/Nominatim service | geoBoundaries gbOpen ADM0/ADM1 with exact region-name and containment checks, supported license metadata and bounded downloads; otherwise retain the existing cache and retry later. |
| U.S. municipalities / rewards | Existing official Census TIGERweb checks and verified caches | Saved candidates retry later. Generic region datasets do not replace municipal verification. |
| Place names | Android's system geocoder | Existing official U.S. point lookup. Unverified worldwide town names do not earn town credit. |
| Account API / database | Existing Cloudflare account Worker and Neon Function / PostgreSQL | No database failover or account migration. The separately verified ingress is available for a later approved internal build. |
| Optional crash diagnostics | Firebase Crashlytics | Reporting is off by default. Firebase initializes for enabled friend alerts with sign-in and Android permission, or separate crash opt-in; notifications never enable crash collection. No Analytics dependency; revocation stops reports and deletes unsent reports. |
| Friend alerts and weekly reminders | Firebase Cloud Messaging; local Android alarms | Both on by default, preserving explicit opt-outs. Friend alerts require sign-in and Android notification permission, with current FID registration and HTTP v1 delivery. Weekly reminders need only notification permission. See [free services](FREE_SERVICES.md). |
| Service controls and monitoring | Cloudflare Workers KV; UptimeRobot | Public provider switches, existing hard quota caps, five-minute checks and owner email alerts; [service status](https://stats.uptimerobot.com/ZT185qP4jc). See [free services](FREE_SERVICES.md). |

## Configuration

- `ROADCONQUEST_MAP_GATEWAY_URL` defaults to the verified `https://roadconquest-maps.22abongiovanni.workers.dev`. Set it empty to disable Geoapify and LocationIQ integrations for a custom build. Keys are never placed in Gradle fields or in the APK.
- `ROADCONQUEST_ACCOUNT_API_URL` keeps its previous default. This change does not switch existing Android installations or the account ingress.
- The checked-in `app/google-services.json` contains the registered Firebase app's **public client identifiers**, including its Firebase-restricted Android API key. It contains no service-account key or backend credential. [Firebase documents why these identifiers are public](https://firebase.google.com/docs/projects/api-keys). Project: `roadconquest-9f0eb`, Android package: `com.roadconquest.app`.

## Match quality and failure handling

Geoapify uses a different POST and GeoJSON contract. The adapter checks original waypoint ordering and coordinates, requires actual matched points (never interpolated/unmatched points), and maps a single continuous route to the existing snap-distance, speed, continuity, endpoint and detour validation. Multiple disconnected legs and ambiguous/implausible responses remain pending. Its response cache reparses against current point IDs. A valid NoMatch or ambiguous result does not query another provider for an easier match.

Geoapify provides no externally reported confidence probability or explicit ramp maneuver in this response. The existing 0.45 local acceptance threshold is used after geometry validation; it is not presented as a provider-supplied probability. Geometrically confirmed drivable roads, including primary/trunk/motorway, service, unnamed roads and roundabouts, can earn new-road credit. Link ramps, footways and unrecognized classes remain visual-only to avoid unsupported new-road awards. This integration has fixture and network tests, plus a successful live public-example check; it has not established that Geoapify is more accurate than the original matcher on real drives. Verified competition matching stays on its separate server verification path.

HTTP 429, 401/403, 5xx, timeouts and connection failures move to an eligible backup. Retry-After seconds and HTTP dates are respected, up to a day. Providers return in priority order when their cooldown expires. A provider's exhausted budget is never bypassed by changing keys, accounts or forwarding headers.

## Backup import memory and bookkeeping

- ZIP import now retains one set of entry names and reuses it for both duplicate-name and exact expected-file checks.
- The single-byte streaming ZIP integrity path directly updates `CRC32` with that byte instead of allocating a temporary one-byte array each time. Byte-count caps, global zip-bomb guard, cancellation checks, CRC validation and accepted file layouts remain unchanged.

## Avoid duplicate cached boundary decompression

- A normal background overlay refresh of an already verified town now checks the cached verification bit in its first gzip read, rather than decompressing and parsing the complete polygon twice. Valid negative-result protection and strict verified-candidate requirements are preserved. Existing cache regression tests exercise both behaviors.

## Weekly challenge SQLite batching

- The same week-scoped road and town counts now run as two independently optimized SQL scalar subqueries within one SQLite statement per week instead of opening two cursors. Both original filters are unchanged.
- Up to four earned challenge rewards are inserted with one atomic `INSERT OR IGNORE` statement rather than four individual writes. Existing reward keys, payout amounts, timestamp, idempotence and previous-week grace are preserved. The challenge tests now verify that repeated refreshes do not create extra weekly reward rows.

## Minimizing map gateway budget database statements

- Each successful provider-credit reservation now updates the global credit counter, the per-client credit counter and the provider's next rate-limit slot in **one** SQLite statement rather than two. The statement is atomic and retains the exact daily caps, previous-version counter compatibility, 250 ms / 1050 ms pacing and safe failures. Tests check one write per allowed reservation and none on exhausted quotas.
- This reduces Durable Object SQLite statement count; it does **not** claim a reduction in the three actual quota rows written per reservation or in upstream map matching requests.

## Reducing personal leaderboard request load

- An authenticated `GET /v1/leaderboard?metric=cells&mine=1` may include the same verified-only `mine` total returned by the existing `/v1/verified/me` endpoint. The global leaderboard can then load its list and the current user's total in one HTTP request and one IP-rate-limit reservation, while retaining the original fallback when talking to older account servers.
- The optional `mine` field is returned only after session authentication, never for anonymous or invalid credentials. A failed personal-total lookup does not suppress the public ranking; the Android client still validates the bundled result's scoring policy. All pre-existing endpoint shapes remain supported.

## Reducing authenticated account-server round trips

- When the Challenges screen displays both the friend list and verified weekly standings, it requests `GET /v1/friends?include=weekly`. An updated server returns the same friend data and the existing verified-only weekly response together, eliminating one separate authenticated HTTP request, session lookup and rate-limit reservation per refresh.
- Legacy `GET /v1/friends` clients and `GET /v1/friends/weekly` remain unchanged. A server that doesn't support bundling omits `weekly` and Android falls back to the original endpoint. A failed weekly query cannot hide friend management, and bundled standings still pass the same scoring-policy verification.
- Server endpoint tests cover opt-in bundled responses and unchanged ordinary friend responses. This does not alter GPS upload cadence, matching accuracy, scoring or database schema.

## Combined local GPS continuity and heading check

- Previously verified road revisits now compute east/north displacement once per GPS edge, using it for both distance/speed continuity and heading agreement. The precise five-point eligibility, distance, time-gap, speed and 25° direction limits remain unchanged, and saved-road SQLite lookups still happen only after prequalification. Adds a regression test for speed, heading, time gaps and jumps.

## Polygon-area and Census distance calculation improvements

- The spherical GeoJSON area estimator now decodes each shared ring vertex, converts it to radians and computes its latitude sine once instead of twice per adjacent edge. The original edge accumulation order and area/hole formula remain unchanged; a test verifies reversal invariance and hole subtraction.
- The official Census municipality proximity fallback reuses one latitude cosine and each shared projected ring vertex instead of redoing trigonometry and JSON reads on both ends of every edge. Exact point-containment, snap thresholds and provider requests remain unchanged.

## Faster point-in-polygon and uncut municipal shapes

- Municipal containment checks now transform each ring vertex's latitude and wrapped longitude once instead of twice (as the end of one edge and the start of the next). The exact cross-product, boundary-inclusion, hole checks and longitude wrapping are unchanged. A new regression test exercises both sides of the International Date Line and on-edge points.
- Town-overlay partitioning reuses the cached polygon area when a municipality needs no clipping; overlapping municipalities still compute the precise post-clipping area and keep the same rendering priority.

## Additional municipal polygon sorting improvements

- Town overlays precompute the geometry area and Census layer priority once per candidate, instead of recalculating JTS polygon area and scanning layer-name substrings during every sort comparison. The comparator still sorts by the same (priority, geometric area, key) tuple and clipping semantics are unchanged.
- Polygon-type guards in official Census and geoBoundaries response parsers now directly compare the two supported type strings, avoiding a temporary set allocation for every candidate feature. No polygon acceptance rules or network requests change.

## Additional no-regression map hot-path optimizations

- Road grouping now projects each intermediate coordinate on a saved polyline once rather than projecting the same vertex twice for successive segment comparisons. Snap tolerances, geodesic projection, connected-road grouping and visit crediting are unchanged.
- Fog-grid projection computes its geographic raster center once per batch of unlocked mile tiles, rather than once per cell. This keeps map/world-wrap coordinates identical.
- Viewport sanity checks avoid temporary `List<Double>` allocations for four constant bounds, retaining identical finite/validity logic.
- The existing fog-grid and viewport regression tests still exercise these paths; an additional curved-road grouping test protects positive and negative grouping decisions.

## Trip replay and town-boundary rendering optimizations

- Trip replay now creates `SavedTripPoint` objects only for samples actually displayed and the true endpoint, rather than for every raw GPS fix read from SQLite. It retains timestamp gap detection even across dropped samples, the original sampling stride and equality/deduplication behavior.
- Town-overlap partitioning caches each immutable JTS polygon's bounding envelope once per render. Every overlap, ordering and clipping test still uses the exact same geometry.
- Town polygon rings already supplied closed are decoded directly into JTS coordinate arrays, avoiding an extra mutable list and array conversion. Unclosed rings are still closed identically; a regression test compares both representations.
- Remote Geoapify and normalized OSRM matching parsers now reuse each decoded polyline point as the preceding vertex of the next segment. This avoids requesting the same JSON array twice per segment; distance formulas, segment ordering and accumulated sums stay unchanged.

## Additional allocation and boundary-cache optimizations

- Cached administrative boundary polygons now stream gzip bytes straight into the existing atomic on-disk cache file, instead of keeping multiple copies of the full JSON and compressed data in heap memory. The gzip format, atomic rollback, decoded-size limit, historical plain-JSON compatibility and cache eviction semantics are unchanged. A large-polygon cache round-trip test was added.
- geoBoundaries backup parsing scans candidate polygons once and keeps at most one match, instead of constructing an intermediate list of all features and matches. A second valid match is still treated as ambiguous and rejected. This only affects local parsing; request frequency and matching criteria are unchanged.
- Nearby saved-road distance calculations now project shared polyline vertices only once per vertex, instead of once per adjacent segment. Degenerate/malformed vertices still break segment continuity; no proximity or ambiguity threshold changes.

## Further no-regression optimizations

- Cloudflare's Durable Object now reads all five daily budget counters in one SQLite SELECT rather than five separate queries per request. It also purges expired-day rows once per day per running object, rather than on every request; a restarted object purges on its first request. The shared and per-client counters are now updated together with one atomic SQLite INSERT instead of two separate statements; the pacing record remains its own write. Accounting, free-plan caps and pacing are preserved, including old whole-credit reservations. The server regression test verifies that allowed and rejected reservations each need only one read.
- The already strict five-fix local re-drive check skips repository summary access for shorter traces. Geoapify's segment projection reuses waypoint coordinates, step names and road classes rather than repeatedly decoding the same JSON fields. The geoBoundaries ISO-country validation reuses its compiled regex; neither changes acceptance criteria or network usage.

## Gateway deployment and spending protection

The map Worker enables Cloudflare's native Worker response cache. Identical public Geoapify tile URLs served from that cache bypass Worker execution, Durable Object reservations and provider requests, without caching individual driving traces. Only successful public tile responses advertise a one-day cache lifetime; matching, health and error responses remain `no-store`. Geoapify permits storing/reusing raster tiles. Caching is an optimization rather than a guaranteed hit rate; the existing shared credit caps still apply to cache misses.


`server/maps/wrangler.jsonc` creates a standalone Worker with a small SQLite-backed Durable Object for shared reservations. It does not replace either account Worker or Neon. SQLite-backed Durable Objects are [available on Workers Free](https://developers.cloudflare.com/durable-objects/platform/pricing/).

```powershell
npx wrangler deploy --config server/maps/wrangler.jsonc
npx wrangler secret put GEOAPIFY_API_KEY --config server/maps/wrangler.jsonc
npx wrangler secret put LOCATIONIQ_API_KEY --config server/maps/wrangler.jsonc
```

Both keys belong only in the Worker secret store. The gateway pins the Geoapify upstream hosts and LocationIQ's US matching endpoint, sanitizes requests, rejects upstream Worker client-address claims, strips authentication, limits trace size, refuses redirects, and hides provider error bodies. Android sends GPS in POST bodies to the proxy; LocationIQ's documented upstream API receives coordinates/timestamps in its URL. Matching responses use no-store. Tile responses can be cached for a day. No GPS trace, password or account token is saved in the budget object or application logs. Daily address fingerprints use provider-keyed HMAC and a daily namespace; old counter rows are removed on the next active day.

All installations share **at most 2,400 reserved Geoapify credits per UTC day**, with a 600-credit per-client daily guard and outgoing calls spaced at four/second. Reservations use integer quarter-credit units: up to 100 GPS waypoints cost one credit (200 waypoints cost two credits), while a raster tile costs 0.25 credits. Reservations persist across restarts and count any legacy same-day reservations conservatively. A request reserves allowance before transmission, so canceled/failed calls can consume allowance too. The deployed Cloudflare Worker must be updated alongside the Android app: the previous Worker rejects traces above 100 waypoints. Geoapify supports up to 1,000 points; Android deliberately caps batches at 200 for bounded processing and safer partial matches. This leaves headroom below Geoapify Free's 3,000 daily credits. The cap fails closed if its storage is unavailable. A normal map tile burst waits briefly rather than immediately exhausting its provider. Usage made directly with the same key outside this gateway also consumes Geoapify's allowance.

Firebase is on Spark; Geoapify and LocationIQ are on Free. No payment method, paid tier or automatic upgrade was configured. Cloudflare Workers/DO and Neon still have finite free quotas. Reaching a cap can pause a feature; it cannot create unlimited free capacity. Mapbox and Stadia were excluded because their previously checked map-display/commercial-use restrictions do not fit this app and budget.

## LocationIQ road-matching fallback (2026-10-16)

LocationIQ's [Free plan](https://locationiq.com/pricing) includes **5,000 requests/day, 2/second and 60/minute** and permits commercial applications with a prominent attribution link. Its [Terms of Use](https://locationiq.com/tos), under Acceptable Use, expressly allow permanent storage of response data; request-response caches on Free are limited to 48 hours. RC's cache lives in memory for at most 60 seconds and reparses responses against current point IDs. Saved matched road output can therefore remain in history. The app includes a visible clickable **Search by LocationIQ.com** link on the driving map and in Settings, plus OpenStreetMap data attribution in map/service credits.

The [matching API](https://docs.locationiq.com/docs/map-matching-api) accepts ordered GPS traces; its [standard routing limit](https://docs.locationiq.com/docs/routing-api) is **25 points/request**. This integration requests driving, GeoJSON step geometry, all input waypoints, timestamps, bounded accuracy radii, gap splitting, and no trace tidying. It keeps the provider's confidence and ambiguous-point markers and uses `RoadGeometryValidator` unchanged. It does not use nearest-road or planned-route responses as evidence of driving. Competition verification remains separate.

Order: **Geoapify → LocationIQ → saved pending GPS**. Only quota/auth/server/transport failures permit failover. A valid NoMatch, ambiguous response, or geometry rejected by local checks does not seek a more permissive provider. After cooldown, Geoapify is preferred again. While LocationIQ is preferred, matching windows shrink to 25 points. If a 200-point Geoapify attempt fails, the backup receives only its first 25 points in one request; the other 175 remain pending. No window is silently downsampled or fanned out into multiple fallback requests. Primary requests retain their seven-minute cadence; while LocationIQ is used, the 25-point backup runs at a proportional 52.5-second cadence under the separate shared quota. Initial failover can make one primary call and one backup call.

The gateway has a separate persistent LocationIQ budget object: **4,000 requests per UTC day across all installations**, **1,000/client/day**, and one outgoing slot per **1.05 seconds** (below the free second/minute limits). The key is stored only as `LOCATIONIQ_API_KEY`. Budget/storage failures stop requests, and failed/canceled upstream calls may still consume reservations. Direct use of the same key outside this gateway consumes the provider allowance too; its own 429 and Retry-After remain authoritative. The Geoapify credit counter and quarter-credit accounting are independent and preserved across deployment.

A live public Morisse trace returned HTTP 200 through both the direct API and deployed gateway. The captured LocationIQ fixture has confidence about 0.977; the adapter accepts supported road intervals and deliberately leaves the ambiguous final point pending. This demonstrates an operational integration, not a real-drive accuracy benchmark or proof of extra production capacity. Only public example coordinates were used. The free account was created with the owner's approval; no TomTom account or paid service was added.

## TomTom fallback assessment (2026-10-16)

**Not enabled: the standard terms do not establish permission for RoadConquest's retained road history and offline reuse.** This assessment uses PR #95 at `7fd91806288d3661d9c3944cc35ef7062cad98a3`, including the newer on-device re-drive checks and seven-minute remote-attempt limit.

- **Commercial/free offering:** TomTom's [platform FAQ](https://docs.tomtom.com/platform/documentation/status-and-support/faqs) says commercial applications can use its APIs even during free evaluation. The current [pricing page](https://docs.tomtom.com/pricing) lists **2,500 Snap to Roads requests per month**. This is a shared service allowance, not 2,500 requests per installation or per day.
- **Technical fit:** [Snap to Roads](https://docs.tomtom.com/snap-to-roads-api/documentation/snap-to-roads-api/synchronous-snap-to-roads) reconstructs driven roads from GPS traces and supports synchronous POST requests. Ordinary route planning does not establish that someone drove a road. Snap to Roads would need its own response adapter, the existing geometry checks, and a separate monthly gateway budget; changing the Geoapify URL would not work. No authenticated TomTom request or real-drive accuracy test was performed.
- **Retention blocker:** TomTom's [Portal Terms and Conditions](https://docs.tomtom.com/legal/terms-and-conditions), clauses **11.4–11.4.3**, restrict retained API results to client caching within response cache-control limits. Clause **11.6.1** restricts secondary/derived databases incorporating returned content. These rights are not granted by the commercial-use FAQ. Clause **2.2** also describes royalty-free evaluation rights as internal testing; production entitlement needs clarification if the account offers only evaluation access.

The application stores matched coordinates in `roads.geometry_json` through `TrackingRepository.upsertRoads`, exports those coordinates in `DataExporter`, and uses saved geometry in `KnownRoadReuse` to resolve later visits offline. **Our assessment:** a normal matcher fallback would feed TomTom results into those persistent features, so the standard free offering is not a confirmed fit. Merely disabling the short HTTP response cache would not address that storage. A verification-only adapter has not been established as an allowed workaround.

Before enabling TomTom, obtain written permission covering production commercial use, permanent matched geometry/history, backup export, and offline re-drive recognition within the existing $10/year budget. Then validate a live public-coordinate fixture and failure/cooldown behavior while preserving the seven-minute remote limit. No TomTom account, key, paid subscription, upstream calls, or runtime integration was added by this assessment. The compatible fallback added instead is LocationIQ, described above.

## Validation and handoff

Run the focused Android provider, geometry-validator and consent tests, Android lint, and the account server suite. `server/test/maps-gateway.test.mjs` checks private upstream forwarding, shared persistent caps, restricted tiles, secret-safe errors and malformed/untrusted requests without using a live key. The included Geoapify and LocationIQ fixtures use public example coordinates, not a user's route.

Crashlytics can receive real events only from an opted-in new build. The console initially shows setup until that SDK traffic arrives; a test passing locally is not proof of a received production crash. Before publishing a new app build, review the updated privacy disclosure and Play Data safety entries for Geoapify and opt-in Crashlytics. No new app release or Play upload is part of this work.

### High-capacity driving and local matching (candidate branch)

- Android retains full-frequency location callbacks for the live marker, fog, place discovery, and verification. For a clear, accurate, straight drive only, it saves at most one redundant GPS fix per 5.5 seconds; bends, inaccurate fixes, and other uncertain samples are retained.
- Normal server matching runs on a seven-minute cadence with up to 200 GPS waypoints. Full batches cost two credits; the backup is paced proportionally to its 25-point request limit (52.5 seconds), still within a separate shared free allowance. The map already renders locally recorded pending routes while matching catches up. Small priority corner repairs still use their original ten-point window.
- A matching window that fails without a provider outage is retried with smaller windows; three successful shorter matches expand the request size again.
- An on-device re-drive pass uses nearby saved road geometry. It resolves only precise, contiguous five-fix evidence near the **same already unlocked segment**, saves its visit transactionally, and never adds a new road. GPS on new roads and ambiguous cases still requires the Geoapify gateway.
- **Capacity is an estimate, not a verified load benchmark.** For 200 aggregate driving hours with one retained fix every six seconds, roughly 120,000 GPS fixes/day would cost a theoretical 1,200 credits when efficiently packed into 200-point requests (two credits per full batch). Real small windows, retries, abrupt stops, and backup tiles consume extra credits. A 2,400-credit gateway reserve still enforces the free-plan safety margin.
- Deployment: update the existing Cloudflare gateway with `npx wrangler deploy --config server/maps/wrangler.jsonc` using the existing account and previously configured GEOAPIFY_API_KEY secret. Do **not** distribute the key in the app. Live-device and real-drive benchmarks remain necessary.

### Accuracy and free-quota safeguards

- GPS fixes omitted by the existing road-matching compressor are preserved in `skipped_driving_fixes` (SQLite schema 15). They normally do **not** enter the online matching queue; the terminal skipped fix is promoted when a drive ends or after an unexpected app-process death. They are included in manual ZIP exports and deleted with all device history.
- Local mileage calculations now account for intervening compressed-out driving samples. Normal Geoapify matching now supports up to 200 points per request (two credits when full).
- On-device re-drive recognition requires at least five consecutive, directionally consistent, precise fixes on the same previously saved road, spanning at least 25 m and within 3 m of the stored geometry. Uncertain evidence remains in the existing matching backlog for later remote verification.
- The Android tracking service enforces at most one primary remote matching attempt every seven minutes (or a proportional 52.5-second cadence for the 25-point backup), even for forced retries and backlog flushes. This may increase matching latency but prevents burst spending. Service recreation restarts this local pacing window; the Cloudflare Worker still imposes the authoritative fixed daily shared credit cap.
- Added local evidence slightly increases device storage and write activity; no GPS evidence is uploaded by the new archive. A passing unit suite cannot prove identical accuracy on every real drive; replay comparison is needed before publishing.

### Faster local recognition without more map-matching traffic

- The tracking service now inspects eligible saved roads locally every **30 seconds**, including when the online matcher is on provider cooldown. It does not add web requests.
- Geoapify matching is limited to **one attempt per seven minutes per running service**, with a proportional cadence for the smaller LocationIQ backup; global quotas still apply. Final flushes and retries respect the same online limiter; delayed online confirmation is expected.

## Conservative no-extra-request map-matching seam recovery (draft PR #96)

- Geoapify's local normalization now projects coordinates through the short longitude arc across ±180° instead of projecting a matching International Date Line crossing as an almost 360° jump. Interpolated snapped geometry stays beside the real Date Line, and all existing distance/speed/geometry validator gates still apply. Ordinary sub-180° route geometry is unchanged.
- A provider step with `from_index == to_index` can now locate a snapped GPS waypoint exactly at its junction vertex **only within the existing strict 3-meter projection tolerance**. This prevents a genuine zero-length junction step from making adjoining validated approach/exit legs unmatchable. Zero-length steps never become invented road geometry; all multi-leg continuity, snap, detour, confidence and road-count filters stay in place.
- Added deterministic tests for valid date-line road segments, zero-length junctions, and a snapped junction too far away to qualify. **No additional external provider calls or server quota changes.**

## Salvage neighboring validated geometry when one provider leg is malformed (draft PR #96)

- Both locally rejected geometry (`IllegalArgumentException`) and an invalid provider step object (`JSONException`) now use the SAME already-established per-leg rejection path. Properly validated sibling road legs still save; the invalid interval remains pending, and the no-valid-legs case retains its existing failure backoff.
- The rejected interval remains explicitly blocked from inferred seam bridging. This is not a relaxed snap/road acceptance condition and does not cause any additional HTTP or provider requests. A focused turn test covers valid-before-malformed, no credit for the broken leg, and a malformed-only response.

## Preserve independently validated matchings when another matching has bad indexing

- A provider response with multiple independent route hypotheses may contain one unusable matching with shifted/duplicate waypoint numbers or absent/wrong leg count. The validator now **rejects that entire malformed matching and blocks its GPS IDs from seam bridging**, but can still save unrelated legs after all original distance, detour, confidence and snap checks. If no valid matching remains, the previous failure/backoff still applies.
- No additional remote requests, fallback providers, model guesses, invented connectors, score rule changes or new permissive thresholds. A regression test confirms only the valid independent section is saved, and malformed-only input still fails closed.
