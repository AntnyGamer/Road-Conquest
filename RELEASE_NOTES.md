# Road Conquest 1.0 Beta 62

## What's new since Beta 61

- **Clearer forum cooldown:** Show the remaining time as "You can post again in X minutes" rather than repeating the ten-minute posting policy.
- **Opaque forum content:** Community post cards and the new-post composer use fully opaque backgrounds in light, dark and gold appearances, while retaining highlight borders.
- **Improved discovery tour:** The offline map demonstration follows roads farther, enters a third mile square, and uses smoother cloud-fog transitions as newly visited squares clear.
- **Appearance cues:** The Settings appearance control displays a sun, moon or automatic icon based on the selected setting. The main map Settings gear tint also reflects that choice.
- **Notification reset fixes:** Delete all data disables every optional notification category together and removes its pending weekly reminder. Turning off friend-request notifications also dismisses an existing request alert.
- **Fewer repeated paid matching calls:** Geoapify and LocationIQ retain up to four exact responses within the existing 15-minute lifetime and a fixed aggregate text bound. Every reused response is revalidated against the current GPS fixes. Provider order, matching strictness and tracking cadence stay the same.
- **Lower account and forum server load:** Combine related rate-limit/session checks and transactional writes, return fewer unused profile columns, skip unchanged profile rewrites, and avoid waking the database for disabled public rankings. Add independent reaction and purchase-verification budgets while retaining the shared forum-write cap and permitting an active session to log out after a shared address reaches its limit.
- **Billing and verification efficiency:** Share overlapping identical authenticated billing notifications, reuse imported signing keys, refresh rejected Integrity authorization on the next normal retry, and reject announced oversized verification/media replies before download. Durable receipts, refund checks, authorization and decoded-body limits remain enforced.
- **GIF and local performance:** Share simultaneous downloads of the same verified GIF, stop cancelled views before network I/O after waiting, and avoid redundant byte copies, geometry comparisons, temporary ID lists, fog projections and municipality string construction. Failed GIF loads remain retryable and closing the screen clears its bounded cache.
- **Compatibility:** Android 12+, versionCode **88**, versionName **1.0-beta.62**. No change to the package ID, upload key, saved progress, account data or backup format.

The GitHub release workflow publishes signed APK and Play upload AAB files, the tested account function, source archive, deobfuscation mapping and checksums after its required checks succeed. Google Play upload and deployment of the account function/map gateway are separate steps. Existing Forum Plus purchase setup remains unchanged. Separately deployed verified-driving services must allow version code 88 before accepting verified submissions. Editing the documentation does not change live backend configuration. See `server/SERVER_USAGE_AUDIT_20261025.md` for the complete change inventory, regression evidence and bounded tradeoffs.

---

# Road Conquest 1.0 Beta 61

## What's new since Beta 60

- **A discovery preview that matches the map:** The Quick tour uses the app's cloud fog, starts with one clear square, and follows the illustrated streets. Entering the next square clears its fog.
- **Clearer Settings:** Move Quick tour near the bottom, add icons to action buttons, keep privacy descriptions beside their toggles, and explain that exit confirmation applies when pressing Back from the map.
- **Friend-post alerts:** Simplify the description and enable occasional friend-post alerts by default. Existing saved choices stay respected.
- **Community posting:** Enable the forum backend with automatic moderation and recognize signed-in accounts correctly. Temporary service failures show an availability message instead of incorrectly asking signed-in players to sign in again.
- **Readable dialog buttons:** Use white action text in dark mode across community rules and other standard dialogs, with matching light and gold styling and distinct disabled states.
- **Cleaner wording:** Replace semicolons in the app's sentences with periods or commas and replace the overlay labels' em dashes with colons.
- **Compatibility:** Android 12+, versionCode **87**, versionName **1.0-beta.61**. Keep the same package, upload certificate, saved progress, accounts, and backup format.

Forum Plus checkout remains unavailable until its separate Google Play product and refund configuration is complete. The GitHub release includes the signed APK, signed Play upload AAB, source archive, tested account function, deobfuscation mapping, and checksums. Google Play upload is a separate step.

---

# Road Conquest 1.0 Beta 60

## What's new since Beta 59

- **Modern first-use guide:** A replayable six-step Quick tour highlights tracking, map layers, trip history, goals, customization and Settings. The offline discovery preview works without disclosing a location. Tour position and preview survive rotation, and Back can navigate within the guide.
- **Community forum groundwork:** Add opt-in public posts, reactions, reports, blocking, moderator review and appeals. A persistent ten-minute posting cooldown and moderation-before-publication enforce the published rules. The forum is **disabled by default** until its separate server schema, moderation and service credentials are deployed; installing Beta 60 alone does not enable it.
- **Occasional friend-post alerts:** A separate, off-by-default Settings toggle permits a sampled notification for an accepted friend's newly approved forum post. At most one alert is attempted per recipient in any seven-day period, with server-side atomic throttling, block filtering and no background polling. The forum, backend schema and Firebase push configuration must be enabled separately before delivery is possible.
- **Optional Forum Plus groundwork:** A separate lifetime in-app product adds GIF linking and subtle post highlights, with Play verification, restores, and refund notifications. It requires a separately configured Play Console product, backend credentials and live end-to-end verification before being offered; no payment service is activated by the GitHub release itself.
- **Road-matching continuity:** Keep the strict GPS/geometry checks while allowing correctly validated repeated junction vertices and equivalent +180/-180 longitude representations that could otherwise leave short turn segments unmatched. Tests cover both valid turns and rejection of unverified geometry; real driving improvement has not been measured.
- **Other fixes:** Restore a practical GPX export flow, maintain theme-appropriate controls, preserve existing verified-driving and rate-limit defenses, and add an optional Buy Me a Coffee link in Settings.
- **Conservative resource improvements:** Reuse the main map's unchanged overlay mode collection and nearby empty GeoJSON, reuse empty replay features, avoid unnecessary Android label invalidation, reduce duplicate GPS-window processing, and stream Cloudflare budget query rows without intermediate arrays. These preserve existing quotas and provider order. No measured battery or invoice savings are claimed.
- **Compatibility:** Android 12+, versionCode **86**, versionName **1.0-beta.60**. Same Android package, upload certificate, saved driving history, account data and backup format.

