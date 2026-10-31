# Road Conquest 1.1

## Changes since 1.0

- **Less unnecessary SQLite work:** Rendering a viewport with fewer than two saved road fragments skips loading road-visit windows, which cannot produce a junction in that case. The geometry-indexed junction search runs locally.
- **Verified-geometry junction display:** Junction-guessing and GPS checks remain active; the live map only draws an estimated connection if an already-saved, matching road centerline supports its full path. Unverified diagonal and V-shaped extrapolations are hidden, not saved or credited. Existing road records, points and history are preserved, and no additional network calls are made. Missing centerline coverage can leave an honest gap until the road is matched again.
- **Password-free, export-only ZIPs:** New exports write a readable ZIP directly to Android's document picker without Play verification or a passphrase. These are explicitly archive-only and cannot be imported back into Road Conquest. The removed second dialog no longer creates the cancelled-flow zero-byte file. A failed or interrupted active file write may still leave an incomplete file if Android's provider refuses deletion. Older authenticated restore formats remain unchanged.
- **More balanced new earnings:** New roads earn 2 points, towns 35, states or regions 150, and countries 500. Weekly challenges and future achievement milestones award fewer points. Existing balances, earned ledger entries, purchases and already-owned achievements are preserved.
- **Starting-place statistics:** The first verified starting town, state/region and country now appear as explored in Settings → Your progress. They do not give starting-place points or count toward earned milestones.
- **Clearer choices:** Backup export formats and map-overlay options use separated tappable buttons; the Explore Nearby action is visually distinct. Town medal icons occupy less map space.
- **Boundary display:** State and region overlays request unsimplified geometry where supported, refresh old render caches, and partition overlapping neighboring snapshots for display. Source polygons and rewards are not rewritten; inconsistent upstream boundaries may remain.
- **Map settings:** Optional town medals, automatic car following and display-only distance units. Tracking gains an optional keep-screen-awake toggle; controls include concise descriptions.
- **Reduced avoidable UI work:** Opening settings or switching categories without a search no longer builds search text by recursively reading all controls. Search still examines live settings text when a query exists, and its whitespace matching is unchanged.
- **More robust worldwide region fallback:** A malformed unrelated entry in a geoBoundaries dataset no longer hides a valid matching region. Exact names, containment checks and ambiguous-match rejection remain enforced.
- **Valid polygon fallback:** A high-ranked zero-area worldwide boundary no longer hides a valid lower-ranked polygon; geoBoundaries also ignores invalid zero-area features when checking for ambiguous duplicates. Existing name, containment and score checks remain intact.
- **Avoided matching allocations:** When no previously driven road is recognized, normal and approach-window matching reuse the pending-ID set rather than allocating an empty derived set. Acceptance thresholds and network request decisions are unchanged.
- **Clock-safe boundary cache:** A negative cached place lookup with a future timestamp is invalidated after device clock changes instead of suppressing retries indefinitely; fresh negative results still retain their bounded cache window.
- **Date-line overlay safety:** Region polygons spanning the International Date Line bypass planar overlap clipping, and world-spanning viewport bounds no longer hide date-line towns. This conservatively retains source geometry instead of inventing a wrong clipped boundary.
- **Country fallback metadata:** A valid geoBoundaries country polygon no longer requires region-only `shapeName` metadata. State and region overlays still enforce exact matching names and reject genuinely ambiguous geometry.
- **Clock-stable provider refresh:** The optional provider-switch configuration uses Android's monotonic elapsed clock for its in-process retry deadline, preventing a phone clock rollback from delaying retries beyond their intended interval. Expiry of saved configuration still uses the wall clock.
- **Reclaimed orphan boundary-cache storage:** When a state is accessed, delete only its unused previous-resolution state polygon from the shared legacy state/country directory, retaining current full-resolution states and countries. The existing cache scan also deletes abandoned `.json.new` writes after process death; committed and recoverable `.bak` files remain protected. Savings depend on whether those leftovers exist.
- **Less empty map work:** State/country overlays and town overlays with medals disabled reuse the existing empty badge collection rather than allocating and serializing an unused one.
- **Current overlay configuration location:** Read the public overlay-provider configuration from the canonical Road-Conquest repository without relying on a legacy repository redirect.
- **Smaller fallback parser allocation:** LocationIQ's NoMatch/NoSegment check uses two direct comparisons instead of allocating a temporary set. This does not change matching confidence, GPS evidence, requests, or fallback behavior.
- **Test infrastructure:** Existing SQLite test fixtures batch independent inserts, reuse selected connections, and avoid repeated immutable fixture/regex creation. The server CI job caches verified npm downloads while still running `npm ci`.

