# Follow-up optimization review

Reviewed the new forum, billing, GIF loading, tutorial and map tests, plus matching, local SQL,
map-budget bookkeeping, account APIs, maintenance and common collection pipelines. Kept the 19
groups below; did not reach 50 without speculative caching, behavioral changes or less clear code.
These remove redundant work. They do not change quotas, cooldowns, expiry, matching thresholds,
GPS samples, provider order, road geometry, moderation rules or public response shapes.

| # | Area | Removed work | Preserved behavior |
| --- | --- | --- | --- |
| 1 | GIF fingerprints | Per-load regex construction and 32 per-byte formatting calls | Exact lowercase, 64-character SHA-256 validation; compare the same digest as bytes |
| 2 | Play purchase restoration | Filtered purchase list and separate product-presence scan | Same purchased/pending callbacks, order and missing-purchase feedback |
| 3 | Forum page merge | Temporary filtered-post list | Same UUID deduplication and insertion order, including repeated pages |
| 4 | Recycled GIF views | Clearing the same view twice before loading | Old animation and download ticket are cleared once before the replacement |
| 5 | Reaction buttons | Per-row list, triples and repeated map/selection reads | Same counts, selection, labels, accessibility descriptions and API actions |
| 6 | Forum status | Resetting identical text on every control redraw | Changed statuses still appear; unchanged statuses avoid layout/accessibility churn |
| 7 | Nearby tile columns | Mapped list and distinct-result list around a temporary set | Same unique columns in first-seen order, including date-line neighborhoods |
| 8 | Matching-window eligibility | Filtered point list before collecting IDs | Same ordered set of unmatched IDs; bridged matched points remain read-only context |
| 9 | Fallback matching eligibility | Two duplicated filter/map pipelines | Shared single-pass ID collection for cached and network entry points |
| 10 | Local SQL arguments | Intermediate mapped lists, copied arrays and a spread copy at four call sites | Same argument count, values and order for anchors, known visits, chunks and conditional road updates |
| 11 | Forum profile reads | Selecting unused `rules_version` | Acceptance remains stored and updated; penalties and entitlements use the same fields |
| 12 | Map-budget metadata | Returning redundant health keys and permit IDs; storing the ID again in its keyed cache | Same indexed lookups, limits, reservations, dispatches, refunds, health and recovery state |
| 13 | Outstanding-post lookup | Copying all pending keys into an array and rebuilding the account prefix | Same per-account in-flight rejection and global bound |
| 14 | Refund notification parsing | Rechecking a token type already validated in the same parsed payload | Same token hashing, ownership verification and refund handling |
| 15 | GPS adjacency checks | Four temporary lists of neighboring pairs | Same empty/single-point behavior, order, duplicate-time, ID, gap and continuity checks |
| 16 | Settings choices | Intermediate label lists before creating the two dialog arrays | Same enum order, labels, selected item and click actions |
| 17 | Weekly reward lookup | Intermediate key list before creating SQL arguments | Same four reward IDs, bindings, ownership lookup and payouts |
| 18 | GIF URL ports | A temporary two-element list and boxed port values on each check | Exactly the same default/443 allowlist and all other URL restrictions |
| 19 | GIF moderation sheets | Recomputing identical scale and thumbnail dimensions for every frame | Byte-identical PNG contact sheets, including disposal, transparency, all frames and media bounds |

The requested camera-movement assertion and its longitude polling were removed. The actual pan,
render synchronization, fog-pixel checks and other map/tour tests remain.

The same review also fixed correctness issues: malformed Play replies could revoke Plus; rejected
cached credentials could keep verification unavailable; acknowledgement cleanup could delay/fail a
successful grant; direct new purchases could bypass the banned-account UI guard; interrupted GIFs
and contradictory moderation replies were not consistently classified as retryable outages; long
normalized media URLs could exceed storage bounds; large moderator queues could exceed the app's
response limit; and periodic redraws could hide a failed forum refresh.

Validation uses the existing matching/database/map tests, new forum status and binary-hash
regressions, provider fault-injection tests, quota/recovery/cancellation tests and PostgreSQL CI.
No phone battery percentage, fleet capacity or billing reduction is claimed from these edits.

The separate [accuracy follow-up](server/maps/ACCURACY_REVIEW.md#repeated-junction-vertices)
fixes dropped turn waypoints on repeated provider vertices. It preserves valid approach/exit
sections, rejects out-of-range junction evidence and avoids crediting empty junction geometry.
The [date-line follow-up](server/maps/ACCURACY_REVIEW.md#equivalent-original-longitudes-at-the-date-line)
also accepts equivalent +/-180-degree representations of an original fix. Neither relaxes
matching thresholds, and neither is counted as an optimization group.

The last two optimization checks compare all 65,537 valid/default URI port values and 42
complete contact-sheet/error results, including all disposal modes, transparency, aspect ratios
and the decoded-pixel bound. They preserve all results; the server sheets compare byte for byte.

The subsequent full-PR review also fixes live Plus controls in an open composer while keeping
its draft and rules agreement, preserves a valid Play price through restore/payment feedback,
clears stale checking/restore state, and rejects an empty GIF fragment consistently with Android.
No new dependency, server request or matching change is needed for these corrections.

The map-budget comparison was repeated against the PR's actual starting commit
`146b80cb56907ad2012f91b6fc9e8d8fc100dc9f`: all 2,209 complete responses and all three stored
tables remain identical through restart, eviction, day rollover, dispatch, cancellation,
success, outage and recovery. The 17 targeted SQL/provider checks also pass again.
