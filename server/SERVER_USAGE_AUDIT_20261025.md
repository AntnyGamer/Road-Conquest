# Server usage audit, October 25, 2026

This pass starts at PR #119 commit `f034a94`. It inventories network entry points
throughout the Android app, all server modules, both Workers, database schemas,
service configuration, assets and workflows. UI/local-only code was checked for
call sites rather than treated as an independent source of remote usage.

## Complete PR inventory

PR #119 contains nine earlier account/notification improvements and the nineteen
audit changes detailed below: 28 grouped changes in total. The count groups the
same improvement across call sites, rather than counting each edited file. The
additional release review found no further implementation change that met the
requested tradeoff standard.

| Earlier change | Result and boundary |
| --- | --- |
| Atomic notification reset | Delete all data turns off friends, friend posts and weekly preferences in one commit before updating the device registration and clearing the weekly notification/reminder. |
| Dismiss friend-request alerts on opt-out | Disabling that notification type also removes an alert already displayed. |
| Bound profile reads and logout attempts | `/v1/me` and `/v1/logout` combine persistent IP budgets and session work in one SQL request. An active session can still revoke itself when its shared-address budget is exhausted. |
| Reserve other account budgets before session lookup | Privacy, username change, deletion and reauthentication use the existing combined budget/session path. Remove the unthrottled helper. |
| Independent reaction budget | Thirty reactions per user per minute, alongside the shared sixty forum writes per minute. Both reservations share one SQL request. This intentionally limits reaction spam. |
| Independent purchase-verification budget | Five verifications per user per minute, alongside the shared forum-write budget. This intentionally bounds expensive Play calls; ordinary purchases/restores still work. |
| Combine webhook receipt/owner lookups | Processed-event and purchase-owner checks share one SQL statement, preserving durable duplicate handling. |
| Skip unchanged Plus profile writes | Repeat verifications still refresh receipt metadata but avoid rewriting unchanged `plus_active` profile state. |
| Coalesce verified GIF downloads | Simultaneous views requesting the same SHA-256 digest share its download and bounded four-item byte cache. Distinct digests stay parallel, failures retry, and screen-close invalidation blocks late cache repopulation. |

Beta 62 / versionCode 88 was already prepared and had not been published at the
release review. Retain that version and extend its release notes. Merge is gated
on the final PR checks; the existing main-branch workflow then tests the merged
commit again before publishing its signed artifacts. Backend deployment and Play
Console upload remain separate from GitHub release publication.

## New changes

### Geoapify and LocationIQ exact response reuse

Each matcher previously remembered only its most recent successful parsed
response. Alternating unchanged retry windows could evict one another within the
existing 15-minute lifetime and spend provider calls on identical evidence.

Both now use a shared cache implementation with at most four responses per
provider, 256,000 response characters per entry, and 288,768 characters across
all request keys and bodies. This aggregate bound is one maximum old-style
response plus one gateway-sized request. Least-recently-used entries are evicted.
Cache hits do not refresh the original expiry, and clock rollback misses.
Only exact serialized requests match. The provider parser still rechecks every
response against the current caller's point IDs and geometry gates. No failed,
malformed, or oversized response is newly cached. Provider order, quotas,
matching cadence, GPS collection, local-road eligibility and geometry validation
remain unchanged. Cache metadata and a four-entry lookup add a small amount of
phone work; avoiding repeat paid calls makes this bounded tradeoff worthwhile.

The adapter regression cycles four distinct traces three times: each provider
uses four HTTP requests instead of twelve. This is a duplicate-work fixture,
not a claim of 67% savings during ordinary driving. Additional tests exercise
changed timestamps/LocationIQ accuracy, existing point-ID remapping, expiry,
clock rollback, count/aggregate bounds and oversized-response rejection.

### Duplicate Google Play billing notifications

A warm API instance now shares processing for an identical notification ID AND
encoded event only while that work is pending. Each delivery first verifies its
own Google identity. At most 32 pending keys are kept; additional distinct work
uses the original path. Success and failure remove the entry. Durable receipts
remain authoritative across restarts and API instances. Different purchase,
refund and restore events still query Play; no completed ownership verdict is
cached.

The regression sends sixteen overlapping deliveries: one ownership/receipt
lookup and one Play purchase verification occur, rather than sixteen. All
sixteen still authenticate. Failed shared work records no successful receipt and
can retry. Separate tests check distinct events, changed payloads, overflow and
later duplicate delivery. The small bounded promise registry is the tradeoff;
savings require overlapping duplicates in the same API instance.

### Unchanged moderation appeals

Clearing already empty forum penalties now skips the PostgreSQL row rewrite.
The same moderator authorization, target lookup and response remain. A database
regression checks that clearing real penalties works and a second clear leaves
`xmin` unchanged. This is a small, occasional WAL/write saving, not a major
capacity increase.

## Additional seven changes from the extended pass

