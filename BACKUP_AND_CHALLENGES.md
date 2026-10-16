# Progress restore and weekly challenges

Settings → **Import and restore progress** accepts current Road Conquest export ZIPs
(formats 9, 10, and 11). Format 10 makes spreadsheet-safe text escaping reversible, including
literal apostrophes; legacy format 9 could not distinguish an apostrophe before a formula
from its spreadsheet-protection prefix. Format 11 preserves the first-unlock timestamps for new mile tiles. Older imports retain explored tiles but have no reliable historical tile-unlock dates.

The importer validates archive entries, CRCs, exact CSV columns, UTF-8, coordinates,
geometry, timestamps, unique keys, road references, row counts, mileage and the points
ledger before showing a replacement confirmation. It parses quoted names and multiline
fields, uses bounded streaming into a private staging database, rejects duplicate or
unexpected/path-traversal entries, and caps compressed and expanded data at 512 MiB.
Larger exports remain exportable but cannot yet be restored in-app.

Before replacement, one private recovery ZIP is saved on the phone. **Restore progress
from before last import** can recover it (the current state is backed up in turn).
All ten history/progression tables change in one SQLite transaction; failed writes roll
back, and pre-restore asynchronous writers cannot repopulate the new history. Restoring
stops tracking and disables verified-drive sharing. It does not import account credentials,
VIP ownership, permissions or upload old GPS for leaderboard credit. Google Play restores
VIP separately. Delete all data removes the private recovery backup too.

**Goals → Weekly challenges and friends** shows four stacking local goals in this branch:
five newly unlocked roads (75 points), 25 newly unlocked roads (250 points),
one newly rewarded town (75 points), and three newly rewarded towns (300 points).
A player can earn up to 700 weekly-goal points. The original 25-road and three-town
goal IDs and payouts remain unchanged; only the two starter milestones are new.
Old-road sections, excluded road groups and the zero-point starting town do not count.
The reward ledger survives export/restore. Weeks reset Monday at 00:00 UTC worldwide.
The previous week has a one-week grace period for late road matching or town resolution.
Local challenges run during ordinary progress refreshes, with no extra GPS fixes or timer.

Friends can request, accept, decline/cancel and remove relationships by username. Weekly
friend rankings compare newly verified exploration cells using the app's existing grid. They require the
updated account function and Google Play verification, with no competitive road matcher/catalog; local history
and backups are never used as verified evidence. Hidden profiles stay out of rankings.
Local goals award points independently of verified friend standings; no winner prize is
advertised or awarded by friend rankings.

The current beta temporarily hides public and friend leaderboards, leaderboard privacy
controls and verified-drive sharing with `FeatureFlags.LEADERBOARDS_VISIBLE = false`.
No verification evidence is collected or uploaded while hidden. Local weekly goals and
friend management remain available; the ranking implementation and existing scores are
retained for a later enabled build.

Free trip history covers 100 recent drives with basic distance, duration, newly matched roads, newly rewarded towns, dated new map tiles and pending GPS matching counts. VIP extends browsing to 500 drives and enables sharing text summaries and user-selected GPX files. GPX offers a privacy-first option that omits the first and last two minutes, or a deliberate full-route export. Free full-progress ZIP export/restore remains available to everyone.

Trip replay respects raw GPS gaps even when the displayed route is downsampled. A trip
selected before data deletion/restore cannot replay unrelated replacement history.

Account competitive scores require the hardened server verification policy. See
[leaderboard security and activation requirements](LEADERBOARD_SECURITY.md).
