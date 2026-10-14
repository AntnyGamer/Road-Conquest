# Road Conquest 1.0 Beta 45

- **Progress restore:** Validates exported ZIPs before replacing history, restores the points ledger and purchases, and saves a private recovery backup first. Imported driving never earns verified credit.
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

**Version code 71, Android 12+.** Install the signed Beta 45 APK over the existing Beta 44 (or earlier) app to retain locally saved roads, points and history. Do not uninstall or clear app data. VIP requires the non-consumable Play product `vip_lifetime` to be activated in Play Console; a GitHub/sideloaded beta cannot simulate payment.

**Production note:** The public OSRM server is a rate-limited community demo without an uptime guarantee; growing public usage will require a production-authorized matcher. VIP also requires a Google Play Console product configuration and, before significant commercial distribution, server-side purchase-token verification is recommended. See [PLAY_VIP_SETUP.md](PLAY_VIP_SETUP.md).
