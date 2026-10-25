# Server usage audit, October 25, 2026

This pass starts at PR #119 commit `f034a94`. It inventories network entry points
throughout the Android app, all server modules, both Workers, database schemas,
service configuration, assets and workflows. UI/local-only code was checked for
call sites rather than treated as an independent source of remote usage.

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

Local server suite: 139 passing, 26 skipped because a native PostgreSQL service
is unavailable; the forum tests use actual PostgreSQL SQL via local PGlite.
The added no-op appeal case brings the forum-only run to 13/13 passing. CI uses
native PostgreSQL and performs Android unit tests, lint and builds.
Local Gradle execution could not download its distribution through the Java
network stack in this environment; full Android results must be read from CI.
No live map-provider requests or Google purchase/moderation traffic were used.

References: [Workers caching](https://developers.cloudflare.com/workers/cache/),
[Geoapify open tile policy](https://www.geoapify.com/map-tiles/).
