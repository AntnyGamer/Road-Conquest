# Road Conquest Release 1.0

Road Conquest remembers the roads you drive and the places you visit, revealing them through a
cloud-textured fog map.

[Download the Android app](https://github.com/AntnyGamer/Road-Conquest/releases) ·
[Source and issues](https://github.com/AntnyGamer/Road-Conquest) ·
[GNU AGPL v3 license](LICENSE)

**Release 1.0** improves historic town discovery from saved GPS, map controls, town-badge balance, and local performance. See the [release notes](RELEASE_NOTES.md) and [town completion details](TOWN_COMPLETION.md).

Earlier beta history is available in [GitHub Releases](https://github.com/AntnyGamer/Road-Conquest/releases).

## Install

Android 12 or newer is required. Download the signed APK from Releases for direct installation.
The GitHub APK uses the permanent upload key and updates compatible GitHub-installed betas.
For an app installed through Google Play, update through Play instead: Play uses its separate
app-signing key. Do not uninstall or clear app data to work around a signature error.
Do not change installation channels without a restorable backup. The current .rcbackup format works across GitHub and Play installations: sign in to the same Road Conquest account to create or restore it. A network connection is required; no separate backup password is needed. Do not uninstall or reset your phone until the backup has been created successfully.
For Google Play, download the **signed** `RoadConquest-<version>.aab` from the [GitHub Releases](https://github.com/AntnyGamer/Road-Conquest/releases) page, not the unsigned intermediate from GitHub Actions. The Actions artifact is named `android-build-inputs-unsigned` and contains `app-release-UNSIGNED-DO-NOT-UPLOAD.aab`, which Google Play cannot accept. Each release also includes the Android Studio project and SHA-256 checksums.

If Play still reports an invalid signature for the **signed release** bundle, compare the SHA-256 fingerprint under Play Console → Test and release → App integrity → **Upload key certificate** (not the separate Google Play **App signing key certificate**) with the release upload key:

`CD:3C:4B:18:0B:47:40:A0:BF:F7:7E:37:DE:10:9B:E6:CA:B2:64:CD:1B:D6:6E:0C:ED:05:44:12:52:AD:9A:E1`

If these fingerprints differ, use the previously registered upload key or request an upload-key reset in Play Console. Do not generate a new keystore for an existing app or put signing keys in Git.

Grant Precise location. For automatic background tracking, choose Allow all the time in Android's
location settings and allow background battery use. Settings includes shortcuts to the relevant
Android screens. Allow notifications to see the tracking notification and its Stop tracking control.

Release 1.0 uses Android application ID `com.roadconquest.app`, internal version code **97**, and GitHub tag `release-v1.0`. Earlier development builds used other tags; these remain in GitHub history. Google Play permanently reserves uploaded version codes, even in testing tracks, so confirm 97 is unused before uploading. The app shows its exact version in Settings → Help & about.

## Explore

- Town overlays now show exploration percentages and bronze (25%), silver (50%), or gold (100%) badges. Completion uses the part of unlocked map tiles inside each town's original boundary. Earn 30 additional achievements, including a new Town Badges category. [Town completion details](TOWN_COMPLETION.md).
- Settings opens to seven searchable categories, including a dedicated Backups & data section. Search by a category or control name; Back returns to the category list before leaving Settings.
- The first-use Quick tour previews a discovery without location access, then highlights the real
  tracking, Layers, Trips/Badges/Goals, Garage and Settings controls. Skip it or replay it from
  Settings → Help & about → Quick tour. The optional Back-to-exit prompt explains whether tracking remains on;
  Settings can turn this prompt off or back on. Leaving the map does not stop the tracking service.
- Recorded driving appears while matching is pending; confirmed road geometry then refines it.
- A tightly bounded, GPS-supported connector can close a short split between differently named
  roads; it does not grant extra unlocked roads. Certain short pending turns can be retried once
  without bearing hints, while retaining confidence, snapped-distance and detour validation.
- Confirmed road segments recorded seconds apart can display a short, uniquely supported
  intersection join instead of leaving a visual hole. This display-only join never adds
  driving miles, road unlocks, or verified leaderboard credit.
- Where a confirmed road ends in a tiny map-matching micro-segment, junction drawing
  uses the nearest 4 m of that same centerline (within 25 m), avoiding an artificial
  visual gap without relaxing the intersection-safety checks.
- Drive to save matched road lines. Entering a roughly 1-mile × 1-mile world-grid tile
  permanently clears fog across that entire tile, with a gradual 1,500-foot fade beyond
  its edges. Good location fixes can unlock tiles while walking or stopped too, without
  adding driving miles. Existing exploration history remains visible in the new grid.
- Earn 2 points per newly unlocked road, 35 per new town, 150 per state/region,
  and 500 per country, plus smaller achievement and weekly bonuses. Starting places
  count toward exploration totals but earn no points or achievements. Previous
  point awards and purchased cosmetics remain intact. The compact map stats distinguish **Driving** miles/roads from **Exploration**
  tiles. Tap the labeled **Garage · ⚔ points** chip to open the shop. The bottom bar keeps
  three primary destinations—**Trips**, **Badges** and **Goals**—without a duplicate Garage
  button. Settings organizes detailed totals into Driving, Exploration, Progression,
  Tracking, and Data & Backup sections.
- Open Garage & Shop to buy and equip car shapes, car colors, road colors, or the Golden
  Road Conquest UI and launcher icon. Purchases are one-time and equipped cosmetics can be changed later.
- Choose Streets, Minimal, Night or Satellite maps, and a Light, Dark or phone-controlled appearance.
  Settings also offers optional town medals, car-follow mode (pauses on map gestures),
  Automatic/Miles/Kilometers distance labels, and keeping the screen awake only while the map is visible.
  All four choices are local display controls; map tracking and scoring stay unchanged.
- **VIP (one-time $2.99 US purchase through Google Play, configured separately in Play Console):** remove banners/rewarded-ad offers, equip Gold car/road/UI cosmetics for free, and unlock trip extras: access up to 500 recent drives instead of the free 100, share text summaries, and save GPX routes with optional start/end redaction. Existing point purchases remain owned. Basic drive summaries, 100-trip history, animated replay and full progress backups/restores **remain free**. Play restores VIP after reinstall. VIP requires an active `vip_lifetime` product (see [VIP setup](PLAY_VIP_SETUP.md)).
- Use the map overlay button to highlight countries, states/regions, or towns, or choose **Explore nearby** for an on-device 5×5-tile progress check at the visible map center. Walking can reveal tiles; only driving earns road credit. Tap a highlighted area for population, area and visit dates when available.
- Tap an unlocked road for its name, saved length, first-unlocked time, last-driven time and times driven.
- Browse achievements by **Road Conquest**, **Mileage**, **Exploration**, and **Ad Rewards**. Retired low-battery Bonus challenges and their empty category are no longer shown.
  Optional rewarded ads in the Garage grant the displayed points only after confirmed completion, and count toward ad milestones.
- Use automatic tracking or control it manually. Stop tracking from the foreground notification.
- **Export progress** offers exactly two choices on Google Play and GitHub installs:
  **Secure backup (.rcbackup)** encrypts progress and authenticates changes. It can be
  restored on another installation after signing into the same Road Conquest account
  with internet access; no separate backup passphrase is required. The account
  service supplies a per-account key over HTTPS but never receives the backup file
  or GPS history. **Readable ZIP (export-only)** contains CSV history, road
  geometries, discovered places and progression data so it can be opened in
  spreadsheet tools or uploaded to ChatGPT for driving analysis. It cannot be
  restored into Road Conquest. It is **not encrypted** and contains sensitive
  location history: inspect and share it deliberately. Deleting your account
  or changing the service's key-derivation secret makes secure backups unreadable.
  Restored data does not grant new verified scores. Settings shows the last export.
- Create an optional account, change your username and manage leaderboard visibility. There are exactly two destructive account/data actions in the app: Account → Delete account removes the cloud account while keeping saved device history and requires the current account password; Settings → Backups & data → Delete local data removes all local Road Conquest data while keeping the cloud account and leaderboard scores and requires typing the exact local confirmation phrase. Local full-data deletion works while signed out, stops tracking, disables verified-drive sharing and removes private interrupted-export snapshots.

Map drawing and viewport refresh timers stop while the map activity is hidden. Returning reloads
roads changed during background tracking; GPS sampling and saved driving history are unaffected.

Turning Show fog off changes only its visibility. Tracking continues and the saved reveals return
when fog is enabled again. Manual tracking stays off until you enable it. On Android 12-13,
automatic mode can keep its foreground service ready while Android Location is off. Android 14+
requires system Location to be enabled before a location foreground service can start; while the
app is open, Road Conquest starts tracking as soon as Location is enabled. If a reboot happens with
Location off on Android 14+, enable Location and open Road Conquest to restart tracking. After a
fresh install or device-data reset, the first good live fix defines the zero-point starting
town/state/country and awards no discovery points. Cached fixes from before the new location request
cannot become that baseline. Automatic tracking still depends on location permissions and Android's
background-execution rules; a force-stopped app must be opened manually before it can resume.

Mileage estimates distance between accepted driving fixes, including repeat drives. GPS speed is
a heuristic and cannot prove you are in a car. Long gaps, reversed timestamps and implausible jumps
are excluded. Local road totals use a human-road identity layer: signed route refs from the existing
matched route references take priority, connected same-name fragments are joined, nearby divided
carriageways with the same route ref are treated as one road, and anonymous on/off ramps do not
inflate the count. Distant same-name or same-ref roads remain separate unless driven geometry
establishes a local connection. The counter is intentionally human-oriented rather than a raw
OSM-way or matcher-fragment count.

## Accounts and leaderboards

This release keeps public and friend rankings, visibility controls and live verified-drive
sharing hidden through `FeatureFlags.LEADERBOARDS_VISIBLE = false`. No verification evidence is
collected or uploaded while hidden. Local weekly goals and friend management remain available.
The retained competition implementation requires production verification infrastructure
before it can be enabled.

The app defaults to the existing live HTTPS account service. Usernames are unique without regard
to capitalization. Passwords are salted and scrypt-hashed on the server; Android stores an encrypted
session token. Local exploration does not require an account.

Account availability and competitive scoring are separate. Verified leaderboard credit requires
production Play Integrity credentials and a checkpoint encryption key. Rankings count the app's existing exploration cells, without a competitive road matcher/catalog. Until those
are configured, the leaderboard reports verification as unavailable and does not rank local totals.
Global rankings count unique verified cells; weekly friend rankings count newly verified cells.

Leaderboard visibility and live GPS sharing are separate settings. New accounts can be visible on
leaderboards, but verified-drive GPS sharing remains off until the user explicitly enables it in Settings.
Deleting an account revokes its sessions and removes its cloud competitive data while leaving
saved device history in place. Deleting device data also disables verified-drive
GPS sharing before tracking shuts down, so queued evidence is not submitted afterward. Other devices
and user-saved exported files are separate.

Moving GPS gaps longer than 30 seconds start a new matching interval; nearby stationary pauses can
still reconnect without inventing a route across a location outage.
A lightweight foreground-service health check can retry a stalled Android GPS subscription after two minutes without usable updates; retries are rate limited to once per five minutes. This only works while the service remains alive. Android battery restrictions, disabled Location, or revoked background access can still interrupt history. Data exports now record the app version to aid comparisons.

Older unmatched GPS fixes also receive spare matching batches even when they have no retry
backoff, avoiding queue starvation while keeping the first batch focused on live driving.
Short unmatched turn gaps retry with adjacent approach and exit fixes, using spare space within
the same ten-fix request limit. Confidence, accuracy and detour checks still govern road credit;
an ambiguous final endpoint can remain pending until later driving provides more evidence.

The fog keeps a cached world layer ready during fast gestures and refreshes detailed reveals
while the camera moves. A detailed reveal stays visible during a gesture while its georeferenced
bitmap still covers the viewport, avoiding the old flash back to fully covered fog. The widest zoom
accounts for the screen diagonal and camera latitude so rotation keeps the map inside its world edges.

Place discovery is intentionally sparse: background tracking records only an occasional local
candidate, while town/state/country names are resolved later on a foreground worker through
Android's system geocoder. Point refreshes use aggregate database queries, Shop/Settings avoid
duplicate launch refreshes, and map cosmetics are only regenerated when their selection changes.
These choices keep the progression system from adding continuous background network or rendering work.

## Build and verify

Open the project in Android Studio with JDK 21 or newer, Android SDK 37.0 and Build Tools 36.0.0.
The wrapper pins Gradle 9.8.1 with a verified checksum and Android Gradle Plugin 9.4.1.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:bundleRelease
./gradlew :app:connectedDebugAndroidTest
```

On Windows, use `gradlew.bat` with the same tasks. The debug APK is
`app/build/outputs/apk/debug/app-debug.apk`. Local release builds are unsigned until signed with
the permanent release key. Signing keys, local SDK paths, databases and build outputs are excluded
from Git.

GitHub Actions runs account/database tests, Android unit tests, debug and release lint, and native
map integration tests on Android 12, 15 and 17. The release job publishes the verified signed APK and Play-ready signed AAB only after
all required jobs pass and only when that version tag does not already exist; ordinary commits at an
already-published version are verified without retargeting or overwriting the release. It also attaches the exact account-function bundle produced by the tested
backend job, so the deployable server artifact for that release is preserved alongside the Android release artifacts. It verifies the permanent signing-certificate digest and includes the source
ZIP and checksums for the APK, AAB, source archive and server bundle. Normal release
publication runs from `main`; feature PR builds verify without publishing a new beta.
Configure these repository secrets from the private signing backup:

- `ROADCONQUEST_RELEASE_KEYSTORE_BASE64`
- `ROADCONQUEST_RELEASE_KEY_ALIAS`
- `ROADCONQUEST_RELEASE_STORE_PASSWORD`
- `ROADCONQUEST_RELEASE_KEY_PASSWORD`

Keep the signing backup outside the repository and release assets. Each released build needs an
unused increasing `versionCode` and a distinct GitHub tag; two-part and three-part numeric Android version names are supported.

Override the account endpoint with the Gradle property
`ROADCONQUEST_ACCOUNT_API_URL=https://accounts.example.com`. Local matching recognizes repeat visits to previously verified roads on-device and sends other matching
traces to the Geoapify gateway, configured with `ROADCONQUEST_MAP_GATEWAY_URL`.
See [service order, fallback behavior and configuration](SERVICE_PROVIDERS.md).
Geoapify's shared free allowance is capped by the gateway, and unmatched GPS remains saved
when the provider is unavailable.

Administrative overlay boundaries are requested only after the user enables an overlay; results are cached and requests are throttled. The APK reads the active provider from `OVERLAY_PROVIDER.txt` on this repository’s `main` branch so the service can be switched or disabled without an app update. Custom builds can point at another remote config with `ROADCONQUEST_PLACE_OVERLAY_CONFIG_URL=https://example.com/overlay-provider.txt`.
See [server setup](server/README.md) and the [physical-device checklist](ANDROID_TEST_CHECKLIST.md).

## Google Play submission

The release bundle targets API 37, uses application ID `com.roadconquest.app`, and is signed for Play upload. GitHub embeds all four full MapLibre native debug-symbol libraries into the AAB, along with the R8 mapping, and validates it with Google's `bundletool` both before and after signing. No extra Play Console uploads are needed. Only the signed release AAB is submitted. The standalone symbol ZIP is omitted because its full contents are already stored in the AAB.
CI verifies 16 KB native-library packaging/alignment before publication, in addition to unit tests, lint,
release builds, account-server tests, and native map tests on supported Android generations.
The account service exposes browser pages at `/privacy` and `/delete-account`. The app links the privacy policy from
Settings; keep the deletion page publicly reachable for the Play Console account-deletion field without adding a third in-app delete action. The first Android location permission flow is preceded by a prominent disclosure
covering precise location, background use, road matching, and optional verified scoring.

Play Console still requires the publisher to complete the Data safety form, background-location declaration,
developer/package registration, store listing assets, review access where applicable, and the other account-level
declarations that cannot be supplied by source code alone.

## Beta 20 embedded native debug symbols and Play validation

Beta 19's signed AAB was rejected by Play Console even though its JAR
signature verified and the upload key matched the registered certificate.
The exact cause is not confirmed. Beta 20 validates the AAB with Google's
`bundletool` both after packaging and after signing; it also verifies the
actual signature and upload-key fingerprint.

All four **full, unstripped** MapLibre native debug-symbol libraries remain
**embedded directly in the AAB** alongside the automatically embedded R8
mapping. GitHub verifies their build IDs match the packaged libraries and
checks that each embedded symbol file is byte-for-byte identical to its
original. Metadata uses lossless maximum ZIP compression. Nothing is
stripped and **only one AAB upload** is needed for Google Play.

The large native debug data increases the GitHub AAB download size, but
is not installed as application code on users' devices. Beta 20 omits
the redundant standalone native-symbol ZIP from release assets to avoid
storing the same full symbols twice. The full symbols can be extracted
from the AAB if needed. This does not change the app's runtime behavior.

## Beta 19 startup hotfix

Beta 18's minified release crashed before the main screen: R8 removed the public no-argument
constructor used by Room to create AdMob's WorkManager database. Beta 19 preserves that constructor.
Publication now also requires two cold launches of the minified release APK on Android 12 and 17,
using the exact unsigned APK produced by the release build before permanent signing. Install the
signed Beta 19 APK as an update to retain local driving history; do not uninstall or clear storage.

## Ads

AdMob anchored adaptive banners appear in separate footers on Garage, Achievements, Leaderboards,
and Settings. The driving map and account forms remain clear. No automatic full-screen ads are used.
Banners pause while their screen is hidden and are destroyed with it. Rewarded ads load only after
pressing **Load rewarded ad** in the Garage; **Watch ad** shows the exact point reward configured in
AdMob before playback. The SDK-confirmed reward is persisted once, together with the completed-view
counter; callbacks from a deleted history session are rejected. There is no automatic rewarded-ad
preloading or retry loop. These local points do not add verified leaderboard credit.

Release builds use the publisher's AdMob App ID and banner/rewarded units in `app/build.gradle.kts`.
Debug builds use Google's demo App ID and ad units; use debug builds for testing to avoid invalid
traffic on live ads. Google UMP updates consent on entry to an ad-supported screen and gates SDK
initialization/loading through `canRequestAds()`. Applicable **Ad privacy choices** stay reachable
in the footer even if no banner fills. Ads require a network connection and eligible inventory;
ad failures never block the rest of the screen.

Before distributing live ads, publish the appropriate messages in AdMob **Privacy & messaging**,
complete AdMob app/store verification and readiness review, and declare ads and the SDK's data
collection in Play Console. Deploy the updated account-service bundle so `/privacy` includes the
advertising disclosure, and allow version code 53 for verified scoring. GitHub release publication
builds and attaches that bundle; it does not deploy the account service or change AdMob/Play settings.
Ad requests have their own network, memory, storage, and battery costs; this release retains the
Beta 17 map/tracking optimizations but does not claim ads have zero overhead.

## Privacy and map credits

Driving history, explored places, points and purchases stay on the phone. Already verified
road segments can be recognized locally on a repeat drive; new road matching sends bounded
coordinate batches through the Road Conquest gateway to Geoapify. Town/state/country labels are requested through
Android's system geocoder only while Road Conquest is foregrounded; the geocoder implementation and
network behavior are supplied by the device. Pending data remains saved for retry after network or
matching failures. User-selected ZIP exports contain precise history, road geometry and progression data.
Protected exports require internet and production Play app verification; only a SHA-256 fingerprint
and attestation are sent for signing. Restore rejects changed or unsigned files. Verification keys
are fetched over HTTPS once per installation/key and saved for later offline checks. The private
pre-restore recovery copy works offline on the same installation. Imported history, including delayed
matching and town discovery, cannot earn new weekly challenge credit or change verified rankings.
Restored local history and previously earned local rewards remain visible. See the account server
README for signing-key setup required before releasing this change.

When enabled and available, verified scoring sends precise live GPS batches with Play Integrity
evidence for independent server matching. The server retains scores, short-lived receipt hashes,
and a timestamp plus one-way fingerprint for the last accepted boundary fix; it does not retain
complete route history. Saved local totals and old history are never accepted as competitive scores.

Street maps use OpenFreeMap, OpenMapTiles and OpenStreetMap contributors. Satellite imagery uses
Esri World Imagery and its credited providers. Map providers receive requests for the area you view.
Styles are bundled; tiles, sprites, fonts and imagery require network access or an existing cache.
Satellite detail varies by area.
Geoapify street tiles and geoBoundaries country/region datasets provide the documented backups.
Crashlytics is optional, initializes only after consent in Settings, and does not include Analytics.

Settings → Help & about → Licenses and map credits contains the app license and provider notices. Third-party
styles, map data, imagery and dependencies retain their own licenses and service terms. See
[NOTICE](NOTICE) and the credits under `app/src/main/assets/`.

## License

The proposed community feed, automated moderation and optional Forum Plus setup are documented in
[COMMUNITY_FORUM.md](COMMUNITY_FORUM.md). The forum defaults off until its server configuration is ready.

Road Conquest's original source is licensed under GNU Affero General Public License v3 only
(`AGPL-3.0-only`). The complete license is in [LICENSE](LICENSE) and bundled with the app.
