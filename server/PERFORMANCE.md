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