The GitHub release workflow publishes a signed APK and Play-ready AAB only when account-server tests, Android unit tests, lint, release-build checks, all map-integration emulator checks, startup tests and signing verification pass. Upload to Google Play, deployment of the server/map gateway and enabling Forum/Forum Plus remain **separate steps**. The separately deployed verified-driving server must also allow versionCode 86 if that feature is enabled; updating its documented allowlist does not change live configuration.

---

# Road Conquest 1.0 Beta 59

## What's new since Beta 58

- **Reliable map fallback:** A legitimate OpenFreeMap cached HTTP 304 no longer marks the primary map offline. Isolated tile errors and failures of optional zoomed-out Natural Earth overview images, glyphs or sprites no longer cause an unnecessary complete basemap switch. Repeated primary vector failures and real authorization/rate limits still trigger the backup map.
- **Clear satellite status:** If Esri satellite imagery fails while streets remain available, the app correctly says that satellite imagery is unavailable instead of incorrectly blaming primary map tiles.
- **Trip GPX export:** Restore the selectable privacy options in the save dialog so the Android document picker launches correctly. Prevent a privacy-redacted export from proceeding for a drive too short to leave any endpoint-safe time range, and improve error feedback when a picker result is invalid.
- **Dark-mode buttons:** Render the Cancel text in the map-overlay selector and GPX dialog using the active theme's foreground color.
- **Conservative optimizations:** Build known-road matching's pending-ID set without an intermediate list, reuse existing road-visit window objects, remove mathematically redundant or unreachable road-grouping conditions, and remove an unreferenced legacy matching constant. Remove unused Natural Earth raster declarations from Minimal, Night and Satellite styles while retaining the actual Streets overview layer; all rendered style layers and their active sources remain unchanged. Matching strictness, mileage, achievement rewards, GPS history and map geometry remain unchanged.
- **Compatibility:** Android 12+, versionCode **85**, versionName **1.0-beta.59**. Same Android package, release upload key, purchases, storage and backup formats.

The updated signed APK and Play-ready AAB are published to GitHub only after Android unit tests, lint, native map integration on Android 12/15/17, release cold-start checks and signing/bundle verification pass. Uploading the release AAB to Google Play and enabling verified-scoring services are separate operations. The separately deployed verified-scoring backend must explicitly allow versionCode 85 if competitive verification is enabled; publishing the release or editing documentation does not deploy its allowlist.

---

# Road Conquest 1.0 Beta 58

## What's new since Beta 57

- **Current Android dependencies:** Upgrade MapLibre OpenGL from 13.6.1 to 13.7.1 and OkHttp/MockWebServer from 4.12.0 to 5.5.0. Retain the stable OpenGL rendering backend, existing road-matching acceptance rules and all supported backup formats.
- **Current build tools:** Update the verified Gradle wrapper from 9.8.1 to 9.8.2, including the official ZIP SHA-256 checksum, and the SHA-pinned setup-java GitHub Action from 6.0.1 to 6.1.0.
- **Release symbols:** Update the pinned MapLibre 13.7.1 native debug-symbol archive and authoritative SHA-256. The release pipeline continues verifying each embedded ABI symbol Build ID against its shipped native library.
- **Emulator tooling:** Update release-drive replay dependencies to grpcio 1.84.0 and protobuf 7.36.2 without changing production GPS recording or how replay data is interpreted.
- **Firebase SDKs:** Update the Firebase BoM from 35.0.1 to the latest stable 35.0.2. The Crashlytics and Cloud Messaging component versions used by this app are unchanged by this particular BoM update.
- **Unchanged dependencies:** Android Gradle Plugin, Google Play Billing/Integrity/Ads, AndroidX, Robolectric, PostgreSQL and esbuild are already at the verified current stable versions.
- **Compatibility:** Android 12+, versionCode **84**, versionName **1.0-beta.58**. Same package ID, upload key, account and backup schemas; existing device progress and Play purchases must remain intact.

This release requires the usual Android unit, lint, native map/emulator, release-startup, account-server, bundle validation, 16 KB library, MapLibre debug-symbol and signing checks before publishing. Upgrading major versions can change implementation behavior, so all verification gates must complete before merging/publishing.

The signed release APK and Play AAB are published to GitHub separately from the Google Play Console upload. The separately deployed verified-driving server must allow versionCode 84; updating documentation alone does not change production allowlists. Public verified competition remains hidden in ordinary builds.

---

# Road Conquest 1.0 Beta 57

## What's new since Beta 56

- **More reliable fog exploration:** Walking and slow driving can accumulate several short, GPS-corroborated movements without requiring a single long leap. Existing anti-spoofing, accuracy, speed and road-mileage acceptance gates remain intact.
- **Safer restores:** Restore detailed saved-road geometry from valid exports even above 50,000 vertices. A confirmed restore finishes its data transaction when its screen closes or rotates, and a visible map refreshes after the replacement commits. Rejected stale backups leave tracking settings unchanged.
- **Account reliability:** Keep encrypted login/signup results across rotation without permitting a duplicate pending request to displace a successful signup. Failed requests allow retry, while stale responses cannot revive a signed-out session.
- **UI polish:** Consistent Back button arrows and more reliable native-car rendering. Map style device tests wait for satellite reloads and recognize the documented fallback behavior.
- **Service-check resilience:** Retry transient invalid replies from the live Census municipality endpoint without hiding an ongoing service failure.
- **Compatibility:** Android 12+, versionCode **83**, versionName **1.0-beta.57**, unchanged app ID and signing identity. Existing roads, GPS, visits, cosmetics, accounts, points, purchases and compatible backups are retained.
- **Release verification:** Signed artifacts publish only after Android unit tests, lint, native map tests on Android 12/15/17, startup tests on Android 12/17, server tests, Play bundle validation and signing checks all pass.