| # | Change | Scope of savings and preserved behavior |
| --- | --- | --- |
| 4 | Combine post insertion, cooldown and optional penalty | One SQL write statement instead of two for an approved post or three for a flagged post. The profile lock, receipt check, transaction and after-commit notification remain. |
| 5 | Combine locked-post lookup and reaction mutation | One fewer SQL round trip per reaction. Missing/blocked/deleted posts still reject, and unchanged reactions still avoid row rewrites. |
| 6 | Combine purchase receipt refresh and entitlement reconciliation | One fewer SQL round trip per verification. The verified state of this token and other active receipts determine access within the existing locked transaction; refunds/restores and no-op profile writes are preserved. |
| 7 | Reuse imported private OAuth signing keys | Play billing, Firebase and Integrity lazily import their fixed service key once per instance. Every assertion is still freshly signed; OAuth lifetimes, scopes and concurrent refresh behavior stay the same. This saves CPU, not provider requests. |
| 8 | Reuse parsed Google notification verification keys | A key is imported once within its existing five-minute JWKS lifetime and ten-key bound. Every delivery still verifies its signature and claims. Rotation replaces the parsed keys too. CPU saving only. |
| 9 | Avoid copying a single-chunk GIF download | A correctly offset Buffer view goes directly into the existing frame decoder/hash pipeline. Split downloads still concatenate. Same bytes, byte limits, contact sheet and moderation verdict; at most a 2 MiB copy avoided. Memory/CPU saving only. |
| 10 | Invalidate rejected Integrity OAuth credentials | A Google 401 discards only the access token used by that request so the next ordinary retry refreshes it. A delayed old-token rejection cannot discard a newer refresh. No extra immediate retry and no weaker Integrity checks. |

The mutation tests assert transaction statement counts of 5 for posts (including
flagged posts), 4 for reactions and 5 for purchases, down from 6/7, 5 and 6.
Forced profile-write errors verify rollback of post text, penalties, cooldown and
purchase receipt changes. Multiple active receipts and refund/restore transitions
are also tested.

A small local Node benchmark (100 RSA operations, five rounds, synthetic keys)
measured median signing time of 64.2 ms with repeated PEM parsing versus 28.8 ms
with an imported key; verification measured 4.6 versus 2.4 ms with repeated JWK
imports versus reuse. These are narrow CPU measurements, not API latency,
invoice savings, or larger provider allowances. Each service retains one small
private KeyObject; at most ten public KeyObjects share the preexisting JWKS
lifetime. The bounded memory tradeoff avoids repeated cryptographic parsing.

## Final strict pass: four more changes

| # | Change | Evidence / boundaries |
| --- | --- | --- |
| 11 | Disabled public leaderboard needs no database | With verification disabled, the same empty availability response now uses zero SQL, including a signed-in `mine=1` request. Invalid metrics still reject. An offline cold-start test verifies no connections or queries. This static endpoint no longer spends the inactive competition rate budget; all enabled competition/auth limits remain. |
| 12 | Reject announced oversized upstream bodies early | JSON verification replies and GIF moderation downloads cancel a truthful oversized Content-Length before reading. Unknown lengths retain stream limits. Encoded responses skip this header shortcut because Fetch reports wire length while delivering decoded bytes. Lazy-stream tests confirm zero reads and unchanged decoded limits. |
| 13 | Return only needed forum profile columns | Reactions return two columns instead of five; purchase and moderation preflights return one instead of five. Fixed internal column lists preserve missing-profile creation and row locks. Transaction tests check both the reduced projections and functional results. This reduces Neon response metadata/data, not query count. |
| 14 | Do not await cleanup after definitive Google results | Accepted FCM sends return after requesting cleanup; invalid Integrity-token HTTP 400 responses remain permanent 403 failures. Failed/stalled body cleanup cannot hold the request open or turn an invalid token into a retryable outage. Tests cover rejecting and never-settling cleanup. |

The follow-up requested searching for 100 worthwhile improvements. Fourteen
were justified in the server-focused pass; the implementation does not force the
count to 100 by treating the same fix at multiple call sites as separate work.
Broader caching, lower sampling/detail, wider road snapping, longer delays,
changed verification, more batching, cache TTL changes, extra indexes and
infrastructure replacement did not meet the required tradeoff standard.

## Deep review and app performance follow-up

| # | Change | Behavior preserved |
| --- | --- | --- |
| 15 | Recheck GIF cancellation immediately before opening a connection | A worker that waited for another digest download must not start network I/O for a view cancelled while waiting. Existing cancellation, hashing, decode and close checks remain. |
| 16 | Reuse each adjacent-road identity comparison | Geometry split bridging computes its pure normalized-name/reference comparison once instead of twice. Same thresholds, snap gates, corner evidence and accepted roads. |
| 17 | Fill resolved-ID sets directly | Known-road reuse uses `flatMapTo(HashSet())` in two places instead of building a temporary flattened list and then a set. The same point IDs are removed from the same eligible set. |
| 18 | Reuse the initial fog quad | Three initially transparent fog sources share one read-only camera-coordinate snapshot during style installation. This avoids two repeated projection passes; subsequent per-source positioning and bitmap publication are unchanged. |
| 19 | Precompute municipality prefix/suffix forms | Fixed legal-type strings are constructed once rather than up to 36 temporary strings per identity lookup. Same order, normalization, conflicting-type rejection and town/state/country rules. |

