# Progress restore and weekly challenges

Settings → **Import and restore progress** accepts current Road Conquest export ZIPs
(formats 9 and 10). Format 10 makes spreadsheet-safe text escaping reversible, including
literal apostrophes; legacy format 9 could not distinguish an apostrophe before a formula
from its spreadsheet-protection prefix.

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

Settings → **Weekly challenges and friends** shows local goals of 25 new roads and three
new rewarded towns. Completion awards 250 and 300 points once per UTC week respectively.
Old-road sections, excluded road groups and the zero-point starting town do not count.
The reward ledger survives export/restore. Weeks reset Monday at 00:00 UTC worldwide.
The previous week has a one-week grace period for late road matching or town resolution.
Local challenges run during ordinary progress refreshes, with no extra GPS fixes or timer.

Friends can request, accept, decline/cancel and remove relationships by username. Weekly
friend rankings compare verified miles or new verified road sections. They require the
updated account function and the existing verified-scoring infrastructure; local history
and backups are never used as verified evidence. Hidden profiles stay out of rankings.
Local goals award points independently of verified friend standings; no winner prize is
advertised or awarded by friend rankings.

Trip replay respects raw GPS gaps even when the displayed route is downsampled. A trip
selected before data deletion/restore cannot replay unrelated replacement history.
