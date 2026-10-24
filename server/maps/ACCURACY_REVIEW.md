# Accuracy review of this chat's changes

The review compares this branch with its starting commit,
`137e398706c0caf591ac13c091634a3711fc15e2`. Provider allowance savings must not
depend on lower GPS quality, weaker matching evidence or reduced recording.

## Source checks

During the original resource review, the complete Geoapify parser/client, LocationIQ
parser/client, shared `RoadGeometryValidator` and phone provider-cooldown implementation
were unchanged. The subsequent repeated-junction parser correction is documented below.
The entire local `KnownRoadReuse.identify` acceptance algorithm and its edge
checks are also unchanged. This includes five actual fixes, minimum span,
accuracy, speed, heading and continuity requirements. Saved-road lookup still
uses its existing snap and parallel-road exclusion rules.

The original GPS acceptance functions, bend/chord test, saved GPS fields and
track insertion/distance-writing implementation compare identically. The turn
retention change uses the same wrapped-bearing calculation and retains every
previously retained curve fix, adding the last original approach fix at the
existing direction-change threshold. It does not remove original evidence.

## Earlier changes reviewed

| Change | Accuracy protection |
|---|---|
| Town-border line merging | Joins degree-two line pieces; retains survey coordinates, junctions, holes and disconnected geometry |
| Short-turn approach retention | Keeps original GPS evidence in addition to the existing curve selection |
| Backup failure scope | Defers only submitted pending IDs; never credits an unsent point |
| Backup window placement | Changes a prefix only when it contains no eligible work; submits one contiguous slice with genuine approach fixes; existing parsers and valid-NoMatch precedence remain |
| Exact cached coverage of eligible work | Requires an accepted road and accepted point confidence for every eligible fix in the actual provider slice; unresolved deferred context stays unmarked; cache equality, expiry, parser checks and provider precedence remain |
| Known-highway evidence | Uses original captured intermediate fixes with a bounded association to the exact preceding retained ID; no timestamp-only trust after restart/eviction; invokes the unchanged local acceptance algorithm |
| Budget/HMAC caching and proven quota rejection | Changes accounting work; no trace geometry or matching decision is cached on the server |
| Early cancellation | Stops abandoned work before reservation; retains active-request handling and conservative dispatch/refund accounting |
| Empty verified-cell insertion | Skips only an empty insert after server scoring; retains evidence validation, eligibility, locks, continuity, receipts and atomic persistence |

## Additional local-only approach context

A live singleton on a verified road can have only three saved approach fixes in
its online window. That is four actual fixes, so the unchanged five-fix local
rule cannot accept it. For this exact four-fix case, the phone may read the
immediately preceding saved ID as one additional local-only context point.

That point must already be matched and fall within the original 12-second
continuity limit. Missing IDs, unmatched/deferred points, pauses and reversed
time cannot supply it. The unchanged five-fix algorithm still checks every
sample and edge, including geometry, heading, speed, accuracy and road ambiguity.
Only the original eligible pending IDs may be completed. A failed proof sends
the exact original online request; the extra point is never uploaded as part of
that request. Existing positive original/intermediate-evidence results are kept.

The new fixtures also exercise a stream of 100 genuine saved fixes following
four matched approach fixes. The original path makes two online matcher calls;
the additional context avoids both while retaining all 104 records and the one
verified road. The online matcher is stubbed: these are request-selection and
guard regressions, not measured physical-drive accuracy or a fleet savings rate.

The broader regression run includes the provider parsers, geometry/turn
validators, ambiguity, malformed evidence, retry/fallback windows, GPS retention,
distance persistence and both forms of local reuse. All **411 tests across 13
suites** pass with zero failures, errors or skips, together with Android lint and
the debug build. Fourteen source invariants were checked against the starting
commit, including the complete distance calculator, database schema and tracking
constants. The preceding highway follow-up also passed all **1,043 Android unit
tests** in CI; the XML artifact confirms zero failures, errors or skips.

The subsequent exact-cache eligibility fix also passes **423 tests across 14
suites**, Android lint and the debug build. Its twelve new SDK-expanded cases
exercise both the real HTTP clients/router and tracking service: unresolved
deferred context cannot block fully validated eligible work, remains unmatched
with its original deferral, and becomes required evidence if made eligible.
Changed traces, missing or low-confidence eligible evidence, empty work and
missing/low-confidence roads cannot use the bypass. The same fourteen source
invariants were rechecked against the starting commit and still match.

Source equality and fixture
tests cannot prove accuracy parity on every physical drive. No matching threshold,
GPS subscription, confidence rule, source coordinate or remote cadence is relaxed
to obtain these savings.

## Full resource follow-up review

The subsequent [resource review](../RESOURCE_REVIEW.md) also rechecks all fourteen
source invariants against the same starting commit. Its eight changes cover
accounting metadata, duplicate exploration writes, unchanged encrypted account
refreshes, boundary-cache retention/cleanup, obsolete overlay work, row-buffer
reuse and compaction after explicit data deletion. None modifies driving or
matching acceptance, schema, evidence retention or distance calculation.

The complete local run passes **1,073 Android unit tests across 67 suites** with
zero failures/errors/skips, Android lint and both debug app/test APK builds.
All **122 server tests** pass against isolated PostgreSQL. A local workerd tile
comparison preserves all 400 complete responses and final durable-state hashes,
including matching-attempt enforcement after the tile workload. Device Keystore,
native map rendering and optimized startup still require emulator CI; local
unit tests and APK compilation are not substitutes for those checks.

## Repeated junction vertices

The follow-up compares the Geoapify parser with commit
`02f2b0a41027a72d141f073da944b0cb97ac2a5d`. A provider step can represent a junction
with two consecutive, identical coordinates. Previously, the waypoint projector
skipped that zero-length edge, discarded its matched waypoint and split an otherwise
valid approach/turn/exit into unmatched singletons. A three-fix synthetic drive
reproduces the loss of both roads at latitudes 0, 40, -40 and 70 degrees.

A single collapsed edge can now locate its junction using the existing strict
3-metre position limit, just like the existing single-index junction case. Ordinary
nonzero edges and multi-edge projection are unchanged. Zero-length geometry still
covers its provider index interval but supplies no road step: this prevents a
sub-metre waypoint offset from turning an empty junction into an extra named road.
The approach and exit retain their original provider coordinates and pass through
the unchanged shared geometry validator, including its 1-metre join tolerance,
snap, confidence, detour and speed checks. No threshold, GPS sample, retry, provider
call, subscription or server setting changes.

Two regression methods cover all four latitudes. They verify contiguous approach
and exit geometry, preservation of independently accepted sections, all eligible
point IDs, no zero-length junction road credit, and rejection of an out-of-range
junction while retaining independently valid sides. The three-fix response changes
from zero accepted roads to exactly the two genuine roads; normal single-index
junction responses compare identically with the original parser. A sub-metre
offset regression also reproduced spurious junction credit before the zero-length
step guard and passes after it.

Local verification compiles the actual production parser, validator, grouping and
models with Kotlin and executes seven parser regression methods, including the
date-line, malformed-step, malformed-waypoint and multi-leg cases. Network services
are stubbed to prohibit provider calls. These synthetic checks are not physical-drive
measurements or a full Android test run; Android CI validates the committed tests
with the rest of the app.
