# Android physical validation checklist

This checklist is for final device-level validation on supported Android 12+ phones and tablets that cannot be fully reproduced in the build environment.

## Install and permissions

1. With JDK 21+, Android SDK 37.0 and Build Tools 36.0.0 installed, open the project in Android Studio and complete a Gradle sync/build. The repository includes the full Gradle wrapper.
2. Install the debug build on the Android device.
3. Grant **Precise** location.
4. For automatic restart/background-created tracking, set Location to **Allow all the time** in Android app settings.
5. Allow notifications so the foreground tracking notification is visible.

## UI and map

1. Launch Road Conquest in portrait and landscape.
2. Confirm the Settings gear is clear of the status bar/camera cutout.
3. Confirm the bottom panel is clear of gesture navigation.
4. Confirm MapLibre attribution/logo remain visible above the bottom panel.
5. Confirm the labeled OpenFreeMap base map loads.
6. Confirm the map centers on the first fresh phone-location fix and the car icon follows current position.
7. Zoom in fully and confirm fog, blue unlocked-road lines, and the car marker remain visible at Road Conquest's maximum zoom on the device.
8. Zoom progressively outward and confirm the fog keeps visible cloud texture without collapsing into many tiny repeated tiles or producing a bright halo around traveled roads.
9. Confirm blue traveled-road lines remain visible from zoom 6, but cleared fog corridors disappear below zoom 9. At world zoom and the next three zoom levels, pan through several full world copies in both directions and confirm fog stays over the whole map while crossing the date line and looping.
10. Confirm the points chip is visible below the map stats and opens Garage & Shop.
11. Buy/equip at least one car shape/color and one road color; return to the map and verify each changes without restarting tracking.
12. If enough test points are available, equip Golden Road Conquest and verify the app palette and launcher icon change, then switch back to Standard UI.

## Driving and fog

1. Drive a short route with several turns, including one slow section. At a normal intersection, confirm the blue approach joins the actual exit without unlocking the opposite side. Allow matching to catch up after an uncertain GPS fix; a pending section may appear later when the turn is clear.
2. Lock the screen for at least several minutes while driving and verify the foreground notification remains active.
3. Reopen Road Conquest and confirm driven roads are blue and map-matched to the roadway rather than the raw GPS trace.
4. Confirm fog is clear near unlocked roads and visited/live places, begins fading around 50 ft, and reaches 80% around 1,500 ft. Repeated roads and overlapping visited areas must not push the start farther out.
5. Re-drive part of the same route; export data and verify `first_unlocked_utc` stays the original time while `last_driven_utc` advances.
6. Walk briefly with Road Conquest tracking and confirm nearby places stay uncovered after walking away and reopening the app. Walking should not add driving miles or blue matched roads. Repeat with fog hidden, then turn fog back on. (Fast cycling can still resemble slow driving to the current speed heuristic.)

## Tracking modes and restart behavior

1. In **Always** mode, leave Road Conquest and confirm tracking continues.
2. Reboot the phone with background location granted and verify Road Conquest resumes according to the device's Android background-execution policy.
3. In **Always** mode, turn Android Location off, fresh-reset device data, then turn Location back on. An already-running tracking service may remain ready while Location is off. On Android 14+, if the service must be newly created while Location is still off, confirm Road Conquest does not attempt that invalid start. With the app open when Location returns, confirm the first new live fix becomes the zero-point starting location; an older cached fix must not become the baseline.
4. Switch to **Manual** mode, disable tracking, leave/reopen the app, and confirm it stays off until **Enable** is pressed.
5. Switch Manual -> Always while Android Location is off, then enable Location. On Android 14+, return to Road Conquest/Settings if needed; confirm tracking starts at the first foreground-safe opportunity without requiring a process restart.
6. Reboot on Android 14+ with system Location off, then turn Location on. Confirm opening Road Conquest starts automatic tracking normally; the platform does not permit a location foreground service to start at boot while Location itself is disabled.
7. Force-stop Road Conquest and confirm it does **not** restart itself until manually launched; this is expected Android behavior.

## Progression and achievements

1. Unlock a previously unseen road and verify the point balance increases by 5 exactly once.
2. Cross into a locality/state/country not already recorded, keep Road Conquest foregrounded briefly,
   and verify the relevant discovery count/points appear without repeated rewards on a revisit.
3. Verify town/state/country achievement progress reflects the discovery counts.
4. At 5% battery while Road Conquest is open or tracking, verify **Running on Fumes** unlocks once.
   At 1%, verify **Last Percent** unlocks once. Ordinary battery levels must not affect progression.
5. Confirm achievement category buttons show **Roads**, **Distance**, **Places**, **Ads**, and **Extra**. Battery achievements belong to Extra; ad milestones belong only to Ads.
6. In Garage & Shop, complete an AdMob rewarded ad and confirm the earned points are saved once.
   Verify the Ads achievement count advances toward the 5/10/25/50/100 milestones. Cancel or
   fail to load an ad and confirm neither points nor achievement progress increases.

## Export

1. Use **Export driving data** in Settings.
2. Open the ZIP and verify `metadata.json`, `track_points.csv`, `roads.csv`,
   `road_visits.csv`, `explored_places.csv`, `visited_places.csv`, `place_candidates.csv`,
   `progression_rewards.csv`, `progression_purchases.csv`, and
   `progression_counters.csv` are present. Metadata schema version is 8.
3. Verify metadata point/discovery counts agree with their CSVs and equipped car/road/gold settings
   match the app.
4. Verify road rows contain segment ID, road name, first-unlocked time, last-driven time, and the
   `coordinates_json` geometry array.

If all of the above pass on the target Android device and firmware, that provides the device-level validation that static analysis cannot substitute for.

## Account maintenance

- Change a username using the current password; verify the new name in Account settings and
  after signing in again, with scores and privacy preserved. A taken name or wrong password
  must leave the original account unchanged.
- Cancel both deletion confirmations and verify that all data remains.
- **Account → Delete account** must require the current password, delete the cloud account/sessions/leaderboard data, and leave saved device history intact.
- **Settings → Data and privacy → Delete all data** must remain available while signed out and require typing `I confirm I want to delete all of my local data.` exactly, including the period. A partial/mismatched phrase must not delete anything. After exact confirmation, local deletion must begin without any account or network dependency; the cloud account and leaderboard scores stay. Then tracking stops, verified-drive sharing turns off, history and fog reveals clear, points/purchases/cosmetics clear, and delayed road matches cannot restore them. The
  cloud account remains when only device data is deleted. Tracking and verified sharing can be
  explicitly re-enabled later.
- Interrupt an export (for example by killing the app during export), reopen Road Conquest, then
  delete device data. Confirm no private `roadconquest-export-*` snapshot remains in app cache;
  user-saved ZIP exports remain the user's responsibility.

### Additional map and display preferences

- Under Map & appearance, confirm the town-medal switch hides and restores markers without changing earned medals or town-completion percentages.
- Enable car following, drive with good GPS and confirm the camera follows live fixes. Drag or zoom the map to pause following; tap the center button to resume. With following off, live location must not recenter a manually moved map.
- Change distance units from Automatic to Miles and Kilometers; confirm driving, trip and achievement labels update while actual stored meters and milestones stay unchanged, including after restarting the app.
- Under Tracking, enable Keep screen awake while the map is open; verify Android screen timeout is prevented on the foreground map and returns to normal in Settings, other activities, and after minimizing the app.