The signed GitHub APK and AAB are released separately from Google Play Console and the account/map backend deployments. Verified-scoring deployments must explicitly allow versionCode 83; adding it to the documented server allowlist does not change production server configuration. Public competitive scoring remains disabled in ordinary builds.

---

# Road Conquest 1.0 Beta 56

## What's new since Beta 55

- **Continuous town borders:** Rejoin shared survey-edge pieces into continuous outlines while retaining every coordinate, real junction, island and hole. This addresses missing or dotted town borders without snapping boundaries or inventing connections.
- **Better short-turn and retry coverage:** Retain the original approach fix at detected turns. A failed backup matching batch now defers only the fixes actually submitted, so unsent fixes can continue. Matching confidence, snap-distance, ambiguity, speed and continuity checks remain in place.
- **Less repeated online matching:** Reuse trusted saved-road geometry only when the existing strict GPS evidence proves the re-drive, and reuse exact cached responses only when every eligible submitted fix is covered. Uncertain or changed evidence continues through online matching.
- **More reliable overlay updates:** Compare exact geometry and popup properties so hash collisions or metadata-only changes cannot leave stale boundaries. Missing map sources and failed uploads remain retryable.
- **Less phone processing:** Reduce repeated geometry parsing, temporary arrays and strings, redundant database writes, and duplicate map-source updates across map refreshes, matching, trip replay, account refreshes and backups. These are targeted resource reductions, not a measured battery-life guarantee.
- **Lower backup overhead:** Stream eligible large geometry fields without creating a second escaped copy. Backup bytes and restore format are preserved. In a local 1,000-road fixture, temporary export allocation falls about 43%; small fields retain the original escaping rules.
- **More accurate cache accounting:** Keep boundary-cache file/byte counts correct after empty-file removal or deletion failure, and read eviction timestamps once per file while keeping the same retention order and limits. Compaction after an explicit stopped-data deletion can reclaim empty database pages; that deletion may take longer and need temporary disk space.
- **More efficient optional server bundle:** Reduce repeated quota-accounting reads, identical in-flight Integrity checks and unchanged account/notification writes while retaining the existing reservations, limits, consent and scoring validation. Server and map-gateway changes require separate deployments; publishing this app release does not activate them.
- **Compatibility:** Android 12+, versionCode **82**, versionName **1.0-beta.56**, unchanged application ID and permanent signing key. Existing saved GPS, roads, visits, points, purchases, accounts and compatible backups remain supported.
- **Release verification:** Publication requires Android unit tests and lint, account-server checks, native map tests on Android 12/15/17, minified-release startup on Android 12/17, bundle validation, signing-certificate checks and 16 KB native-library compatibility.

The GitHub release includes the signed APK, signed Play-ready AAB, Android Studio source archive, tested account-function bundle, R8 mapping and SHA-256 checksums. Uploading the AAB to Google Play and deploying backend services are separate steps. A separately deployed verified-scoring server must include versionCode 82 in its production `PLAY_VERSION_CODES` before accepting verified submissions; updating its documentation does not change production configuration. Ordinary builds keep competitive verification hidden by default.

---

# Road Conquest 1.0 Beta 55

## What's new since Beta 54

- **Cleaner home map:** Show a compact “Tracking” status while recording and shorter location/paused messages. Remove the no-longer-needed database query for a pending-road-matching status badge; saved GPS and the matching pipeline are unchanged.
- **More usable account screen:** Separate username, password, account creation, sign-in and account-management controls with clear spacing; show valid username/password requirements; hide unused leaderboard guidance when competitive features are disabled.
- **Simpler Settings with clearer privacy:** Remove redundant descriptions, keep essential provider and sharing disclosures, and name the destructive button “Delete local data” to distinguish it from deleting a cloud account.
- **Clearer goals and friends:** Simplify weekly-goal guidance and use action-specific confirmation buttons for friend removals and requests.
- **Cleaner trip history and restores:** Reduce redundant explanatory text, reclaim empty replay-label space, keep the replay scroller intact, and preserve the distinction between free progress/replays and VIP extras.
- **Clearer VIP and achievements:** Improve lifetime VIP descriptions and make achievement category touch targets at least 48dp tall.
- **Regression protection:** Update account-spacing and renamed-label tests. All existing road matching, trip backup/restore, in-app purchases and saved-progress behavior remain unchanged by this UI-oriented update.
- **Compatibility:** Android 12+, versionCode **81**, versionName **1.0-beta.55**, unchanged application ID and permanent signing key. Saved GPS, roads, points, purchases, account information and backups are preserved when updating a compatible installation.
- **Release verification:** Android unit tests and lint, account-server checks, device map tests, minified-release startup tests, Play bundle checks, advertising-ID permission and 16 KB native-library compatibility must pass before release publication.

The separately deployed verified-scoring server must allow versionCode 81 before it accepts verified submissions. Its production `PLAY_VERSION_CODES` setting is **not** changed merely by updating the server documentation.

The GitHub release will include a signed APK and a signed AAB. Upload the new versionCode **81** AAB to Google Play separately; merging this PR does **not** publish to Play or deploy backend services.

---

# Road Conquest 1.0 Beta 54

## What's new since Beta 53