**Version:** Android versionName **1.1**, versionCode **96**, Android 12+ (API 31 minimum). No local database migration or account protocol change is introduced by these release changes.

**Compatibility and verification:** Existing backup formats, purchases, stored progress and scoring rules for already-earned rewards remain intact. The code-level reductions above are expected to save avoidable allocations or work; no measured whole-app startup, battery or server-cost improvement is claimed. Release publishing, signing and device checks must finish in CI before this version is considered published.

---

# Road Conquest 1.0

## What's new since Beta 68

- **Town completion medals:** Town overlays show a small explored percentage and a bronze badge at 25%, silver at 50%, or gold at **100%**. Completion measures the part of unlocked map tiles inside the original town boundary. Border tiles, holes, disconnected parts and visual overlaps do not inflate coverage. Earned tiers survive full backups.
- **30 new achievements:** More road, tile, mileage and place milestones, plus ten achievements in the new Town Badges category. Existing awards and payouts remain owned. Achievement filters survive rotation.
- **Searchable settings:** Seven categories with a dedicated Backups & data section, last-backup status, control-label search, and Back navigation to the category list. Navigation survives rotation and theme changes.
- **Faster local calculations:** Prepared polygon checks avoid unnecessary intersections, visible town results are reused on unchanged pans, geometry caching is bounded, and town backfill reuses existing tile totals without rereading unchanged progression. Completed, unchanged backfill skips repository setup, and trip replay collects camera bounds in one pass instead of four. Coverage reuses each validated tile, computes polygon centers once, and rejects out-of-bounds tile envelopes before allocating polygons.
- **Fewer unnecessary requests and reads:** Obsolete queued settings account reads, achievement loads and trip-list scans are skipped. Settings reuses one backup timestamp and formatted date for its two summaries. Interrupted GPS uploads fail through the private JSON contract before any provider allowance is used, and oversized uploads retain HTTP 413 even when cancellation fails.
- **Restore cancellation:** Encrypted restores honor cancellation while reading ciphertext and plaintext and remove partial decrypted output on failure.
- **Clearer restore errors:** Preserve file/storage errors when writing a decrypted backup instead of incorrectly reporting a wrong passphrase. Wrong-passphrase and altered-file checks still reject unauthenticated data and remove partial output.
- **Readable system controls:** Navigation-bar icons follow the app's theme, and fog-off daytime maps use dark status-bar icons.
- **Date-line trip replay:** Crossing from 179°E to 179°W keeps the replay camera near the trip instead of fitting almost the whole world.
- **Cancelled matching:** Interrupted matching stops before starting another provider request, response reads honor cancellation, and cancellation does not impose a false provider cooldown.
- **Less duplicate server work:** Overlapping copies of the same community submission share active receipt/profile checks as well as moderation. Completed and failed reads are never cached; account isolation, posting cooldowns and request limits remain enforced.
- **Bounded password memory:** Account hashing and verification share two active operations and up to eight waiting callers per server instance. Busy bursts may wait or receive a retryable HTTP 503 rather than starting more memory-heavy work. Existing password strength, hashes and attempt limits stay the same.
- **Unicode passwords:** Sign-in, confirmation, account-change/deletion fields and the web deletion form retain the full password range supported by the server, including emoji, instead of silently shortening longer Unicode passwords. The 8–128 code-point policy is unchanged.
- **Stable release identity:** versionName **1.0**, versionCode **95**, Android 12+. The Android application ID and permanent signing identity stay the same, with no database migration.

**Deployment and compatibility:** Town medals and UI improvements are local app changes. Activating the interrupted-upload gateway fix requires separately deploying the updated maps Worker; the database and password-memory improvements require deploying the updated account function. Neither needs a database migration. Existing tracking, backup formats, local weekly eligibility and verified scoring policies are retained. Public/friend competitive rankings remain hidden by the existing feature flag. GitHub publication and any Play upload remain gated on their required release checks.

---

# Road Conquest 1.0 Beta 68

## What's new since Beta 67

