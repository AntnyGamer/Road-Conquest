# Road Conquest 1.0 Beta 49

- **Simpler map UI:** Group Driving miles/roads and Exploration tiles in a compact summary; Settings now has categorized Driving, Exploration, Progression, Tracking and Backup details. The three bottom destinations are Trips, Badges and Goals; the already-clickable points chip is clearly labeled Garage, removing its redundant bottom-bar button.

- **New feature work on the unmerged development branch:** Free trip summaries now report estimated road unlocks, discovered towns, dated map-tile unlocks, and GPS matching still pending. Saved mile tiles carry first-unlock dates via a backward-compatible schema-14 migration and ZIP format 11. Unknown legacy dates stay unknown; backup formats 9/10 remain importable. The map overlay picker adds a local 5×5 exploration goal view, while Settings records the time of the last successful progress export.
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
