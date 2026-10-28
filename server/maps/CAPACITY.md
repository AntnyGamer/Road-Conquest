# Driving efficiency changes — October 17, 2026

These changes extend PR #97 without removing its map, nearby-exploration or UI
work. They reduce wasted matching allowance. They do not establish that the whole
application can already support 500 daily driving hours for free.

## What changed

| Change | What it saves or fixes |
|---|---|
| At most 100 total fixes in a live primary batch, including context | Avoids paying a second Geoapify credit for a batch just over 100 fixes |
| A full live primary window can leave sooner | Prevents the smaller batch from building a queue; startup is still immediate and sparse windows keep their seven-minute deadline |
| Complete, exact cached match checked before network gates | Uses a validated answer already on the phone, including during an outage |
| Only real network attempts move the network timer | A cached result cannot postpone new useful work by another seven minutes |
| Phone read allowance of 35 seconds | Covers the gateway's existing maximum ten-second queue plus twenty-second provider allowance and a five-second margin |
| Cancellation before and during upstream work | Stops abandoned queued work and asks in-flight work to stop |
| Durable random reservation IDs and a dispatch marker | Replaying a reservation cannot charge twice or authorize two dispatches |
| Refund only a reservation still proven unsent | Restores credits after queue cancellation or a lost reservation reply; ambiguous/dispatched work stays charged |
| Shared provider health and one leased recovery probe | Other phones need not rediscover a known outage; stale or queued work cannot bypass recovery |
| Separate, non-refundable attempt limits | Repeated cancellations cannot create unlimited reservation writes or evade the client attempt ceiling |
| Once-per-day cleanup survives object restarts | Avoids repeated full-table scans after a restart |
| Compact new bookkeeping tables | Removes an unnecessary SQLite index; lower measured writes and storage |

The backup keeps its existing 25-point limit and 52.5-second minimum cadence.
GPS retention, road-quality checks, ambiguous/NoMatch decisions, local rewards,
attribution and public-tile caching are unchanged. Smaller primary windows can
still change a provider's route inference; fixture tests cannot prove accuracy
parity on every physical drive.

Cache preflight accepts only an exact, unexpired answer that passes the current
parser and covers every pending fix in the submitted provider window. Partial
and negative answers cannot bypass a network gate. Existing normal matching-cache
and unchanged-NoMatch backoff behavior remains. Provider results cannot mark or
defer fixes that were never submitted to that provider.

## Recovery and accounting

Unadvertised provider failures get a two-second shared pause. Only one recovery
probe is admitted after that pause, with a 35-second lease. Explicit provider
Retry-After instructions are honored, up to one day. Valid empty/NoMatch and
caller-specific 400 responses do not create a shared outage. Queue-busy and
budget errors have their own retry deadlines. Provider-key rotation gives the
server a different health scope; existing phone-local cooldowns can still last
until their original deadline.

Reservations, refunds and dispatch markers update in synchronous SQLite
transactions. A duplicate internal reservation returns the original permit.
Dispatch is authorized once, before contacting the supplier. A lost dispatch
acknowledgement remains charged, even if the gateway then sends nothing. No
refund is inferred from a timeout, 5xx response, missing response or cancellation
after dispatch. App reservations are conservative; they are not supplier bills.

All provider/global/client credit caps and queue pacing remain. Additional
matching-attempt ceilings are 2,700/day globally and 600/day per client address
for Geoapify, and 4,500/day globally and 1,000/day per address for LocationIQ at
the updated default caps. These ceilings do not refund. Spent credits and admitted
attempts must both fit. Shared-health rejections do not reserve either.

No GPS trace, trace fingerprint or response body is stored in the budget object.
It stores daily counters, keyed client-address buckets, random attempt IDs, slots
and dispatch state, plus short provider-health records. Old daily records are
removed on later-day activity. One durable marker avoids repeating those scans
on every object restart; it adds one small lookup on startup. There is no cleanup timer keeping a service awake.
The privacy text describes the additional metadata.

## Actual local checks