The changed matcher/geometry classes compile with Kotlin 2.3.20. A host-JVM
run passed 104 existing pure matching/cache/geometry cases, using the pinned
HTTP fixture dependencies and a host JSON implementation. Android annotations
were omitted only in temporary copies; three Android database integration cases
were excluded rather than replaced with fake storage. This is additional
verification, not a substitute for Robolectric/device CI. Separately, 100,000
municipality names gave identical before/after identity results using the actual
extracted old/new methods. The unchanged server behavior has passed all 175
server cases on native PostgreSQL in CI. The latest follow-up reruns full Android
checks; consult the PR rather than assuming an earlier run covers a later commit.

The earlier fourteen improvements cover the server-focused pass. This follow-up
adds one cancellation fix and four small app optimizations. The older account,
notification, rate-limit and GIF coalescing changes already in PR #119 remain
part of the complete PR and are described in its body.

## Other paths reviewed

| Area | Existing protections retained / decision |
| --- | --- |
| Matching and tracking queue | Up to 100 fixes per normal paid primary batch, 25 for backup; exact request cache; local validated road reuse; deferred unresolved fixes; valid NoMatch backoff. Larger batches, longer retries or relaxed turn evidence would change accuracy or latency. |
| Map Worker / Durable Object | Separate provider budgets, per-address limits, pacing, attempt ceilings, cancellation refunds, provider recovery probes, bounded authoritative metadata caches and daily cleanup. No quota increases or weakened anti-abuse gates. |
| Public map tiles and switches | Native Workers caching is already enabled; tiles declare a one-day TTL, matching/errors are no-store. Switch reads coalesce with KV caching. Another tile cache would be redundant. |
| Neon account/authentication | Fresh session checks, combined rate-limit queries, saturated counter write avoidance, bounded pool, lazy initialization and request-driven hourly expiry cleanup. Liveness needs no SQL; deep health intentionally does. No stale identity cache. |
| Friends / leaderboards / verified driving | Capped friend graph, combined consent/ranking queries, bounded top-50 rankings, combined totals/week writes, idempotent batch receipts and in-flight Integrity deduplication. Public Android builds keep verified competition disabled. |
| Notifications / Firebase | Six-hour unchanged device-registration cache, fresh account bindings/consent checks, OAuth reuse, bounded per-recipient delivery, sampled weekly-capped friend-post alerts. No added polling or refresh delays. |
| Forum / moderation / GIFs / billing | Paged feed, 10-minute post cooldown, per-user reaction/purchase limits, idempotent post IDs, pending-post reuse, bounded media, verified GIF reuse, OAuth/signing-key caches and durable billing receipts. Ownership/refund checks must stay current. |
| Census / Android geocoder / Nominatim / geoBoundaries / country population | Persistent verified geometry and fixed Census population caches, batch-local exact polygon reuse, bounded downloads, provider throttling/backoff and per-place lookup coalescing. Broader location caches could misidentify a boundary crossing and were not added. |
| Ads / crash reports / exports / replay / onboarding | Optional service gates and local-only paths inspected. No new server or polling mechanism added. |

## Read-only Neon check

The production branch reported 33,366,016 logical bytes (about 31.8 MiB) against
the configured 512 MiB branch limit. Its endpoint had pooling enabled, a 0.25 CU
ceiling and was idle/suspended at inspection. The temporary rollout endpoint was
also idle. This does not establish future capacity, but it provides no evidence
of an immediate storage or always-awake-compute problem. No production SQL,
statistics reset, migration, infrastructure setting change or deployment was
needed for this audit.

Geoapify and LocationIQ remain finite shared allowances: reducing duplicate work
does not make unique new-road matching unlimited. Broader retry delays, lower
map detail, caching account state, removing verification, and replacing providers
were not justified by this audit.

## Validation

Local server suite: 149 passing, 26 skipped native-PostgreSQL-only cases, no failures.
Server and regression tests use PostgreSQL SQL via PGlite where supported.
Native PostgreSQL-only cases run in CI. The cache helper also compiles and passes
three JUnit cases using Kotlin 2.3.20. CI performs the Android adapter tests, unit
tests, lint and builds; consult the latest PR checks for their current status.
Local Gradle execution could not download its distribution through the Java
network stack in this environment; full Android results must be read from CI.
No live map-provider requests or Google purchase/moderation traffic were used.

References: [Workers caching](https://developers.cloudflare.com/workers/cache/),
[Geoapify open tile policy](https://www.geoapify.com/map-tiles/).

SQL semantics and key reuse references: [PostgreSQL writable CTEs](https://www.postgresql.org/docs/18/queries-with.html), [Node crypto KeyObjects](https://nodejs.org/download/release/v24.18.0/docs/api/crypto.html).