- **GitHub backups now have a portable option:** Settings → Export progress offers a private `.rcbackup` file that works without Google Play licensing or any backend. The entire progress ZIP, including GPS history, is encrypted with AES-256-GCM using a passphrase the user must retain. Imports require the same passphrase and pass all existing ZIP/CSV, metadata, provenance and ledger checks. Offline export uses a new random salt and nonce for every file. A private backup is not a normal ZIP and does not affect verified scoring.
- **Faster, smaller private exports:** Write the encrypted ZIP directly to the selected destination rather than creating and rereading a second plaintext ZIP in cache. Prevent stale exports when device history is deleted or restored during the write. This lowers temporary storage and disk I/O without changing the encrypted file format.
- **Retained Play-signed backups:** The existing `.zip` export continues to require the separately deployed Play Integrity signing service and a licensed Play build. Its trust policy, restore behavior and compatibility remain unchanged. Unsigned legacy ZIP archives are still rejected.
- **Diagnostic export errors:** If Android's file provider refuses to delete a partial output, the error dialog reports the ORIGINAL failure and warns about the possible incomplete file separately.
- **GPS-backed road and intersection repairs:** Preserve the ordinary 30 m/15 s verified-geometry turn display fallback, and add a stricter way to display otherwise missing turns up to 65 m or 45 seconds when three time-ordered GPS fixes actually show the approach, junction and exit. Straight seams (at most 25 m) additionally require real saved GPS movement, matching road names and aligned bearings. These repairs are visual-only: they do not mark pending fixes matched, create new roads, mileage, points, achievements, or verified credit. Missing/weak GPS, walking motion, reversed directions and ambiguous connections remain unresolved instead of fabricating roads.
- **Compatibility:** Android 12+, versionCode **94**, versionName **1.0-beta.68**. No database schema, app identity, signing key, or server deployment change.

**Testing / release requirements:** Verify full Android unit tests, lint, device emulator and release-startup gates before merging. Check an actual GitHub-installed APK: export a private file, restore it offline on a second installation using its passphrase, reject altered files and wrong passphrases, and verify historical road continuity without awarding credits. User GPS evidence from the screenshots was not available for automated reproduction; these changes do not guarantee every historic turn can be recovered. Confirm version code 94 is unused in Google Play before uploading an AAB. The Google Play signing service must be deployed separately if using Play-signed ZIP exports.

---

# Road Conquest 1.0 Beta 67

## What's new since Beta 66

- **Walking and driving agree:** The walking marker stays below the 2.2 m/s driving threshold, and switches back to the car at that threshold after the existing hysteresis. This preserves slow driving and turns.
- **Final road matching:** Stopping tracking permits one final matching pass past the local batching delay. Provider cooldowns and quota limits still apply. Data deletion starts no final request, and partial results do not trigger a shutdown retry burst.
- **Reliable VIP restoration:** A newer pending or failed ownership query cannot discard a successful restoration. Newer successful refunds and purchase callbacks still override stale results. Garage ads share the Garage purchase check, removing duplicate Play clients and lookups.
- **Provider recovery:** Valid Retry-After delays on authorization failures allow a timely recovery instead of forcing 24 hours. Missing or malformed headers keep the conservative fallback, and existing longer cooldowns cannot be shortened.
- **Current trip history:** Returning to Trips checks the newest saved point before rescanning. New background drives appear, and an extended selected drive reloads its replay and summary while retaining selection.
- **Tracking resumes from every app screen:** Reopening Garage, Trips, Account, Badges, Goals, Community, Restore or Rankings resumes a notification-paused session when location access permits. Internal navigation, notification-shade dismissal and recreation retain the stop; deletion remains blocked.
- **Future Play builds:** Protected backups and verified requests accept version codes 90 and newer. Certificate, licensing, device-integrity and request-binding checks remain required. CI and backup preflight use the same minimum policy.
- **Compatibility:** Android 12+, versionCode **93**, versionName **1.0-beta.67**.

**Backend and publishing:** The tested account-function policy is already active as Neon deployment 36. No additional map-gateway or database change is required for Beta 67. Signed GitHub APK/AAB publication is gated on the full test, lint, native-map and release-startup suite. Google Play upload remains separate; confirm version code 93 is unused in Play Console. Physical-device Play purchases and protected export/restore still require real-device verification.

---

# Road Conquest 1.0 Beta 66

## What's new since Beta 65

- **More matching headroom on Free:** The map gateway increases the shared Geoapify limit from 2,400 to 2,700 credits per UTC day and LocationIQ from 4,000 to 4,500 requests/day. Both remain at 90% of the published free-tier allowances, keeping a 10% reserve. Existing per-client limits, fallback order, matching strictness, GPS capture, storage, and cooldown pacing are unchanged. This increases admitted matching work, not guaranteed aggregate driving hours.
- **Less gateway overhead:** Direct-client checks now reject untrusted map-matching and raster-tile calls before reading traces or provider switches. The expected response for unauthorized origins can become HTTP 403 even if a provider is disabled or the GPS body is malformed. Authorized callers retain their normal error handling.
- **Lower quota-accounting read load:** The Cloudflare Durable Object uses a bounded 1,024-key in-memory counter cache instead of 512. Multi-client tests show repeated requests can reuse more counters without extra SQL SELECTs; durable reservations, refunds, restarts, and hard caps remain enforced. The larger cache consumes some additional Worker memory.
- **Repository cleanup:** Remove three unused historical review/evidence files, retain all runtime resources, licenses, release tools and reproducibility-critical fixtures, and correct stale published quota descriptions.
- **Compatibility:** Android 12+, versionCode **92**, versionName **1.0-beta.66**. App identity, signing key, local progress, purchase identities, and backend database schema are unchanged.

