# Service order and free allowances

Services are ranked within the work they can perform. Tiles cannot replace road matching, and a region boundary cannot establish a municipality visit. This is an operational preference order, not an accuracy benchmark.

| Work | Preferred service | Backups / exhausted allowance |
|---|---|---|
| Street-map display | OpenFreeMap, with bundled styles and native cached tiles | Geoapify raster street tiles through the map gateway, then a local background with saved exploration/roads. Retry the preferred provider after its cooldown. |
| Satellite display | Esri imagery with OpenFreeMap labels | OpenFreeMap streets, then Geoapify streets, then the local progress view. A toast explains a degraded map. |
| Local road matching | Conservative on-device re-drive recognition against previously verified and stored roads; Geoapify for new roads | Persistent GPS queue and adaptive smaller matching windows on complex or unmatchable traces. No alternate public-road matcher is configured. |
| Countries / regions | Existing cached boundaries, then the configured OpenStreetMap/Nominatim service | geoBoundaries gbOpen ADM0/ADM1 with exact region-name and containment checks, supported license metadata and bounded downloads; otherwise retain the existing cache and retry later. |
| U.S. municipalities / rewards | Existing official Census TIGERweb checks and verified caches | Saved candidates retry later. Generic region datasets do not replace municipal verification. |
| Place names | Android's system geocoder | Existing official U.S. point lookup. Unverified worldwide town names do not earn town credit. |
| Account API / database | Existing Cloudflare account Worker and Neon Function / PostgreSQL | No database failover or account migration. The separately verified ingress is available for a later approved internal build. |
| Optional crash diagnostics | Firebase Crashlytics | Off by default. No Google Analytics dependency. SDK initialization happens only after opt-in; revocation stops collection and deletes unsent reports. |

## Configuration

- `ROADCONQUEST_MAP_GATEWAY_URL` defaults to the verified `https://roadconquest-maps.22abongiovanni.workers.dev`. Set it empty to disable Geoapify integration for a custom build. Keys are never placed in Gradle fields or in the APK.
- `ROADCONQUEST_ACCOUNT_API_URL` keeps its previous default. This change does not switch existing Android installations or the account ingress.
- The checked-in `app/google-services.json` contains the registered Firebase app's **public client identifiers**, including its Firebase-restricted Android API key. It contains no service-account key or backend credential. [Firebase documents why these identifiers are public](https://firebase.google.com/docs/projects/api-keys). Project: `roadconquest-9f0eb`, Android package: `com.roadconquest.app`.

## Match quality and failure handling

Geoapify uses a different POST and GeoJSON contract. The adapter checks original waypoint ordering and coordinates, requires actual matched points (never interpolated/unmatched points), and maps a single continuous route to the existing snap-distance, speed, continuity, endpoint and detour validation. Multiple disconnected legs and ambiguous/implausible responses remain pending. Its response cache reparses against current point IDs. A valid NoMatch or ambiguous result does not query another provider for an easier match.

Geoapify provides no externally reported confidence probability or explicit ramp maneuver in this response. The existing 0.8 local acceptance threshold is used after geometry validation; it is not presented as a provider-supplied probability. Only named residential/unclassified/tertiary/secondary street segments earn new road-count credit through this adapter. Other classes still draw validated geometry without adding a new road; this is intentionally more conservative than a fully annotated routing provider result. This integration has fixture and network tests, plus a successful live public-example check; it has not established that Geoapify is more accurate than the original matcher on real drives. Verified competition matching stays on its separate server verification path.

HTTP 429, 401/403, 5xx, timeouts and connection failures move to an eligible backup. Retry-After seconds and HTTP dates are respected, up to a day. Providers return in priority order when their cooldown expires. A provider's exhausted budget is never bypassed by changing keys, accounts or forwarding headers.

## Gateway deployment and spending protection

