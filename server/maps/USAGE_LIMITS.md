# Driving allowance audit — October 19, 2026

The first capacity target is **whole paid matching work avoided**. Reducing SQL
reads or HMAC operations helps hosting headroom, but cannot multiply provider
credits. Ordinary personal driving keeps GPS/history on the phone and uses the
maps gateway; optional verified competition has separate limits.

## Limits most likely to matter

| Resource | Published free allowance | Current application guard / effect |
|---|---|---|
| Geoapify | 3,000 credits/day; matching costs one credit per 100 waypoints; raster tiles cost 0.25 credit each | Shared 2,400-credit hard cap; 600 credits/client IP; live batches contain at most 100 total fixes |
| LocationIQ | 5,000 requests/day, 60/minute and 2/second; routing accepts at most 25 coordinate pairs/request | 4,000/day, 1,000/client IP, 1,050-ms shared spacing; 25-fix backup batches |
| Cloudflare Durable Objects | 100,000 requests, 100,000 SQLite rows written and five million rows read/day | Healthy matching needs two internal calls; full hard-cap fixture previously used 12,800 calls, with 52,860 writes including next-day cleanup |
| Google Play Integrity, if verified competition is enabled | Default 10,000 server token decryptions/day, separately from its token/preparation bucket | At 40 new verified submissions/hour, this alone is about 250 verified hours/day before other use; normal Android builds currently hide/disable competition |

Sources checked October 19: [Geoapify credits](https://www.geoapify.com/pricing-details/),
[LocationIQ plans](https://locationiq.com/pricing),
[routing coordinate limit](https://docs.locationiq.com/docs/routing-api),
[Cloudflare allowances](https://developers.cloudflare.com/durable-objects/platform/pricing/),
[Play Integrity limits](https://developer.android.com/google/play/integrity/setup#increase-daily).
Published allowances are not a readout of the project's actual remaining usage.
Other applications/services using the same accounts consume their allowances.

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

## Reproducible hours and daily-driver planning

Run `node server/tools/driving-capacity.mjs` from the repository root. Options
include `--fixes-per-hour`, `--hours-per-user`, `--local-share`, `--tile-misses`,
`--repair-credits`, `--repair-backup-calls` and `--budget-fraction`.

The default retains the existing reference model: 900 retained fixes/hour,
75-minute fragments, three context slots, startup/final partial requests,
10.4 primary credits/online hour and 69.6 backup calls/online hour. It plans with
80% of the application's hard caps, leaving further operational headroom.

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