**Deployment and publishing:** Merging this source and publishing the GitHub release does **not** deploy the map gateway, account function, or Play Store update. Deploy `server/maps/wrangler.jsonc` to Cloudflare to activate the higher quotas and request-handling improvements. Confirm production Play Integrity configuration allows version code 92 before relying on protected backup exports or verified requests in the new build. Verify that code 92 is unused in Play Console before separately uploading the signed AAB. Real fleet-wide driving capacity, supplier consumption and physical-device Play purchase/backup behavior require separate verification.

---

# Road Conquest 1.0 Beta 65

## What's new since Beta 64

- **Protected progress backups:** New portable exports are signed against the exact ZIP contents after official Google Play app/device verification. Tampered, repacked and unsigned imported archives are rejected before restoring data. Private same-installation recovery copies use a separate local key.
- **Import and weekly-goal accuracy:** Restored GPS, roads and places remain excluded from new weekly challenge credit, including when later matched or discovered. New live driving retains eligible progress when matched alongside restored history.
- **Missing turns and place boundaries:** Recover valid zero-length Geoapify junction waypoints without inflating driving credit, and correct longitude-unwrapping so polygons cannot incorrectly contain points on the other side of the world.
- **Forum Plus and account reliability:** Shorter receipt transactions keep fresh Google verification, rollback and account isolation. Malformed account/provider replies are rejected; GIF provider failures remain retryable.
- **Performance and correctness:** Fewer repeated map recovery reads, GPS queries, temporary matching and fog calculations; fix first-discovery road counting, registration-cache notification choices and native device-test focus timing.
- **Dependencies:** Firebase BoM 35.1.0 and OkHttp/MockWebServer 5.5.1.
- **Compatibility:** Android 12+, versionCode **91**, versionName **1.0-beta.65**. The application ID and release signing identity are unchanged. Google Play reserves uploaded version codes, so confirm 91 has not been used.

**Required before distributing this build:** Deploy the tested account-function backend and configure a dedicated P-256 `BACKUP_SIGNING_PRIVATE_KEY` together with production Play Integrity credentials. Preserve previously trusted backup public keys on any future signing-key rotation. The Android application now rejects unsigned imports and portable backup export deliberately fails closed until the signer is ready. **Do not treat passing Android/GitHub checks as confirmation that the live Neon/Cloudflare backend has been deployed.** Verify `GET /health/live`, `GET /v1/backups` and an actual Play-installed export/restore before general distribution. Recheck Play Console's unused code 91 and separately upload the signed AAB; a GitHub Release does not publish to Play automatically. Real driving, physical-device backup transfer, and actual Play purchase/refund behavior remain unverified.

---

# Road Conquest 1.0 Beta 64

## What's new since Beta 63

- **Replay map recovery:** Trip replay uses the main map's provider health and failover. It retries after provider cooldowns and defers style/provider changes while hidden, then applies the current selection when resumed.
- **Redrawn map-style icons:** Streets, Minimal and Night have new artwork using the current appearance's colors.
- **Forum account isolation:** Drafts, feed results, checkout and restoration belong to the session that requested them. Old account responses cannot unlock a new account's pending action. Switching accounts clears the previous account's unsent composer.
- **Forum Plus handling:** Overlapping verification callbacks for the same session/receipt share one request, with fresh retries after completion. Missing products, eligible Buy options and store failures have clearer feedback. Backend verification can reuse the existing approved Play credential when no dedicated one is set. Pending payments never grant access.
- **Less receipt processing:** Repeat verified purchases update the stored encrypted receipt directly, avoiding replacement encryption and database transfer. Every request still checks Play and reconciles refunds/restores inside the existing transaction.
- **Cleaner community media:** Recycled GIF rows clear stale accessibility labels and hide media without a moderated digest.
- **Trip and configuration fixes:** Require stronger speed evidence before listing effectively zero-distance walking/scatter groups. Very short, slow drives may also be omitted from the recent list, while original fixes remain saved. Removed provider overrides restore defaults, malformed configuration is rejected and redundant provider checks/temporary collections are avoided.
- **Account feedback:** A username change that finishes after switching accounts reports which account was changed.
- **Compatibility:** Android 12+, versionCode **90**, versionName **1.0-beta.64**. Existing package, signing identity, progress, backup format and purchases are retained.

