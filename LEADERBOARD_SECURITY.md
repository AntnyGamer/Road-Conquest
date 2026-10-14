# Verified-only competitive scores

Every account's public and friend rankings use scoring policy 2. The server computes
distance from trusted road matching and counts canonical road IDs from its pinned catalog.
Local points, saved GPS, restored backups, uploaded totals and old scoring columns cannot
contribute. Older totals remain stored without being promoted to verified credit.

Before accepting a batch, the server requires an authenticated eligible account, a fresh
server-issued run/nonce/sequence, and a Google-decoded integrity token bound to the exact
evidence bytes. The token must identify the approved Play app-signing certificate, a tested
version code of 71 or later, `PLAY_RECOGNIZED`, `LICENSED`, `MEETS_DEVICE_INTEGRITY` and
`MEETS_STRONG_INTEGRITY`. Sideloaded and weak-integrity devices cannot compete. Strong
integrity also requires recent patches on Android 13+; some otherwise usable phones will
therefore be ineligible for ranked competition.

GPS fixes must be fresh, non-mock, accurate, ordered and physically plausible, with a
consistent monotonic clock. Every matched trace must be complete and unambiguous and
use the pinned dataset. The private matcher requires a server-only bearer secret. Changing
client road names, distance, road IDs or matcher URLs cannot influence scoring.

The latest accepted fix is encrypted with AES-256-GCM and scoped to the account and map.
Continuity is checked across batches, restarts, sessions and devices. A live drive must
remain plausible for ground travel; separate drives may follow plausible air travel.
No unobserved gap earns distance. Corrupt checkpoints fail closed rather than resetting
the replay boundary. Keep the dedicated encryption key stable; key rotation needs an
explicit checkpoint migration.

Account row locks serialize changes. Exact retries return the same committed receipt;
altered sequences, overlaps, old-policy receipts, impossible jumps and moderated accounts
cannot add credit. Eligibility is checked again inside the transaction after remote
verification. Distance, road flags, weekly counts and the receipt commit together.

The Android app requires policy-2 verified responses for competitive scores. Having an
account never enables a local-score fallback. Live GPS sharing still needs consent;
turning it off stops competitive credit. Older sharing consent does not activate policy 2;
the user must explicitly opt in again. Local exploration remains usable. Account
deletion removes the encrypted checkpoint and competitive records.

## Activation requirements

Leaderboards remain hidden until all of the following are actually operational:

1. An immutable road snapshot, its database catalog and the matching OSRM data version.
2. An authenticated private HTTPS matcher tested against real reference drives.
3. Play Integrity enabled for this Play app and the strong-integrity label opted in.
4. Server-only Google credentials, project number, app-signing certificate and approved
   version list, plus separate random matcher and checkpoint secrets.
5. A successful end-to-end submission from the actual Play-installed release on a real
   eligible phone, and rejected tampering/retry/privacy cases against that deployment.

`server/README.md` lists the required environment variables. Once infrastructure is ready,
enable `LEADERBOARDS_ENABLED=1` on a test deployment and `LEADERBOARDS_VISIBLE` in an
internal Google Play test build. Verify a real eligible phone without simulated verdict
overrides. After the end-to-end checks pass, enable the tested setup for public competition.
Deterministic test verifiers and emulator tests do not establish real Google eligibility.

## Practical limit

These checks make common cheating substantially harder; they cannot prove physical GPS
truth or that the phone owner was driving. External GNSS spoofing on a genuine phone,
device farms and sophisticated attacks remain possible. Do not advertise the system as
unfakeable. Review unusual winners before introducing valuable competitive prizes.
