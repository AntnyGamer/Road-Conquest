# Server efficiency — October 16, 2026

These changes reduce repeated database work while preserving authentication,
anti-abuse limits, notification expiry/binding, privacy filtering and scoring.
No schema migration, provider-cap increase, added service, paid plan or Android
change is required.

## Database sleep and public requests

Creating the application pool opens no connection. Liveness, privacy/account-
deletion pages and competition availability work without a reachable database,
including on a cold start and with schema auto-apply enabled. The deep `/health`
endpoint remains a database readiness check.

The first real database operation shares a lazy initialization/cleanup promise
with concurrent requests. Successful cleanup is checked again by active requests
after fifteen minutes. There is no background timer to wake an idle database.
Initialization or cleanup failures retry on the next real request. Session and
device expiry, evidence freshness, foreign keys and explicit deletion retain
their existing checks; physical cleanup after an idle interval happens when
database activity resumes. No periodic job is added.

## Notification registration

An ordinary refresh now keeps the same device row and updates its encrypted FID,
binding and exact refresh time. A changed FID still removes the session's old
registration, and an existing FID transfers to its current valid session. Invalid
or expired sessions cannot remove another device. Logout/account deletion still
cascades registrations. No refresh interval or delivery preference changes.

Session validation and conditional removal share one SQL statement. One refresh
uses four statements including transaction boundaries, previously five; the
data statements fall from three to two. Repeated registrations remain encrypted
with fresh ciphertext and preserve the full thirty-day freshness window.

## Public leaderboard

Privacy/eligibility filters and the existing score/username ordering select the
top fifty before computing `RANK`. Rows below that cutoff cannot change any
returned rank: they have lower or equal scores. The boundary-tie regression test
checks this against all eligible scores, including hidden/ineligible high scores.
Friend rankings, totals, scoring, pagination length and tie ordering do not change.

## Before/after measurements

Compared with commit `d041ac7`, on the same local PostgreSQL fixture and desktop:
10,000 ranked accounts, one million stored cell rows, pool maximum five. Requests
were interleaved before/after to reduce timing bias; Google/FCM calls were mocked.
These are local measurements, not production latency or DAU guarantees.

| Check | Before | After |
|---|---:|---:|
| Cold public/liveness initialization SQL | 7 queries | 0 queries |
| Idle cleanup timers | 1 | 0 |
| 500 notification refreshes, SQL statements | 2,500 | 2,000 |
| Median database WAL bytes for 500 refreshes, three rounds | 377,624 | 324,000 |
| Ranking-stage rows, 10,000 tied accounts | 10,000 | 50 |
| Tied leaderboard p95, 160 interleaved samples | 10.14 ms | 6.39 ms |
| Varied-score leaderboard p95 | 1.008 ms | 1.003 ms |

Notification WAL decreased about 14%; tied leaderboard p95 decreased about 37%.
Varied-score latency was comparable (median 0.553 to 0.591 ms, p95 unchanged).
No claim of a general 37% API speedup or higher provider allowance follows.

All 62 server tests passed against isolated PostgreSQL, with no failures/skips.
Coverage includes offline cold public responses, shared maintenance/recovery,
expiry/deletion races, notification refresh/replacement/session transfer,
rank ties across the fifty-row cutoff, auth/privacy and existing map budgets.

Cloudflare's native Worker cache is already enabled for public map responses;
matching/errors remain `no-store`. No duplicate cache or stale ranking cache is
introduced. Google/Geoapify/LocationIQ limits and the remaining Nominatim/country-
population issues in the capacity review are separate from these optimizations.

## Deeper query review

A second pass compares with `a334910` and removes more repeated database work:

- Authentication for routes other than `/v1/me` selects only the session's user
  ID. The immediate foreign key and account-deletion cascade enforce the same
  ownership relationship. `/v1/me` still joins the current profile. Both paths
  enforce expiry on every request, and reuse the already-computed session hash
  locally instead of returning it from PostgreSQL. No identity/consent cache.
- New friend requests check existence, both fifty-relationship caps and insert
  in one statement, while holding both user locks in the same stable order.
  Existing/reciprocal requests remain idempotent at the cap, acceptance still
  requires consent, and only a newly inserted request triggers notification.
- Verified batches select explicit challenge columns, return one count of newly
  inserted cells instead of a row per cell, and combine nonzero total/checkpoint
  and weekly updates. A zero-credit batch returns no inserted-cell row and uses
  the existing direct total/checkpoint update without touching weekly storage.
  The user lock, Integrity checks, continuity checks and receipt transaction stay
  intact. The week is still derived from the final fix in UTC. Competition is
  still disabled by default; this change does not enable it.

| Second-pass measurement | Before | After |
|---|---:|---:|
| Identity-only auth, PostgreSQL response bytes/query | 266 | 73 |
| Profile auth, PostgreSQL response bytes/query | 266 | 165 |
| Warm identity lookup, shared-buffer hits | 6 | 3 |
| New friend request transaction, SQL statements | 6 | 4 |
| 500 friend transactions, PostgreSQL response bytes | 199,179 | 142,286 |
| New-credit verified batch, SQL statements | 13 | 12 |
| 200 starts + 200 new + 200 zero-credit + 200 exact retries, statements | 5,600 | 5,400 |
| Same mixed verified work, median response bytes over three rounds | 641,134 | 617,524 |

The auth queries returned about 73%/38% fewer bytes; friend transactions used 33%
fewer statements and about 29% fewer response bytes. Mixed verified work saved
about 3.6% of statements and 3.7% of response bytes. These are path-specific
results, not whole-app savings or an increase in external provider allowances.
Friend counts exclude HTTP authentication/throttling and notification delivery.
Verified counts include transaction boundaries and mocked Integrity verification.

Bytes were measured at established local PostgreSQL TCP streams, including result
metadata and command tags, excluding TLS, HTTP and connection handshakes. Fixtures
used the same 10,000-account synthetic database; calls were interleaved before/
after, with three mutation rounds, and the pool stayed at five. Desktop timing
varies: the final standalone run's median 500-friend duration fell 176.84 to
148.35 ms, while one scoring round was slightly slower. We claim reduced query
and transfer work, not a guaranteed production latency percentage.

All **64 server tests passed**, including concurrent cap enforcement, exactly one
notification for repeated requests, expiry/logout/account-deletion on both auth
paths, and a forced weekly-write failure that rolls back cells, totals,
checkpoint, drive sequence and receipt together before a successful exact retry.

The wider review also inspected matching retries/known-road reuse, provider
cooldowns and budgets, Cloudflare/KV caching, boundary/population caches,
notification refresh/delivery, account/session cleanup and OAuth token reuse.
Longer notification refresh intervals, stale account/ranking caches, bigger map
batches/allowances, new indexes and data-source replacements were not required
for these changes: each changes behavior, quotas or write/storage costs. Existing
cache and coalescing mechanisms were retained.

The combined writes use PostgreSQL's documented [data-modifying CTE semantics](https://www.postgresql.org/docs/current/queries-with.html#QUERIES-WITH-MODIFYING):
`RETURNING` communicates inserted/updated results within the statement. Unrelated
expiry-table cleanup remains separate to preserve its existing lock ordering.
