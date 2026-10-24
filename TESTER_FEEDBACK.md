# Closed-test feedback improvements

The supplied Testers Community reports recommend a dynamic tutorial, stronger Play Store copy,
feature-focused screenshots, and an optional exit prompt. The production questionnaire is suggested
wording, not evidence that the described recruitment, surveys, engagement, or changes occurred.

## Implemented in this change

- A six-step tour over the real map controls: an offline discovery preview, tracking, Layers and
  Explore nearby, Trips/Badges/Goals/Forum, Garage, and Settings/privacy/backups. Skip and Back are available;
  completion or Skip suppresses the automatic tour. Settings can replay it without resetting progress.
- Tour progress and the preview survive activity recreation. The tour appears before the existing
  account invitation and location disclosure; demo interaction never requests location or writes
  exploration/reward data. Existing recording is not stopped by the tour.
- Back at the introduction leaves setup untouched and preserves the tour for returning later.
  Restored cards use the controls' actual layout bounds immediately, keeping highlighted navigation
  clear on the first landscape frame.
- Rounded, themed cards, a short opt-in preview animation, highlighted controls, safe-area handling,
  scrollable text, and modal accessibility. The preview respects disabled system animations.
- An optional map-exit confirmation, enabled by default, with live tracking status and an explanation
  that leaving does not erase saved progress or stop recording. Stay/cancel preserves the preference;
  Don't ask again is saved only when Leave map is chosen. Settings can re-enable it.
- Ready-to-review English Play Store descriptions in `play-store/en-US/`. They describe existing
  features and distinguish free features from optional VIP. These files do not publish a listing.
- Optional Buy Me a Coffee support link in Settings → About; it grants no app benefits.
- Public community feed with reactions, reports, account blocking, a persistent 10-minute posting
  cooldown and automatic moderation. Forum Plus adds GIF links and subtle highlights through a
  separate one-time Play purchase. See `COMMUNITY_FORUM.md` for setup and launch requirements.

## Already present

Achievements, weekly goals, friends, trip replay, optional VIP summary/GPX sharing, crash-reporting
consent, backup/restore, and a privacy-policy link already exist. This change explains and surfaces
them instead of duplicating them. Competitive leaderboards remain controlled by the existing build flag.

## Deferred

- Play Store screenshots and promotional imagery, as requested.
- Public route feeds or automatic route sharing: the report does not establish demand for them,
  and location-sharing behavior should be an explicit product decision.
- Claims about install forecasts, tester recruitment methods, surveys, or measured engagement.
  Use only what actually happened when answering Play's production-access questions.

## Verification

- Initial local full unit suite: **1,243 tests passed**, with no failures, errors or skips.
- Follow-up review: **37 focused tests passed**, including new first-page Back and first-layout
  restoration regressions on Android 12 and 17. The layout matrix now uses a single layout pass.
- Local debug and release lint: **zero errors**. Existing warnings and non-blocking style suggestions remain.
- Local minified release APK: **build and ZIP integrity checks passed**, with Crashlytics upload
  tasks disabled for local validation. This unsigned verification APK is not an installable update
  to the signed beta and has not been published.
- Native Android view renders inspected for light/dark portrait and landscape layouts. Automated
  bounds checks also exercise 200% font scaling and system-bar insets on Android 12 and 17.
- Tutorial/exit coverage includes demo isolation, explicit Skip/completion, recreation state, Back,
  keyboard focus, accessibility restoration, themes, large text, landscape and exit choices.
- The device-test APK compiles, including a new real-Activity rotation/system-Back regression test.
  The PR checks run device execution and the existing map/startup matrix.

The Play copy must still be reviewed and entered in Play Console; store screenshots remain deferred.
The code is proposed for review and has not been merged, released or submitted through Play Console.
