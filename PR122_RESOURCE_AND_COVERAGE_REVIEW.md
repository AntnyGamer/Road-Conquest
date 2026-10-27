# Additional server-work and turn-coverage fixes

This follow-up to PR #122 implements the two retained server-usage recommendations
and fixes a reproduced missing-turn case. The GIF resampling experiment was
rejected because some timing fixtures regressed.

- **Map recovery metadata:** Keep the known health/probe state in the existing
  eight-entry cache after a successful reservation or probe clear. Unknown/evicted
  entries still reload from SQL, failed commits invalidate all caches, and the
  original durable writes, limits, dispatches and refunds remain. The isolated
  100-cycle outage/recovery fixture reduced health SELECTs from 301 to 101;
  37,964 response/full-state comparisons matched across seeded mixed operations,
  rollback, eviction, restarts and day rollover. Savings apply to recovery
  bookkeeping, not provider allowances.
- **Identical pending backup exports:** Share one signing operation only when
  both its bound file fingerprint and Integrity token are identical. Keep at most
  32 pending digests, clear successes/failures, verify distinct operations
  independently and use ordinary verification at capacity. Every HTTP request
  still consumes its original budget; completed verdicts/signatures are not
  cached. A six-call mocked overlap used one verifier call instead of six and all
  signatures remained valid. The small bounded pending registry is the tradeoff;
  there is no cross-instance or completed-retry saving.
- **Collapsed junction geometry:** Recover a waypoint from a step containing
  several zero-length edges only when the existing projector has no result and
  every edge is degenerate. Reuse the existing strict 3-metre position rule and
  unchanged shared validator. This repairs the approach and exit without granting
  length or credit to the collapsed junction. Real edges, GPS sampling, evidence
  retention, matching gates, retry timing and provider order are unchanged.

The new coverage regressions preserve canonical approach/exit geometry,
timestamps, confidence and road identity across repeated vertices, four latitudes,
date-line normalization and exact/sub-metre offsets. Rejected evidence preserves
independently valid sides. A seeded corpus adds 200 varied turn shapes, each
compared against three repeated-coordinate representations. The production
parser reproduced missing roads before the fix and passed the new cases after it.

The matching change is deliberately limited to recovering the previously missing
position. No stricter filter, broader snapping, additional simplification, reduced
GPS collection or removal of saved/pending evidence is included. This is tested
coverage preservation, not a claimed physical-drive accuracy percentage or a
guarantee about all possible provider/GPS failures.

Local validation uses actual production Kotlin/parser/validator/grouping sources
and the repository's server tests: 103 host matcher/turn tests passed, and the
complete local server run passed 190 cases with 28 native-PostgreSQL-only skips.
Host JVM runs omit Android test-runner
annotations and exclude Android database/render integration cases; those remain
in the committed Android CI suite. Provider calls are stubbed, server SQL checks
use local PostgreSQL WASM where supported, and native PostgreSQL-only cases run
in CI. No production database query, provider request, deployment, merge or release
is required by these changes.