`server/maps/wrangler.jsonc` creates a standalone Worker with a small SQLite-backed Durable Object for shared reservations. It does not replace either account Worker or Neon. SQLite-backed Durable Objects are [available on Workers Free](https://developers.cloudflare.com/durable-objects/platform/pricing/).

```powershell
npx wrangler deploy --config server/maps/wrangler.jsonc
npx wrangler secret put GEOAPIFY_API_KEY --config server/maps/wrangler.jsonc
```

The key belongs only in the Worker secret store. The gateway pins the two Geoapify upstream hosts, sanitizes requests, rejects upstream Worker client-address claims, strips authentication, limits trace size, refuses redirects, and hides provider error bodies. Matching responses use no-store. Tile responses can be cached for a day. No GPS trace, password or account token is saved in the budget object or application logs. Daily address fingerprints use HMAC and a daily namespace; old counter rows are removed on the next active day.

All installations share **at most 2,400 reserved Geoapify credits per UTC day**, with a 600-credit per-client daily guard and outgoing calls spaced at four/second. Reservations use integer quarter-credit units: up to 100 GPS waypoints cost one credit, while a raster tile costs 0.25 credits. Reservations persist across restarts and count any legacy same-day reservations conservatively. A request reserves allowance before transmission, so canceled/failed calls can consume allowance too. The deployed Cloudflare Worker must be updated alongside the Android app: the previous Worker rejects traces above 10 waypoints. This leaves headroom below Geoapify Free's 3,000 daily credits. The cap fails closed if its storage is unavailable. A normal map tile burst waits briefly rather than immediately exhausting its provider. Usage made directly with the same key outside this gateway also consumes Geoapify's allowance.

Firebase is on Spark and Geoapify is on Free. No payment method, paid tier or automatic upgrade was configured. Cloudflare Workers/DO and Neon still have finite free quotas. Reaching a cap can pause a feature; it cannot create unlimited free capacity. Mapbox and Stadia were excluded because their previously checked map-display/commercial-use restrictions do not fit this app and budget.

## Validation and handoff

Run the focused Android provider, original matcher-network and consent tests, Android lint, and the account server suite. `server/test/maps-gateway.test.mjs` checks private upstream forwarding, shared persistent caps, restricted tiles, secret-safe errors and malformed/untrusted requests without using a live key. The included Geoapify fixture uses the vendor's public example coordinates, not a user's route.

Crashlytics can receive real events only from an opted-in new build. The console initially shows setup until that SDK traffic arrives; a test passing locally is not proof of a received production crash. Before publishing a new app build, review the updated privacy disclosure and Play Data safety entries for Geoapify and opt-in Crashlytics. No new app release or Play upload is part of this work.

### High-capacity driving and local matching (candidate branch)

- Android retains full-frequency location callbacks for the live marker, fog, place discovery, and verification. For a clear, accurate, straight drive only, it saves at most one redundant GPS fix per 5.5 seconds; bends, inaccurate fixes, and other uncertain samples are retained.
- Normal server matching runs on a seven-minute cadence with up to 100 GPS waypoints. The map already renders locally recorded pending routes while matching catches up. Small priority corner repairs still use their original ten-point window.
- A matching window that fails without a provider outage is retried with smaller windows; three successful shorter matches expand the request size again.
- An on-device re-drive pass uses nearby saved road geometry. It resolves only precise, contiguous three-fix evidence near the **same already unlocked segment**, saves its visit transactionally, and never adds a new road. GPS on new roads and ambiguous cases still requires the Geoapify gateway.
- **Capacity is an estimate, not a verified load benchmark.** For 200 aggregate driving hours with one retained fix every six seconds, roughly 120,000 GPS fixes/day would cost a theoretical 1,200 credits when efficiently packed into 100-point requests. Real small windows, retries, abrupt stops, and backup tiles consume extra credits. A 2,400-credit gateway reserve still enforces the free-plan safety margin.
- Deployment: update the existing Cloudflare gateway with `npx wrangler deploy --config server/maps/wrangler.jsonc` using the existing account and previously configured GEOAPIFY_API_KEY secret. Do **not** distribute the key in the app. Live-device and real-drive benchmarks remain necessary.

### Accuracy and free-quota safeguards

- GPS fixes omitted by the existing road-matching compressor are preserved in `skipped_driving_fixes` (SQLite schema 15). They do **not** enter the online matching queue. They are included in manual ZIP exports and deleted with all device history.
- Local mileage calculations now account for intervening compressed-out driving samples. The original route-matching input selection and 100-point request size are preserved.
- On-device re-drive recognition requires at least five consecutive, directionally consistent, precise fixes on the same previously saved road, spanning at least 25 m and within 3 m of the stored geometry. Uncertain evidence remains in the existing matching backlog for later remote verification.
- The Android tracking service enforces at most one remote matching attempt every six minutes, even for forced retries and backlog flushes. This may increase matching latency but prevents burst spending. Service recreation restarts this local pacing window; the Cloudflare Worker still imposes the authoritative fixed daily shared credit cap.
- Added local evidence slightly increases device storage and write activity; no GPS evidence is uploaded by the new archive. A passing unit suite cannot prove identical accuracy on every real drive; replay comparison is needed before publishing.

### Faster local recognition without more map-matching traffic

- The tracking service now inspects eligible saved roads locally every **30 seconds**, including when the online matcher is on provider cooldown. It does not add web requests.
- Geoapify matching is independently limited to **one attempt per seven minutes per running service**. This is stricter than the earlier six-minute request limiter and prevents the faster local interval from increasing server usage. Final flushes and retries respect the same online limiter; delayed online confirmation is expected.
