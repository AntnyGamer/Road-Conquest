# Driving allowance audit — October 19, 2026

**October 30 reliability update:** Interrupted driving uploads now return the normal private JSON error before any configuration, budget or upstream work. Oversized uploads remain HTTP 413 even when cancelling their body stream fails. Reader locks are released in every path. Limits, provider budgets and scoring are unchanged. Activating this server-side fix requires separately deploying the updated maps Worker.

The first capacity target is **whole paid matching work avoided**. Reducing SQL
reads or HMAC operations helps hosting headroom, but cannot multiply provider
credits. Ordinary personal driving keeps GPS/history on the phone and uses the
maps gateway; optional verified competition has separate limits.

**October 28 update:** The gateway now uses 90% of the providers' published free daily
allowances instead of 80%, preserving 300 Geoapify credits and 500 LocationIQ
requests as provider-level reserves. This increases admitted work but reduces
the previous margin for other uses of the same provider accounts. The historical
October 19 estimates below remain labeled with their original assumptions.

The gateway also rejects untrusted matching and fallback-tile calls before reading
GPS bodies or fetching remote provider switches. Its bounded Durable Object
counter cache now retains up to 1,024 keys instead of 512. Because each matching
client uses three per-address counters, this lets 200 warm clients reuse their
stored counters without extra SQLite reads. A regression test compares two
200-client passes; the second incurs no additional counter reads. Cold starts,
new addresses and evictions still reload durable balances, and writes, provider
requests and credit costs do not change. The larger bound uses some additional
Worker memory but cannot make the cache unbounded.

## Limits most likely to matter

| Resource | Published free allowance | Current application guard / effect |
|---|---|---|
| Geoapify | 3,000 credits/day; matching costs one credit per 100 waypoints; raster tiles cost 0.25 credit each | Shared 2,700-credit hard cap; 600 credits/client IP; live batches contain at most 100 total fixes |
| LocationIQ | 5,000 requests/day, 60/minute and 2/second; routing accepts at most 25 coordinate pairs/request | 4,500/day, 1,000/client IP, 1,050-ms shared spacing; 25-fix backup batches |
| Cloudflare Durable Objects | 100,000 requests, 100,000 SQLite rows written and five million rows read/day | Healthy matching needs two internal calls; compact-counter hard-cap fixture uses 12,800 calls for 6,400 matches, with 52,038 writes including next-day cleanup/two new matches |
| Google Play Integrity, if verified competition is enabled | Default 10,000 server token decryptions/day, separately from its token/preparation bucket | At 40 new verified submissions/hour, this alone is about 250 verified hours/day before other use; normal Android builds currently hide/disable competition |