- **Google Play advertising ID compatibility:** Explicitly declare the `com.google.android.gms.permission.AD_ID` permission for AdMob and verify that both the final release APK and Google Play App Bundle actually contain it. This guards against a missing permission being silently published in the future.
- **Faster map-provider recovery:** A deliberate remote provider-disable decision no longer counts as a network failure or adds an unnecessary cooldown. Automatic fallback behavior and recovery checks remain in place.
- **Provider settings recover on cache expiry:** Notify map renderers when a cached service configuration expires or becomes corrupt, instead of silently reverting to defaults while leaving the current fallback map unchanged.
- **Safer matching input handling:** Reject non-finite and out-of-range Geoapify coordinates before creating JSON or sending requests; apply LocationIQ's existing validation to cached responses too. Valid driving coordinates and road acceptance thresholds are unchanged.
- **Lower map tile processing overhead:** Cache the map-gateway hostname rather than reparsing its address for every MapLibre HTTP request, with safe handling for an invalid configured URL.
- **Less map-tile overhead:** Avoid allocating a status-code list for each intercepted HTTP tile response and avoid retry timers for explicitly disabled providers. Service-config change notifications still restore an enabled provider.
- **Less provider-failure overhead:** Use the same direct HTTP status/error-code checks in Geoapify, LocationIQ and shared cooldown handling, without allocating a temporary collection on failure.
- **Faster rate-limit recovery parsing:** Numeric `Retry-After` headers bypass RFC-date parsing and its exception path; supported seconds and HTTP-date cooldowns remain unchanged.
- **Less live-GPS battery work:** Ordinary saved GPS fixes go straight to the single-fix database path without assembling a throwaway list. Multi-fix start anchors and turns preserve the original batch ordering.
- **Less map gateway processing per request:** Use direct route checks and a shared set of upstream failure codes rather than rebuilding temporary status/path arrays in the Cloudflare Worker.
- **Faster matching request preparation:** Stream compass guidance into one buffer rather than constructing a separate string/pair for every GPS fix; exact compass values and blank sharp-turn positions remain unchanged.
- **Less map-overlay work:** Check the same Polygon/MultiPolygon types directly instead of allocating a temporary set when calculating viewport bounds.
- **Lower account-ingress overhead:** Reuse the validated URL's hostname to detect accidental self-proxying, avoiding a second URL parse for each Cloudflare Worker request.
- **Less allocation in road grouping:** Empty road names and route references no longer go through Unicode normalization and tokenization, preserving all nonempty grouping rules.
- **Less work in the main map status:** Format the same point balance once for both the visible Garage label and its accessibility description, preserving both texts.
- **More efficient Explore Nearby camera framing:** Convert the already-ordered tile corner coordinates directly rather than creating nested temporary lists; zoom, map bounds and anti-meridian handling stay unchanged.
- **Less work when refreshing rewarded-ad controls:** Reuse a single consent/availability check for each reward-button update; ad gating and earnings behavior are unchanged.
- **More efficient backup exports:** Write skipped GPS fixes and progression CSV rows directly to the buffered ZIP output, avoiding temporary row lists/strings while keeping the exact CSV escape rules and backup format.
- **Faster large-history backup copies and restores:** Read each SQLite cursor's unchanging column-name array once per table rather than once for every GPS/road/progression row; the exported and restored data remain identical.
- **Safer backup imports:** Check the fixed RoadConquest backup entry limit before enumerating ZIP entries, avoiding an unnecessary application-level list for malformed archives. The ZIP library still validates/indexes the archive itself; valid backups are unchanged.
- **Documentation cleanup:** Fix a visible paragraph-formatting error introduced into the Beta 53 README.
- **Compatibility:** Android 12+, versionCode **80**, unchanged package name and permanent release signing identity; saved roads, matching, account data, points and purchases are unaffected. Any separately deployed verified-scoring server must accept version 80 before verified submissions can be enabled.
- **Release validation:** Android unit tests, lint, native map tests, minified-release cold starts, and explicit packaged APK/AAB Advertising ID permission checks must pass before publishing signed files.

Upload the signed Beta 54 AAB through Google Play; do not attempt to replace the already-uploaded versionCode 79. Publishing the GitHub release does not automatically update Google Play or deploy the server.

---

# Road Conquest 1.0 Beta 53

## What's new since Beta 52

- **More consistent blue roads when zooming out:** Reduce display-only GeoJSON simplification at regional scales and use a legible regional stroke with square caps, keeping short saved roads visible without rendering every tiny bend. Detailed road appearance is preserved and custom road colors apply at both zoom ranges. No driving records, map matching, unlocks, or rewards are changed.
- **Cleaner first launch after install/update:** Briefly cover the partially loaded base map until the first complete rendered frame. Resolve the saved starting position first when device location is off; use a three-second timeout so poor connectivity cannot trap the user on a loading screen.
- **Less map-renderer busywork:** Combine repeated map-provider failure refresh notifications and avoid periodic provider retry tasks when no service is in a cooldown. Retain provider fallbacks and normal recovery when service status changes.
- **Less intrusive provider credits:** Remove duplicated LocationIQ links from Settings and map-style attribution while retaining the visible, clickable attribution on the home map required for commercial use.
- **Smoother Gold/Regular appearance switching:** Skip redundant launcher-icon updates and change both launcher aliases together on Android 13+ using the don't-kill-app flag. Screen themes still rebuild safely as needed; Android/launcher implementations may control the process lifecycle.
- **Compatibility:** Android 12+, versionCode **79**, same app ID and signing identity. Existing locally saved progress and purchases stay intact when updating a compatible installation. Any separately deployed verified-driving server must allow version 79 before verified leaderboard uploads are enabled.
- **Release validation:** Account-server tests, Android unit tests/lint/build checks, native MapLibre integration on Android 12/15/17 and minified-release cold-start checks must pass before the workflow publishes signed APK/AAB assets.

Install the signed GitHub APK over compatible GitHub-installed versions. Google Play installations must update through Play using the signed release AAB. This GitHub release does not deploy the account service or publish to Google Play.

