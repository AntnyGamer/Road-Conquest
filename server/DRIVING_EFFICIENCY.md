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

No GPS, Android matching, provider quota, scoring rule, schema, service plan,
deployment or production configuration change is included. The gateway and
account-server improvements require their respective deployments to become live.
