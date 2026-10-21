# Additional quota savings — October 19, 2026

This pass compares with `da9bae5` and targets external verification calls and
Cloudflare SQL allowance consumption. It does not raise daily caps or change
GPS capture, road matching, geometry, driving distance or map detail.

## Concurrent duplicate verified submissions

Previously, simultaneous copies of the same uncommitted batch could all pass
the receipt preflight and each request a Google Play Integrity decode. The
existing receipt still prevented duplicate credit, but it was committed too
late to avoid those duplicate Google calls.

Share only an in-flight verification for the exact authenticated user, evidence
hash and integrity token. Keep at most 64 entries per competition instance;
additional distinct work follows the original verification path. Remove each
entry on success or failure. This retains no completed verdict cache, and a new
token, changed evidence or different user cannot reuse another check.

Every caller still passes its own eligibility, receipt, GPS freshness and active
challenge preflight. After verification, each caller still takes the original
user lock, checks current eligibility/receipts/challenge, verifies continuity and
commits the same atomic score transaction. HTTP rate reservations remain per
request. The verifier itself and every Google verdict check are unchanged.

A real isolated PostgreSQL fixture runs 20 simultaneous copies through the
actual competition service and Integrity verifier, with local OAuth/Google
responses. **20 decode requests before become one after**, with one OAuth call,
one receipt, one sequence increment, one credited cell and equal responses.
Another fixture revokes eligibility while the shared verification is pending:
all 20 callers fail, with no cell, receipt or sequence credit. Separate tests
cover distinct users/evidence/tokens, retry after failure, no completed cache,
the 64-entry bound and ordinary overflow behavior.

Google documents [request-hash binding and automatic replay protection](https://developer.android.com/google/play/integrity/standard).
This shares one pending check for the same operation; it does not reuse a
verdict to authorize another batch. Savings require overlapping duplicates in
the same server instance. Ordinary unique submissions still need one decode
each. Verified competition remains disabled in ordinary Android builds, so this
does not increase normal personal-driving capacity or the unique verified-hours
ceiling by itself.

## Compact daily counter storage

The `usage` table now uses SQLite `WITHOUT ROWID`, as the permit/health tables
already do. Its short composite text primary key directly stores each counter,
avoiding the separate rowid table/primary-key index. All counter columns, values,
legacy whole-credit balances, SQL reservation logic, refunds, attempt ceilings,
replay protection, pacing, provider health and daily/client caps remain.

New empty databases use the compact layout immediately. An existing legacy table
keeps today's balances and layout. Only when normal daily retention has left
that table completely empty may the existing synchronous reservation transaction
replace it. No live balances are copied or reset. A failed commit restores the
old schema, deleted expired rows and reservation together. Unexpected unexpired
or null-key legacy rows prevent conversion and remain intact. Previous code can
still read and enforce the upgraded table's counters.

The upgrade adds an empty-table check during daily cleanup, and a primary-key
metadata check only when the table is empty. It introduces no per-request layout
scan or cleanup timer. A one-time empty-table replacement is the only upgrade
write cost. SQLite describes the suitable small composite-key use case and
differences in [WITHOUT ROWID](https://www.sqlite.org/withoutrowid.html).

The committed [workerd audit tool](tools/check-quota-runtime.mjs) uses local
Miniflare 5.20261012.1-beta. Two objects reserve/dispatch 2,400 primary and 4,000
backup matches across 200 client buckets per provider, then exercise next-day
cleanup, rollback, replay and restart. A second pair tests repeat-client caps.
Every response, denial and logical durable-state digest matches the baseline.
A migration fixture also verifies live legacy balances, atomic cleanup rollback
and reads by the previous implementation.

| Local workerd counter: 6,400 matches | Before | After |
|---|---:|---:|
| First-day SQL rows read | 68,814 | 37,622 |
| First-day SQL rows written | 45,624 | 44,814 |
| First-day database bytes, both objects | 1,220,608 | 1,126,400 |
| Rows read including next-day cleanup/two new matches | 76,040 | 44,850 |
| Rows written including next-day cleanup/two new matches | 52,860 | 52,038 |

That is about 45% fewer first-day SQL reads, 1.8% fewer first-day writes and
7.7% less database storage in this fixture. It changes no provider credit or
internal budget-request count. Cloudflare publishes daily Free allowances of
five million SQL rows read and 100,000 written, with deletes counted as writes:
[Durable Objects pricing](https://developers.cloudflare.com/durable-objects/platform/pricing/).
These are local runtime counters, not invoices or production throughput. Saved
SQL allowance does not multiply the Geoapify/LocationIQ matching allowance.

Reproduce with an installed Miniflare module via `MINIFLARE_MODULE`, then run
`node server/tools/check-quota-runtime.mjs` from the repository root. The tool
needs the baseline Git commit in local history. It uses no production database
or map-provider traffic. Results are in [quota-runtime-evidence.json](quota-runtime-evidence.json).

## Validation and boundaries

All 133 server tests pass, with zero failures or skips, using isolated local
PostgreSQL where required. The account Function bundle and Wrangler 4.151.1 maps
dry run pass; no code is uploaded. Eight new quota cases cover duplicate calls,
separation, bounds, moderation and layout upgrades. The duplicate-Google fixture
first failed against the baseline with 20 calls. Migration tests specifically
verify data preservation and rollback, beyond checking that the new schema exists.

Six source comparisons against `da9bae5` confirm unchanged complete gateway,
switches, Integrity verifier and GPS/verdict validators, unchanged budget logic
after maintenance, and unchanged submission logic except its single call through
the scoped pending verifier. The 14 original driving/matching invariants and 12
additional phone/resource comparisons still pass. Android source has not changed
since the full 1,094-test/lint/debug-build run recorded in the resource review.
Native and release CI status is tracked separately in the PR.

The table upgrade changes a storage layout; the verifier shares bounded pending
work. Those implementation costs are bounded and have no intended user-visible
behavior change. Synthetic tests do not prove zero performance cost in every
workload, physical-drive accuracy parity or a new DAU/driving-hours limit. No
production deployment, version bump, quota increase or paid service is included.
