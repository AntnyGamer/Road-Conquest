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
