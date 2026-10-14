# Verified exploration cells

Policy 3 ranks unique verified cells from the app's existing `FogGrid`. Weekly
friend rankings count first-ever newly verified cells in the current UTC week.
Local totals, imported backups and old road scores cannot enter either ranking.

The server calculates row/column IDs directly from GPS using `fog-grid-v1`, with
the same latitude step, 19054 longitude columns, date-line wrapping and map limits
as Android. Shared fixtures check cross-platform identity. The map is unchanged.
Cell widths vary with latitude; this counts grid visits, not uniform explored area.

Credit requires two consecutive accurate moving fixes safely inside one cell.
Stationary jitter, uncertain borders and interpolated cells do not count. Unique
keys prevent repeat credit. Count, weekly credit, encrypted checkpoint and retry
receipt commit together under account lock.

Fresh server challenges and Google-decoded Integrity hashes bind exact evidence.
Approved package, certificate, tested version (71+), recognition, licensing, and
both device/strong integrity remain mandatory. Account eligibility is checked
before verification and again under lock. Overlaps, altered retries, cross-account
runs, impossible motion and corrupt checkpoints fail closed. Starting another
run never clears the encrypted account/grid-bound continuity checkpoint.

Android requires policy 3 and the exact grid version, with no local-score fallback.
Sharing needs explicit new consent. Samples are batched about every 90 seconds;
stationary and session-confirmed cells are skipped using bounded memory. No extra
GPS job or saved-history backfill is added. Fewer submissions give less continuous
travel evidence; this is a cost/security tradeoff, not proof of physical location.

Public/friend responses expose only username, rank and count. Coarse visited-cell
IDs remain private until account deletion, alongside counts and the encrypted
latest GPS fix. Full routes are not saved. Phone deletion leaves cloud scores.

## Activation

No private road matcher, worldwide road catalog, H3 or town service is required.
Apply the additive cell schema/runtime grants, configure the stable checkpoint
key and server-only Google credentials/project/certificate/version allowlist,
and enable Play Integrity's strong label. Enable flags only on a test deployment
and internal Play build first. Verify real-phone credit, borders, replay/teleport
rejection, privacy and deletion before enabling public competition.

For that internal build, pass `-PROADCONQUEST_LEADERBOARDS_VISIBLE=true` and
`-PROADCONQUEST_ACCOUNT_API_URL=<test deployment HTTPS URL>` to
`./gradlew :app:bundleRelease`. The visibility property defaults to `false`;
it never selects a fake verifier or changes the server's verification requirements.

Production has no selectable test verifier. CI/emulators cannot establish Google
eligibility. Rankings remain hidden until setup succeeds. `server/README.md`
lists the settings and scoring rules.

These checks cannot prove real GPS truth or car travel. External GNSS spoofing
and device farms remain possible. Review unusual winners before valuable prizes;
do not advertise scores as unfakeable. Hosting and Integrity quotas still apply.