---

# Road Conquest 1.0 Beta 52

## What's new since Beta 51

- **Trip replay:** Scroll the whole replay page on smaller screens while retaining map gestures.
- **Clearer controls:** Improve Explore nearby, Hide nearby and Cancel button contrast in dark mode, and remove the requested extra VIP purchase-description sentence.
- **More accurate town shading:** Color a saved town green only when its saved visit coordinate is inside the actual matching town boundary. Existing discoveries, rewards and history remain intact.
- **Cleaner map overview:** Scale confirmed-road width with zoom to reduce distant round-cap dots, and soften municipal fills and boundaries at low zoom. Keep the existing map provider, efficient tiling and Minimal map style.
- **Fewer duplicate population requests:** Share overlapping Census/REST Countries lookups for the same place, including waiting callers. A data reset still invalidates older queued work, and unrelated places remain independent.
- **Less repeated road-check work:** Decode a saved road's geometry once per departure scan instead of once per GPS fix. Preserve all distance checks, including departures late in a long scan; no lasting geometry cache is added.
- **Included account-server improvements:** The attached server bundle reduces normal verified batches from 12 to 10 database statements and exact retries from two to one. Rejected Google responses begin cleanup without delaying the original error. Eligibility, device checks, scoring and safe retries remain enforced. These backend improvements require a separate server deployment; public verified leaderboards remain hidden by default.
- **Compatibility:** Android 12+, versionCode **78**, same package ID and permanent signing identity. Updating a compatible installation preserves local progress and purchases. A separately deployed verified-scoring server must include 78 in its version allowlist.
- **Release checks:** Account-server tests, Android unit/lint/build checks, native map tests on Android 12/15/17, and minified-release cold starts must pass before publishing the signed APK/AAB. Obsolete implementation assertions were pruned while saved-data, scoring, road-matching and startup coverage remains.

Install the signed GitHub APK over a compatible GitHub-installed beta. Update Play installations through Google Play; the signed AAB is the Play upload file. No production-service deployment or Google Play publication is performed by this GitHub release.

---

# Road Conquest 1.0 Beta 51

## What's new since Beta 50

- **More usable map:** Reduce bottom-sheet and floating-control space, improve navigation icon spacing, and show numbered green/gold nearby 5×5 explored/unexplored map tiles instead of a text-only popup.
- **Smoother initial map:** Initialize the camera before drawing the first GPS-dependent map layers and keep a ready native world-fog overlay during rapid zoom changes.
- **More efficient matching:** Pack the Geoapify primary into 100-point, one-credit batches, keep sparse traces eligible, and avoid additional provider requests for complete, exact cached match evidence. Existing confidence and road-acceptance checks remain.
- **Safer outages and accounting:** Ensure partial/negative cached answers do not bypass a healthy backup provider. Protect a currently leased provider-recovery probe against late failures from other requests; conserve free provider allowances during cancellation and retry.
- **Better international place discovery:** Preserve countries reported by ISO code alone, prioritize fully country-coded results over adjacent code-less postal addresses, prevent cross-border address results from mixing jurisdictions, and recognize English aliases of valid foreign municipal polygons without relaxing geographic containment or administrative classification.
- **International limitations:** GPS and road-history coordinates are worldwide. Actual road and municipal boundary coverage varies by provider. The default public Nominatim service is subject to an application-wide request limit, so international town discoveries are not guaranteed at large scale. See [international support](INTERNATIONAL_SUPPORT.md).
- **Preserve compatibility:** Android 12+, same app ID, no reset of local progress or purchases, versionCode **77**. Public verified leaderboards remain disabled by default; production verified scoring needs a separately configured server allowlist that includes 77.
- **Release gates:** Android unit/lint/build, Android 12/15/17 map integration, account-server tests, and minified-release cold starts must pass before publishing a signed APK/AAB.
- **Native map test reliability:** Remove two unstable assertions that sampled transient TextureView frames during camera animations. Retain settled overview/rotation, polar/date-line, saved-clearing, native road visibility, and all map/release-startup tests. Animation-frame fog quality still requires manual GPU/device testing.

---

# Road Conquest 1.0 Beta 50 (previous release)

## What's new since Beta 49

- **Fewer matching credits:** Adjacent unmatched GPS gaps can be submitted together using real, already-verified saved fixes as context. New GPS evidence, boundaries and confidence requirements remain authoritative; context never earns duplicate road credit.
- **Safer primary/backup matching:** Track the exact number of points sent to the provider that really handled the request, so unsubmitted GPS is not treated as attempted. Repeated *explicit* NoMatch responses on unchanged evidence back off temporarily; ambiguous intersections retain normal retries.
- **Lower network overhead:** Exact matching requests reuse successful provider responses for up to 15 minutes, with the geometry revalidated against the current GPS IDs. Repeated Firebase device registrations are avoided for six hours after confirmed success; changed devices, sessions and consent trigger fresh registration.
- **Cheaper idle database work:** Expired account records are purged hourly instead of every 15 minutes, while access checks still reject expired records immediately. Road-gap anchor lookups and local place discovery now require fewer SQLite queries.
- **Preserved immediate road matching:** The experimental asynchronous Geoapify job system was removed in full. There is no delayed-job polling or altered location-history retention.
- **Reliability and cleanup:** Additional tests and simplifications for saved tracks, backup helpers, map rendering, location lookup, achievements and account security. Existing progress and purchases remain intact, and optional verified competition remains hidden by default.

## Included features and detailed changes


- **Simpler map UI:** Group Driving miles/roads and Exploration tiles in a compact summary; Settings now has categorized Driving, Exploration, Progression, Tracking and Backup details. The three bottom destinations are Trips, Badges and Goals; the already-clickable points chip is clearly labeled Garage, removing its redundant bottom-bar button.