The GitHub workflow publishes the signed APK, Play upload AAB, tested account function, source archive, mapping and checksums only after all required checks pass. Upload to Google Play and backend/map-gateway deployment remain separate operations. Forum Plus production notification delivery was tested through Google Play, Pub/Sub and the authenticated Neon webhook. An actual device checkout/restore/refund remains untested. The included account-function artifact contains the receipt optimization. A separately deployed verified-driving service must permit version code 90 before accepting this build's verified submissions. Public competitive uploads remain disabled by default. No provider allowances, matching accuracy gates or schema are changed by this release.

---

# Road Conquest 1.0 Beta 63

## What's new since Beta 62

- **More reliable turn recording:** Keep accurate moving slow-turn fixes briefly after confirmed driving. Explicit walking/stopped speeds can no longer become driving solely because of positional GPS jitter. Existing mock, freshness and impossible-jump checks remain.
- **Historical gap repair gets a request opportunity:** Give fully anchored turns a real retry when the newest eligible work has already been submitted, including after a restart with stale walking noise. New driving retains priority. Repair cursors advance only after an actual network attempt, within the existing provider order, cooldowns and allowances.
- **Complete turn windows:** Retry anchored gaps of 2–23 pending fixes with their real approach/exit anchors inside the backup provider's 25-fix limit. Honor the requested island rather than jumping to nearby pending work; retain stoplight continuity checks and strict geometry validation. Provider coverage and finite allowances can still leave some fixes pending.
- **Cleaner trip history:** Hide tiny walking/scatter groups without credible driving-speed evidence from the trip list. Retain short genuine drives and legacy driving trips whose distance totals are unavailable. Short positive distances use feet/metres, and unavailable totals say "Distance not recorded". Original GPS fixes and backups are retained.
- **Walking marker:** Sustained reliable walking speeds display an upright pedestrian in the equipped car color. Confirmed faster movement restores the selected car, while brief slow turns and stops retain the driving mode. No extra location subscription, permission or server request is added.
- **Automatic regional units:** Use the phone locale's measurement system for kilometres/metres and square kilometres, while preserving miles/feet and square miles for U.S./U.K. road users. Apply the same cached formatter to map/Settings totals, trips/replays/sharing, road lengths, place areas, achievement progress, tutorial tile wording and backup restore confirmation. Stored distances, physical exploration tiles, achievement IDs, goals and rewards are unchanged.
- **Distinct map-style icons:** Streets, Satellite, Minimal and Night each have their own Settings icon and update immediately on selection. Streets also follows the app's light/dark appearance. The separate Appearance control keeps its sun/moon/automatic choices.
- **Less database and phone work:** Aggregate and transfer only the 20 displayed forum posts, using the 21st solely to detect another page. Preserve feed ordering, cursors, blocks, highlights, reactions and viewer state in one database statement. Avoid repeating an identical bounded anchored-gap scan when no prior repair cursor exists.
- **Older PR review:** Preserve the forum/tutorial improvements already present from newer code and the restored map artwork from PR #116. Include PR #118's real foreground Back-key dispatch for native tutorial/exit regression tests; keep all assertions and device coverage.
- **Compatibility:** Android 12+, versionCode **89**, versionName **1.0-beta.63**. Same package, permanent signing identity, local database/backup format, saved progress, account data and purchases.

The GitHub workflow publishes the signed APK, Play upload AAB, tested account function, source archive, mapping and checksums only after its required checks pass. Upload to Google Play and deployment of the account function/map gateway remain separate operations. The forum query optimization is included in the account-function artifact and takes effect on a separately deployed backend. A separately deployed verified-driving service must permit version code 89 before accepting this build's verified submissions; updating the documented allowlist does not change live settings. Private-export analysis and synthetic regression fixtures do not establish parity with live provider replies.

---

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

The GitHub release workflow publishes signed APK and Play upload AAB files, the tested account function, source archive, deobfuscation mapping and checksums after its required checks succeed. Google Play upload and deployment of the account function/map gateway are separate steps. Existing Forum Plus purchase setup remains unchanged. Separately deployed verified-driving services must allow version code 88 before accepting verified submissions. Editing the documentation does not change live backend configuration.

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
