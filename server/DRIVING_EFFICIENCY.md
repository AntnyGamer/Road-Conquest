# Additional driving work reduction

Compared with `8df48c7`, two small changes remove deterministic repeated work:

- The maps gateway derives its fixed `health:matching` identity once per provider
  secret in a warm handler. A two-entry cache shares concurrent derivations,
  evicts old secrets on rotation and retries failed derivations. The cached value
  is a provider scope, not provider availability or request data. Every daily
  client/IP bucket is still freshly signed; reservation IDs, allowance checks,
  dispatch, refunds, recovery probes and upstream requests remain unchanged.
- A verified batch whose **server-derived qualifying cell set is empty** skips
  the empty cell-insertion statement. Attestation, current eligibility, account
  and challenge locks, encrypted continuity checkpoint, sequence advancement,
  retry receipt and transaction rollback still run. A batch containing only
  previously visited qualifying cells still uses the original database insertion
  and conflict checks. No client claim can select this fast path.

| Measured work | Before | After |
|---|---:|---:|
| Fixed health derivations, 200 concurrent matching batches / two stable keys | 200 | 2 |
| All HMAC operations in the same fixture | 400 | 202 |
| Client signatures / reservations / upstream calls in the same fixture | 200 each | 200 each |
| Empty-derived-cell verified batch, SQL statements including BEGIN/COMMIT | 10 | 9 |
| Exact committed retry of that batch | 1 | 1 |

The gateway comparison ran in local Cloudflare **workerd**, through Miniflare
`5.20261012.1-beta`, with synthetic GPS and stubbed budget/upstream services.
It used no provider credits or production traffic. Counts measure cryptographic
operations, not whole-request CPU, latency or additional free driving capacity.
Cold handlers and evicted/rotated keys derive their health identifiers again.
The two-entry cache adds a small bounded amount of handler memory.

Database counts used isolated local PostgreSQL and a synthetic accurate moving
trace near a cell edge that cannot establish a unique qualifying visit. They
exclude HTTP authentication and maintenance. Skipping an empty insert saves one
round trip and statement execution; it does not remove a database row write.
Verified competition is currently hidden/disabled in the Android build, so that
SQL saving applies when that optional service is enabled. Ordinary road matching
benefits from the gateway change independently of verified scoring.

All **105 server tests** passed with zero failures or skips, including concurrent
requests, exact signatures, bounded rotation/eviction, failed derivation recovery,
unchanged budgets/pacing/probes, empty-batch attestation, checkpoint rollback,
continuity rejection, receipt retries and fresh eligibility. Wrangler `4.151.1`
accepted the gateway bundle in a dry run. [Recorded counts](driving-efficiency-evidence.json).

Those two initial changes do not alter GPS, Android matching, provider quotas,
scoring rules, schema, service plans or production configuration. The gateway and
account-server improvements require their respective deployments to become live.

## Larger map-budget read reduction

A further change, compared with `9086a6c`, removes repeated SQLite reads inside
the shared map-budget object. It retains at most **512 counter entries and 64
permit records** in memory. Every admitted request still writes its counters,
permit and dispatch marker durably in the same synchronous transactions. Every
request still evaluates the current global/client credit limits, non-refundable
attempt ceilings, pacing, provider health and dispatch eligibility.

Only missing counters are read from SQLite. Newly created permits can be used
for dispatch without reading back the row just inserted. Refunds invalidate the
affected balances; UTC day changes and storage exceptions clear the caches.
Eviction and restart reload from SQLite, including historical whole-credit
balances and spent/cancelled permits. Unknown permit IDs are not cached.