- **Other features included in Beta 50:** Free trip summaries now report estimated road unlocks, discovered towns, dated map-tile unlocks, and GPS matching still pending. Saved mile tiles carry first-unlock dates via a backward-compatible schema-14 migration and ZIP format 11. Unknown legacy dates stay unknown; backup formats 9/10 remain importable. The map overlay picker adds a local 5×5 exploration goal view, while Settings records the time of the last successful progress export.
- **Lifetime VIP trip extras:** The existing $2.99 `vip_lifetime` purchase additionally unlocks browsing up to 500 recent trips, sharing text summaries, and privately saving GPX files (first/last two minutes hidden by default). Free users retain 100 recent trips, replay, basic summaries, and complete data backup/restore.
- **Less clutter / safer progression:** Retire the 1%/5% battery achievements for new awards while preserving earned points, hide the Bonus filter when empty, rename the map Weekly button to Goals, collapse optional friends behind Manage friends while verified leaderboards remain hidden, and identify pending road matching separately from saved GPS.


- **Repository audit:** Correct date-line fog projection so one explored tile cannot reveal a world-wide strip. Cached municipal lookups now retain the actual current visit coordinate, and Census state fallback accepts valid fragmented responses while keeping its response-size bound.
- **Polar fog accuracy:** Keep detailed and regional fog rasters within Mercator bounds so clamped latitude corners cannot stretch cleared tiles away from their saved coordinates.
- **VIP ownership ordering:** Ignore ownership queries superseded by a newer check or purchase callback, including confirmations that match the offline cache. Successful current checks still detect refunds and preserve point-purchased cosmetics.
- **Account deletion reliability:** Expire transient backend records in separate short transactions, avoiding the cross-table lock cycle found between batched cleanup and account-deletion cascades. Retention thresholds and runtime permissions are unchanged.
- **Scalable weekly comparisons:** Select the caller and accepted friends before reading their weekly standings, avoiding scans of unrelated accounts. Add three backend foreign-key indexes for receipt and friendship cleanup; applying the updated schema remains a separate deployment step.
- **Recovery and CI reliability:** Atomically replace an existing restore recovery ZIP, including on Windows development hosts; failed replacement preserves live progress and the existing recovery target. Startup CI downloads only its tested APK instead of the full bundle/symbol inputs.

- **Reset consistency:** Population lookups started before Delete all data can no longer repopulate the persistent 2020 Census cache after it has been cleared. A bounded generation guard also protects the in-memory cache while preserving successful historical cache hits.

- **Idempotent privacy updates:** Repeated requests to save an unchanged leaderboard visibility value no longer rewrite the same account row, while still returning the requested setting.

- **Lower database write load on blocked requests:** Saturated rate-limit windows now refuse further requests without rewriting the same unchanged counter row. Allowed requests, first over-limit requests, and window resets retain the existing limits and expiry behavior.

- **Less duplicated verified GPS work:** After a retry-receipt miss, the account server now checks freshness on the already validated GPS evidence instead of parsing and fully validating the same JSON batch a second time. The same timestamps, motion rules, device integrity, and scoring remain enforced.

- **More database round-trip reductions:** Friend requests now resolve and lock both participants in one ordered query instead of separate lookup and lock requests. Verified batches initialize/read the user's score checkpoint in one statement instead of two, without changing security checks, the lock order, or checkpoint continuity.

- **Lower competition and friends request load:** Server-verified drive challenges now check eligibility while writing the challenge, saving one PostgreSQL round trip without allowing ineligible accounts to start. Rapid repeated Refresh taps skip stale queued account requests in Friends and Leaderboards; a newer refresh still fetches the latest results.

- **Fewer repeat Census requests:** Cache only verified, immutable 2020 U.S. Census population counts on the device between app sessions. Never cache failures or dynamic REST Countries results across sessions; Delete all data also clears this historical cache.

- **Fewer external provider configuration fetches:** Each bounded place-discovery batch shares one overlay client across its non-U.S. town lookups. This avoids redownloading the same endpoint configuration for every candidate, with no changes to boundary validation, polygon caches, or place rewards. The next batch reads a fresh configuration.

- **Less server verification CPU:** Reuse each GPS fix's cell calculation and accuracy-margin check between adjacent pairs, retaining the original two-fix scoring thresholds and resulting cell IDs.
- **Cheaper account deletion:** Remove the five account-specific throttle records in one PostgreSQL request instead of five separate queries, preserving exact deletion scope.
- **Fewer verified-drive writes:** Advancing the secure drive sequence and saving its idempotent retry receipt now share one PostgreSQL statement. Transaction boundaries, session checks, scoring, and safe retries remain unchanged.
- **Faster serverless cold starts:** Remove the redundant scrypt calculation at instance initialization. Missing-user logins still perform the same scrypt verification as existing-user failures, using a randomized dummy hash that cannot accidentally authenticate.
- **Lower account-server database overhead:** Return the latest verified-cell total directly from its score update instead of querying again; resolve the UTC weekly period and rankings together in one query. Routine expiry cleanup uses six sequential, independently committed statements so it does not retain locks across tables. This trades five maintenance round trips per startup or 15-minute sweep for safer account deletion, without adding parallel connections or changing retention windows.