| Test | Observed result |
|---|---|
| Compiled phone matcher, exact primary answer cached during primary cooldown | One road/two fixes resolved; zero backup calls and zero additional primary HTTP calls |
| Actual gateway plus compiled phone matcher; 9.5-second queue, 12-second mock provider | Valid result received after 21.529 seconds; earlier code timed out at 20.016 seconds |
| 200 callers one second apart; persistent mock 503 with no advertised retry | 100 supplier attempts / 200 app credits, versus 200 attempts / 400 credits previously |
| Same arrivals; mock provider recovers at second 10 | 190 successful responses; no successful post-recovery opportunity withheld in this fixture |
| Actual driving-service tests | Complete cache bypasses both gates without changing the network timestamp; a 100-fix window containing 97 pending fixes leaves at 388 seconds and retains every saved fix |
| Cloudflare Wrangler 4.151.1 dry run | Gateway bundle/configuration accepted; nothing uploaded |

These are synthetic fixtures, not production measurements. Simultaneous requests
already dispatched before an outage is known cannot be recalled. An explicit
long Retry-After can delay recovery even if the provider recovers earlier.
No blanket 60-second shared pause was installed.

SQLite accounting was also exercised inside local Cloudflare **workerd** through
Miniflare 5.20261012.1-beta. Two budget objects handled 2,400 primary and 4,000
backup matching reservations/dispatches across 200 synthetic client buckets each.
All succeeded. The test then advanced a day to exercise deletion and one new
request per object. There was no real map-provider traffic.

| Runtime counter, including next-day cleanup and two new requests | Ordinary indexed new tables | Compact new tables in this PR |
|---|---:|---:|
| SQL rows written | 59,266 | 52,860 |
| SQL rows read | 127,228 | 127,228 |
| Database size before cleanup, bytes | 1,458,176 | 1,241,088 |

That is about **11% fewer writes** and **15% less storage** in this fixture,
including the separate attempt caps. These are local runtime counters, not cloud
invoices or a throughput test. Healthy matching uses two internal budget requests
per supplier request instead of one. It adds one permit insert, one dispatch
update and two attempt-counter updates; there is no normal-success health write.
Public tiles retain their lighter path: no new health HMAC, attempt ID, attempt
counter or dispatch round trip.

Cloudflare currently publishes 100,000 Durable Object requests, 100,000 SQLite
rows written and five million rows read per day on Free. These are shared account
allowances, and deletes count as writes. The measured 52,860 is about 53% of the
write allowance; 12,800 internal requests for 6,400 healthy matches is about 13%
of the request allowance. Other traffic, errors, tiles and applications also use
these allowances. This is headroom, not a universal capacity guarantee.
[Official pricing and reset rules](https://developers.cloudflare.com/durable-objects/platform/pricing/).

## Estimated driving capacity

The historical model below retains the earlier audit's working budgets of 1,920
Geoapify credits and 3,200 LocationIQ calls/day. Those figures were selected
before the October 28 increase to 2,700/4,500 and should not be mistaken for
a capacity benchmark of the new limits. It assumes 900 retained
fixes/hour, 75-minute online fragments, three context slots, startup and a final
partial batch. Primary packing is 13 credits per fragment, or 10.4/hour. Backup
timing stays 87 calls per fragment, or 69.6/hour.

| Ordinary reference | Hours/day | Hours/week | Hours/30-day month | Hours/365-day year |
|---|---:|---:|---:|---:|
| Earlier code, all matching online | 155 | 1,085 | 4,652 | 56,600 |
| This packing change, all matching online | 231 | 1,614 | 6,918 | 84,166 |
| 55% of matching work handled locally | 512 | 3,587 | 15,373 | 187,036 |
| 60% handled locally | 576 | 4,035 | 17,294 | 210,416 |

The online estimate is `1920/10.4 + 3200/69.6 = 230.59 hours/day`, about 49%
above the earlier 155-hour reference. At 500 hours/day, about 54% of matching
work must avoid providers. This means whole paid work actually avoided, not a
percentage of miles on familiar roads. Dense sampling, short fragments, repairs,
failures and map-tile usage can lower capacity. At 1,200 retained fixes/hour the
comparable online model is about 187 hours/day, requiring about 63% local
avoidance for 500 hours/day.

**200 people averaging 2.5 hours/day remains a conditional target.** Real-drive
reuse, account-database awake time/storage, map traffic, provider burst limits
and people sharing public IP addresses still matter. Outage/cache savings are
not silently added as another capacity multiplier. No free VM, new paid provider,
database migration or increased allowance was installed.

Android changes require an app build. Gateway changes require a separate
deployment; the new tables create themselves without altering existing usage
counters. Historical counters still count toward caps. This PR does not merge,
deploy, change production data or claim physical-drive testing.