This relies on the existing Durable Object being the sole active owner/writer
of its private storage. Cloudflare documents this pattern for
[in-memory state initialized from storage](https://developers.cloudflare.com/durable-objects/best-practices/access-durable-objects-storage/)
and the rollback behavior of
[synchronous SQLite transactions](https://developers.cloudflare.com/durable-objects/api/sqlite-storage-api/#transactionsync).
The new caches contain only metadata already held by this object, never GPS,
trace fingerprints or provider responses. They add a small bounded memory cost.

The before/after fixture ran the actual budget implementation in local
Cloudflare **workerd**, across two independent objects and **200 synthetic
client buckets per provider**. It filled the existing 2,400-primary/4,000-backup
allowances, using exactly the same reservations and dispatches in both versions.

| First-day runtime counter, including initialization/cleanup | Before | After |
|---|---:|---:|
| Primary SQLite rows read, 2,400 matches | 44,800 | 25,607 |
| Backup SQLite rows read, 4,000 matches | 75,200 | 43,207 |
| Combined SQLite rows read | 120,000 | 68,814 |
| Combined SQL executions | 38,418 | 32,018 |
| Combined SQLite rows written | 45,624 | 45,624 |

That is **42.7% fewer rows read** and **16.7% fewer SQL executions** in this
fixture. A separate warm single-client fixture, stopping at the existing client
caps, reduced reads by **68.4%** and SQL executions by **33.3%**. Cold starts and
evicted entries need more reads. Local elapsed times were similar; these counts
do not establish whole-server CPU, latency, billing or driving-capacity gains.
Provider requests, provider credits, durable writes and storage size are unchanged.

Both versions produced identical complete database-state digests before and
after next-day cleanup, identical exhausted-quota responses, and correct retries
after a real SQLite transaction rollback and object recreation. Five additional
regressions cover warm reads, bounded eviction, restarts, historical balances,
lowered caps, refunds, shared tile/matching accounting, rollback and a 1,200-step
comparison against forced-cold durable reads. All **110 server tests** pass with
zero failures or skips. Wrangler accepts the gateway in a dry run. No deployment
or production traffic was used; this change requires a maps-gateway deployment.

## Avoid backup requests containing no eligible work

The primary matcher can receive a 100-fix window around an old gap. When it
becomes unavailable, the 25-fix backup previously received the first 25 fixes
even if all were already resolved context. No pending fix could be completed
from that request, so the gap still needed another matching attempt.

The phone now passes the eligible pending IDs into its fallback matcher. A
provider keeps its existing prefix whenever it contains any eligible fix. Only
when that prefix contains none does the matcher move its one contiguous window
to the first eligible fix, retaining up to three genuine approach fixes. It
does not skip raw samples inside the selected window, increase request count or
capacity, or change any confidence, snap, speed, continuity or NoMatch rule.

The tracking service uses the actual submitted offset/limit for cached coverage,
successful completion, failure deferral and unchanged-NoMatch backoff. Deferred
unmatched context is distinguished from eligible work. Unsubmitted pending
fixes remain eligible. Primary valid NoMatch/ambiguous answers still stop the
fallback chain. Complete cached answers may bypass the existing network gates;
partial/negative cached answers still cannot.

The regression fixtures use the real tracking service, fallback matcher and,
for positive/cache cases, the actual LocationIQ HTTP client and geometry parser
against a local mock server. They cover a 100-fix history with two pending fixes
in its middle, a 35-fix gap where the backup can receive only 22 pending fixes
plus three context fixes, complete and negative caches, and deferred unmatched
context. All saved GPS points and the existing remote cadence are retained.
All **225 focused Android tests** pass with zero failures, errors or skips,
covering matching, retries, repository windows and local reuse. Android lint and
the debug app build also pass. The primary outage is stubbed, and no real map
provider is contacted.

This is a targeted removal of wasted matching work, not a measured fleet-wide
savings percentage. Synthetic successful provider responses cannot establish
physical-drive accuracy or ensure every gap resolves. The change requires an
Android app build; there is no additional gateway deployment for this part.

## Stop abandoned map work before reservation

The gateway previously checked caller cancellation after reserving a slot.
Already-abandoned matching requests could therefore reserve and refund credits,
write permit/counter state, consume non-refundable attempt allowance and occupy
provider queue slots. Tiles kept their lightweight accounting path, so an
already-abandoned tile could consume a quarter-credit reservation even though
the supplier was never contacted.

Cancellation is now checked before reading a map request, before/after remote
configuration and after asynchronous signing, all before starting a reservation.
The same private `request_cancelled` response is returned. Active requests still
perform every budget, pacing and dispatch check. Cancellation after reservation
retains the existing cleanup; dispatched or ambiguous work is never refunded.
No provider outage is inferred from a caller cancelling, and a shared config
refresh can still finish for other active callers.

The local Cloudflare **workerd** fixture compared `3cd4387` with this change.
Each case sent 100 already-aborted requests to an isolated budget object, then
one active request. The cancelled calls never contacted a provider in either
version; the next active call succeeded in both versions.

| Work caused by the 100 abandoned requests in each case | Before | After |
|---|---:|---:|
| Primary matching: internal budget requests | 200 | 0 |
| Backup matching: internal budget requests | 200 | 0 |
| Tiles: internal budget requests | 100 | 0 |
| Combined SQLite rows written | 2,119 | 0 |
| Primary/backup non-refundable attempts | 100 each | 0 each |
| Tile quarter-credit units reserved | 100 | 0 |

Including the three active follow-up requests, total internal calls fall from
505 to 5 and rows written from 2,136 to 36. These totals include daily cleanup
when it runs on the first active request instead of on an abandoned one.

Eight regressions cover early cancellation without reading GPS bodies, aborts
during body/config/client-signature/health-signature work, shared refreshes,
subsequent active requests and a cancelled tile burst ahead of live matching.
The full **118 server tests** pass with zero failures or skips, including the
existing queue cancellation, lost acknowledgements, non-refundable attempt
caps, dispatch and outage recovery tests. Wrangler accepts the gateway in a
dry run. No production data, real provider traffic or deployment was used.

This saves work only when cancellation is known before reservation begins.
Actual savings depend on real cancellation frequency; these fixture counts are
not a fleet-wide estimate or a claim that handling the HTTP request costs zero.
It requires a maps-gateway deployment, with no additional Android change.

## Skip caller-counter reads when global rejection is already known

Compared with `510ac86`, the budget object avoids loading missing client
counters when its existing authoritative global balances already prove the
current request cannot fit. Both the historical whole-credit and current unit
balances must be present. Cold/restarted objects and invalidated refund balances
still load SQLite. This uses the existing bounded cache, with no new retained
state, expiry or rejection cache.

Permit replay/conflict checks and provider cooldowns still run first. The normal
global-limit check returns the same status, body and headers. The proof uses the
current cap and this request's actual cost, so a rejected matching request cannot
block a cheaper tile that still fits. Cap increases, refunds, UTC rollover and
storage-error invalidation retain their existing behavior. No admitted request
skips its counter writes or other eligibility checks.

The actual budget code ran before/after in local Cloudflare **workerd**. Each
primary, backup and tile case first admitted 300 synthetic client reservations,
then rejected 100 previously seen clients evicted from the cache and 100 new
clients. Setup work is excluded from the following rejection-only counts:

| Work for the 200 rejected requests in each case | Before | After |
|---|---:|---:|
| Primary: caller-counter queries / all SQL executions | 200 / 400 | 0 / 200 |
| Backup: caller-counter queries / all SQL executions | 200 / 400 | 0 / 200 |
| Tiles: caller-counter queries / all SQL executions | 200 / 200 | 0 / 0 |
| Combined runtime SQLite rows read | 2,300 | 0 |
| Combined runtime SQLite rows written | 0 | 0 |

All 600 responses matched exactly, including `Retry-After`. Complete durable
state matched before/after rejection and after recovery/next-day cleanup. Every
unknown matching attempt still performed its durable replay lookup. Recreating
each object forced one counter query before rejection; matching refunds, live
tile-cap increases and next-day reservations then admitted work correctly.

Three new regressions also cover exact costs, historical balances, bounded
eviction, replay/conflict/health precedence and refunds. The full **121 server
tests** pass with zero failures or skips, including the existing 1,200-operation
warm/cold comparison and rollback tests. Wrangler accepts the bundle in a dry
run. The previous combined CI run at `510ac86` passed all 1,019 Android unit
tests, lint/build, native map checks on Android 12/15/17 and release startup on
Android 12/17; this follow-up changes only gateway code/tests and evidence.

These are synthetic exhausted-budget results, with no provider or production
traffic. They measure rejection accounting, not normal driving, whole-server
CPU, billing or additional provider capacity. The HTTP handling and replay
checks still have a cost. This requires a maps-gateway deployment.

## Additional provider-allowance capacity

The next pass targets paid matching requests on precise, already-known highways.
Saved original intermediate fixes can corroborate compressed queue points while
preserving the existing local geometry/quality rules. Capture associations prove
which retained interval each fix belongs to; a bounded 512-pair memory map falls
back safely after restart/eviction. No geometry, GPS sample or new road is invented.

The SDK 31/37 service fixture resolves all 100 retained fixes of a verified
highway using 99 original intermediate fixes, with zero online matcher calls.
The retained-only reference needs one online invocation. Both keep the 199 GPS
records and one existing road. Provider replies are stubbed. All 281 focused
Android tests, lint and the debug build pass. There is a small local capture
lookup and bounded metadata cost; no schema, provider-limit or cadence change.

[The allowance audit and reproducible planning model](maps/USAGE_LIMITS.md)
separate actual request avoidance from conditional daily-hour/driver estimates,
and account for shared raster-tile spend, repairs and denser retained GPS.
This part requires an Android build, with no additional gateway change.

## Avoid singleton lookups on already-verified roads

A single pending fix with three matched approach fixes previously fell one
actual sample short of the strict five-fix local rule. For this four-sample case
only, the local matcher may read one immediately preceding matched record. It
must be adjacent and within the existing continuity limit, then pass the same
accuracy, speed, heading, span, snap and ambiguity checks. This extra context is
never added to the provider request, and it never becomes eligible work.

The SDK 31/37 service fixture feeds 100 genuine saved fixes after four matched
approach samples. Online matcher invocations fall from two to zero, keeping all
104 GPS records and the one verified road. Missing, unmatched/deferred, stale,
reversed-time, inaccurate, wrong-heading, high-speed, off-road or ambiguous
context cannot establish the proof. When a request is still needed, it contains
exactly the original samples. The provider reply is stubbed; this is targeted
request avoidance, with no additional fleet-capacity multiplier claimed.

All **411 focused matching/accuracy tests** pass with no failures, errors or
skips, including the new ten SDK-expanded cases. Android lint and the debug build
pass. The [accuracy review](maps/ACCURACY_REVIEW.md) covers the earlier work in
this chat as well: core validators, GPS acceptance, distance calculations,
tracking constants and source data remain intact. This adds one small local
lookup for an unresolved four-fix window, with no new cache or schema. It
requires an Android build; no additional gateway deployment is needed.

## Reuse validated cache coverage for eligible work

A provider window may include deferred unmatched fixes as read-only context.
Previously, an exact cached response covering every eligible fix could still
fail the completeness check because it did not resolve that deferred context.
During primary cooldown this could spend a backup request on work already
validated by the primary; the service could also delay completing that work.

Both completeness checks now use the explicit eligible IDs within the actual
provider slice. They still require nonempty unmatched eligible work, an accepted
road and accepted point confidence for every eligible fix. Deferred context is
never completed by this change. Cache payload equality, expiry, reparsing with
current IDs, provider precedence and all geometry validation remain unchanged.
There is no new cache, query, retained state, batching delay or provider input.

The SDK 31/37 local HTTP fixture obtains a real parsed Geoapify response for two
fixes with a third unresolved context fix. With only those two fixes eligible
and the primary cooling down, extra backup requests fall from one to zero;
total fixture HTTP requests fall from two to one, including cache preparation.
The real tracking service completes only the two validated fixes during its
network gates, preserving the third fix's exact deferral, every GPS record,
distance and network-attempt clock. If that third fix becomes eligible, or the
trace changes, the existing remote path is still required.

This is conditional removal of duplicate work when such an exact cached answer
already exists. It does not establish a fleet frequency, a new daily-hour/driver
capacity figure or physical-drive accuracy parity. It requires an Android build
only; provider parsers, quality thresholds and server quota accounting are unchanged.

All **423 focused tests across 14 suites** pass with no failures, errors or skips,
including twelve new SDK-expanded cases. Android lint and the debug build pass.
Fourteen source invariants still match the chat's starting commit, including the
complete provider parsers, validator, distance calculator and database schema.

## Broader resource review

[Eight additional server/storage/phone improvements](RESOURCE_REVIEW.md) cover
tile-accounting cache efficiency, boundary refresh eviction, unchanged profile
writes, exploration-cache turnover, obsolete overlay work, retired cache files,
export/restore allocations and explicit-deletion database compaction. The review
separates measured work reduction from unmeasured battery/fleet effects and
explains the one-time compaction tradeoff.