- **Live walking exploration progress:** Newly revealed fog tiles now schedule the existing debounced statistics/achievement refresh even when the player is walking and no driving fixes are stored; no extra location requests.
- **Legacy reward accuracy:** Previously claimed achievement cards display the original payout from the reward ledger, even if later versions rebalance a milestone. No balance change, extra database scan, or repeat award.
- **Audit fixes:** Refresh achievement wallet and milestones on screen resume without losing the selected category; retain friend-request success/error feedback and re-enable action buttons after a failed request.
- **Economy pacing:** Keep the five-points-per-road rate, town/state/country discovery payouts, weekly challenge rewards and existing Garage prices. Reduce low-battery milestone bonuses from 1,300 total to 500 total, while preserving all five rewarded-ad milestone payouts (4,300 points combined) because players can earn them by watching rewarded ads. Exploration rewards remain the main repeatable driving-based progression. Achievement IDs remain unchanged, so previously claimed rewards retain their original ledger amounts without duplication.
- **One-mile map tile achievements:** Ten milestones for 1, 10, 25, 50, 100, 250, 500, 1,000, 2,500 and 5,000 unique unlocked map tiles, with one-time point rewards from 75 to 8,000. Counts come from persisted `explored_grid`, not 50-meter fog samples or independently verified leaderboard cells. Existing reward ownership prevents duplicate payments, including after restore.
- **Progress visibility:** Map, Settings and Garage show map tile totals. Achievements have a new Map Tiles category and prioritize near-complete unfinished milestones.
- **Garage:** Shows real car shape/color previews via the same renderer used by the map, plus road-color and theme swatches, without changing purchases or currently equipped cosmetics.
- **Navigation:** The map's quick destinations have themed icons and clearer Weekly labeling. Secondary screens have an explicit Back button. Existing bottom sheet placement and location permissions are unchanged.
- **Trips and social:** Trip history emphasizes dates, distances, durations and replay instead of GPS-fix counts. Weekly challenge progress uses separate progress bars with reward states; friend requests show explicit actions. Verified leaderboard entries are visually separated when this optional feature is enabled.
- **Compatibility:** Android 12+, versionCode **75**, same application ID and signing identity. No new GPS collection, background tasks, Android database format changes, or changes to road-matching acceptance. Stored exploration and purchases remain intact; fog projection is corrected without changing the grid. The backend adds three indexes without rewriting progress. The documented competition allowlist includes 75; a separately deployed server still requires corresponding allowlist deployment. Public leaderboards remain hidden by default.
- **Validation:** Android compilation, local unit tests, lint, emulator map integration and signed release startup should pass before distributing this build.

Keep existing app data when updating a compatible install. For Play installations, use the signed release AAB via Play; the GitHub APK may use a different app-signing identity.

---

# Beta 48 (previous release)

- **Population details:** New on-demand population lookup for visited U.S. states and Census-identified municipalities using official 2020 Census POP100 counts from the Census2020 TIGERweb service. U.S. country total comes from the 2020 Census; other countries with ISO country-code keys can use REST Countries. Display labels identify 2020 data. Unsupported places honestly remain unavailable, and offline requests do not affect saved polygons or travel data.
- **Exact edge-to-edge bottom panel:** Remove the 1dp border previously visible along its sides and bottom. The sheet still has rounded top corners and safe-area-aware content padding.
- **Turn matching:** Retains the ordinary no-bearing OSRM retry for supported corners. When a precise, continuous gap remains unresolved despite matching both ends (including at low speeds), a final higher-confidence road-edge candidate retry can recover it without discarding accepted fixes, relaxing GPS snap/detour limits, or synthesizing road geometry. Extra network matching requests are possible for persistent corner gaps.
- **Compatibility:** Android 12+, versionCode **74** (Beta 47 was 73). Existing package ID and permanent signing key remain unchanged, and all previous progress and purchases remain saved. Include 74 in the documented `PLAY_VERSION_CODES` allowlist if a separately deployed server uses verified scoring. Public leaderboards remain disabled by default.
- **Validation:** Android unit, lint, emulator, backend, minified-release-startup and signed Play-bundle checks are release gates.

Install signed GitHub APK over a compatible GitHub APK install, or use the signed AAB in a Play testing track to update a Play-installed version. Don't clear app data or switch signing identities. Export a backup before changing distribution channels.

---

# Beta 47 (previous release)


- **More rewarding early and long-term progression:** Adds weekly goals for five newly unlocked roads and one newly visited town (75 points each) alongside the existing 25-road and three-town milestones. Adds intermediate and longer-term road, mileage and place achievements. Earned rewards and historical ledgers remain intact.
- **Shop progression:** Rebalances prices across starter and aspirational cosmetics, adds two vehicle shapes, three car colors and three road colors, and shows points needed before an item can be purchased. The Gold UI now costs 6,500 points unless unlocked through lifetime VIP. Previously purchased items stay owned; the other VIP benefits are unchanged.
- **Missed-town recovery:** Resolves pending place candidates separately so a failed response cannot stop the whole queue, retries empty U.S. town lookups with a bound and backoff, and drains saved town candidates more promptly using the already-running tracking service. This can increase network lookups while a backlog exists, but does not sample GPS more often.
- **Missing turns:** Allows a carefully bounded second map-matching attempt for short, well-supported intersections, preserving accepted GPS fixes and map-match confidence gates. No unverified road connectors or extra leaderboard credit are added.
- **Bottom tracking panel:** Attaches the location/tracking panel to the bottom of the map with rounded upper corners and navigation-bar inset handling.
- **Performance:** Consolidates weekly reward-state queries, reuses known trip fix counts in replay, and reduces per-pixel and per-cell work when rendering fog. No extra GPS collection or new background jobs from these optimizations.
- **Compatibility and release safety:** Android 12+; application ID and permanent signing identity unchanged; versionCode **73** (Beta 46 was 72). Adds 73 to the documented `PLAY_VERSION_CODES` allowlist. A separately deployed verification server needs its own allowlist updated before accepting verified scores from Play-installed Beta 47. Public competition remains disabled by default.
- **Checks:** GitHub Actions must pass account-server tests, Android unit tests, lint, bundle/build validation, Android emulator map tests, and release cold-start tests before creating the signed GitHub release.

Install the signed Beta 47 APK over a compatible **GitHub-installed** beta to keep local progress. For an existing **Google Play installation**, update through Play using the signed Beta 47 AAB instead. These distribution channels may have different app-signing keys. Do not uninstall or clear data to fix a signature mismatch; export a backup before switching channels. The `vip_lifetime` $2.99 purchase still requires activation in Google Play Console.