Sources checked October 19: [Geoapify credits](https://www.geoapify.com/pricing-details/),
[LocationIQ plans](https://locationiq.com/pricing),
[routing coordinate limit](https://docs.locationiq.com/docs/routing-api),
[Cloudflare allowances](https://developers.cloudflare.com/durable-objects/platform/pricing/),
[Play Integrity limits](https://developer.android.com/google/play/integrity/setup#increase-daily).
Published allowances are not a readout of the project's actual remaining usage.
Other applications/services using the same accounts consume their allowances.
See [the region and road-matching accuracy review](ACCURACY_REVIEW.md) for validation limits.

The October quota improvements reduce first-day metered SQL
reads from 68,814 to 37,622 and writes from 45,624 to 44,814 for the same 6,400
local workerd matching operations. It also reduces 20 overlapping exact verified
submissions from 20 Google decodes to one, scoped to one authenticated operation
and server instance. No completed verdicts are cached. Unique batches still need
verification, and provider matching credits are unchanged; neither result is
silently added as another driving-hours multiplier below.

OpenFreeMap is already the normal street-map source. Public Geoapify fallback
tiles already use the configured tiered **Workers Cache**, including request
collapsing. Cloudflare serves a cache hit before executing the gateway, so a hit
already avoids the supplier request and budget reservation. Adding another tile
cache would duplicate existing savings. Matching POST responses remain private
and `no-store`. [Workers Cache behavior](https://developers.cloudflare.com/workers/cache/).

## Concrete additional saving: corroborated known highways

Precise original GPS samples can be compressed from three-second to six-second
spacing in the online queue. At about 30 m/s, consecutive retained fixes are about
180 metres apart, above the local known-road rule's 160-metre per-step limit.
Previously, even a straight, already-verified highway could therefore need a
paid lookup despite its original intermediate fixes remaining in local SQLite.

The local matcher can now also test a bounded sequence of those original fixes.
It keeps the original result first, and uses additional evidence only for still
pending IDs. It retains five actual fixes, 25-metre span, 5-metre accuracy, speed,
heading, 160-metre step and parallel-road ambiguity checks. It creates no road
geometry or new road awards. The provider window and remote cadence are unchanged.

Additional evidence must be tied to its preceding retained point during capture;
timestamps alone cannot establish which drive a fix belongs to after a clock
change. The capture associations are bounded to 512 ID pairs in memory. Restart,
eviction, unavailable associations or conflicting/duplicate evidence fall back
to the existing path. At most four original fixes can fill each proven adjacent
retained interval. No new database schema, GPS subscription or upload is required.

This adds a bounded local metadata map and a small local anchor lookup when a
compressed-out fix is saved. It trades that local work for avoided paid matching
on qualifying repeat drives. It cannot guarantee a particular fleet reuse rate,
and does not increase capacity for entirely new roads by itself.

The real service/repository regression on Android SDKs 31 and 37 resolves a
100-retained-fix known-highway window using its 99 original intermediate fixes.
The retained-only reference path invokes the online matcher once; corroborated
local evidence resolves all 100 queue IDs with zero online invocations and no
network-clock update. Both retain all 199 GPS records and the one existing road.
The online reply is stubbed, so this is request-selection evidence, not a
real-provider response or physical-drive accuracy measurement.

All 281 focused Android tests pass, covering turn retention, matching windows,
fallbacks, retries, repository history and local reuse. The 24 new SDK-expanded
cases cover source association, restarts/eviction, exact original acceptance,
missing IDs, clock changes, excessive/duplicate data, bad quality, parallel roads,
new roads and the service comparison. Android lint and the debug build pass.

A further local-only fix supplies one immediately adjacent, already-matched
approach fix when the current online window contains exactly four samples. The
unchanged five-fix acceptance rule can then prove a known-road singleton without
a paid request. No extra sample is added to the online window, and uncertain,
missing, unmatched, stale or ambiguous context still requires the original
request. A 100-fix stream fixture avoids two online matcher invocations while
preserving all 104 records and one verified road. Provider replies are stubbed;
these counts are not added as another fleet capacity multiplier.

## Reproducible hours and daily-driver planning

Run `node server/tools/driving-capacity.mjs` from the repository root. Options
include `--fixes-per-hour`, `--hours-per-user`, `--local-share`, `--tile-misses`,
`--repair-credits`, `--repair-backup-calls` and `--budget-fraction`.

The default retains the existing reference model: 900 retained fixes/hour,
75-minute fragments, three context slots, startup/final partial requests,
10.4 primary credits/online hour and 69.6 backup calls/online hour. It plans with
80% of the historical October 19 hard caps (2,400 Geoapify credits and 4,000 LocationIQ calls). The new 2,700/4,500 caps give approximately 259.4 no-reuse matching hours/day at the same 80% planning fraction, assuming the fallback can fully use its separate allowance. Run the calculator for current scenarios; neither figure represents verified production capacity.

| Whole paid work avoided locally | Matching hours/day | Drivers/day at 1 h each | Drivers/day at 2.5 h each |
|---|---:|---:|---:|
| 0% | 230.6 | 230 | 92 |
| 25% | 307.5 | 307 | 122 |
| 50% | 461.2 | 461 | 184 |
| 55% | 512.4 | 512 | 204 |
| 60% | 576.5 | 576 | 230 |

These are conditional matching-only estimates, not verified daily-active-user
capacity. No fleet reuse share is known. Familiar miles are not the same as paid
requests avoided: one unresolved fix can still require a request. New highways,
poor accuracy, parallel roads, repairs, outages, very short trips, concurrent
startup bursts, shared public IPs and other backend limits can reduce capacity.

At 1,200 retained fixes/hour, the no-local-reuse reference falls to about
187.2 hours/day. Another 1,000 uncached raster tiles consume 250 shared primary
credits, reducing the default model from 230.6 to 206.6 hours/day. Spending the
application's entire hard caps gives a matching-only arithmetic ceiling of
288.2 hours/day under the same 900-fix assumptions, with no operational reserve.
That is not a safe supported-capacity promise. Raising hard caps toward published
free allowances removes safety margin rather than making requests cheaper.

The highway change requires an Android app build. This audit does not deploy,
increase quotas, add a paid service or change production configuration.