---

# Beta 46 (previous release)


- **Refreshed driving dashboard:** Updated navigation and status cards with direct access to trips, achievements, garage and weekly goals; more consistent light, dark and gold presentation.
- **Trip replay:** Roads turn blue progressively as recorded positions play, without revealing the full route early. Rewinding removes later blue segments and skips unrecorded GPS gaps, including those near the last point after downsampling. Selected trips and elapsed time are easier to identify.
- **Usability and accessibility:** Clear single-choice map overlay selection (including no overlay), readable controls at larger text sizes, improved button states and screen-reader navigation, and clearer sign-in and challenge-reset labels.
- **Performance:** Reuse replay map coordinates and skip queued, superseded trip loads to avoid unnecessary database work.
- **No progress reset:** Tracking, road matching, stored drives, fog, account score calculation and migration formats are unchanged by this update.
- **Release compatibility:** Android versionCode **72** (Beta 45 was 71). Includes the new code in the verified-score server's documented Play allowlist. Any separately deployed server configured with `PLAY_VERSION_CODES` must also permit 72 before it will accept verification from a Play-installed Beta 46 build; public leaderboards remain disabled by default.
- **Verification:** Includes repository replay-regression tests, account-server checks, unit tests, lint, build/bundle checks, Android emulator map tests and release cold-start tests in the CI release gate.

**Android 12+.** Install the signed Beta 46 GitHub APK over a compatible GitHub-installed beta to keep local progress; Play-installed builds must be updated through Google Play. The signed AAB is the Play upload file. The GitHub APK upload key may differ from Play app signing. Do not uninstall or clear app data to work around a signature mismatch; export a backup before switching distribution channels. VIP still requires its Google Play Console product configuration.

---

# Beta 45 (previous release)

- **Verified exploration cells:** Competitive scoring now uses unique squares from the existing exploration grid; weekly friend rankings count first-ever newly verified cells. Require licensed Play-installed builds and strong device integrity, bind every batch to a fresh server challenge, reject impossible cross-drive travel, and isolate verified scores from older totals. Ranked results never fall back to local or restored progress. Public rankings remain hidden pending real-phone Play testing.

- **Progress restore:** Validates exported ZIPs before replacing history, restores the points ledger and purchases, and saves a private recovery backup first. Imported driving never earns verified credit.
- **Restore and verification recovery:** Reject corrupt progression counters and unsafe GPS row IDs, rebuild the GPS counter so tracking can continue after restoring, and acknowledge exact already-recorded score batches after delayed retries without adding credit twice. Invalid Google tokens are handled separately from temporary verification outages.
- **Weekly challenges and friends:** Earn points once per UTC week for 25 new roads or three new towns. Add, accept and remove friends by username. Competitive rankings and verification uploads are temporarily hidden; local weekly rewards remain available.
- **Saved-trip replay:** Browse saved drives and replay routes without joining gaps in GPS coverage.
- **Discovery and VIP fixes:** Resolve places while background tracking, fall back to official Census geography when Android geocoding is absent or incomplete, and preserve earned achievements while excluding unavailable VIP ad goals.
- **Build maintenance:** Incorporates the GitHub Actions updates and Gradle 9.8.1 wrapper update from the dependency branches.

- **Google Play upload fix:** Increases Android versionCode from 70 to **71** so this newly signed app bundle can be uploaded after Google Play reserved version code 70. No data reset or package ID change is necessary.
- **Carries forward Beta 44:** The same VIP, road-matching, municipality, fog and battery improvements are retained.

- **Lifetime VIP (Google Play):** Adds a $2.99 USD one-time VIP product (to be activated in Play Console). Once owned, VIP removes banner and rewarded ads and makes Gold car color, Gold roads and Golden Road Conquest UI free to equip. Ownership can be restored from Google Play and survives a local driving-history reset. Pending payments do not grant access; successful purchase refunds revoke VIP-only gold choices.
- **Road-matching reliability:** Rate-limit public OSRM demo requests to approximately one per second per app, respect HTTP 429/503 Retry-After cooldowns, and stop retry bursts during provider outages. Unmatched GPS fixes remain on the device for later matching. No paid service or new demo-provider dependency is added.
- **Official municipality consistency:** More accurately capture short municipal crossings, support the official Census consolidated-city layer where applicable, and use saved official GEOIDs to reload a municipality despite renamed or revised boundaries.
- **Map and battery optimization:** Avoid unnecessary fog projection and image uploads when geographic keys are unchanged, cull offscreen exploration cells from fog processing, remove obsolete projection caches and avoid repeated polygon topology fixes for disjoint town shapes. Preserve camera-triggered fog refreshes.
- **Verification:** Google Play Billing 9.1.0, unit and integration tests, Android 12/15/17 emulators, account-server tests, bundle signing checks and minified release startup checks are configured in CI.

**Version code 71, Android 12+.** Install the signed Beta 45 APK over a compatible GitHub-installed Beta 44 (or earlier) app to retain locally saved roads, points and history. Update Google Play installations through Play; the signed AAB is the Play upload file. The GitHub APK's upload key and Play's app-signing key are separate. Do not uninstall or clear app data to work around a signature error; export a progress backup before changing installation channels. VIP requires the non-consumable Play product `vip_lifetime` to be activated in Play Console; a GitHub/sideloaded beta cannot simulate payment.

**Production note:** The public OSRM server is a rate-limited community demo without an uptime guarantee; growing public usage will require a production-authorized matcher. VIP also requires a Google Play Console product configuration and, before significant commercial distribution, server-side purchase-token verification is recommended. See [PLAY_VIP_SETUP.md](PLAY_VIP_SETUP.md).
