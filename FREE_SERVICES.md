# Free operations services

UptimeRobot, Cloudflare Workers KV and Firebase Cloud Messaging supplement the
existing services on PR #95. App Distribution is not enabled. No payment method,
paid scheduler, Cloud Functions, Analytics or automatic paid upgrade is required.

## UptimeRobot

The [public service status page](https://stats.uptimerobot.com/ZT185qP4jc) shows
both monitors. Checks run every five minutes with email alerts to the owner.
The map monitor sends **HEAD** to `/health`. The account monitor uses a free
keyword GET to `/health/live`, expecting `"ok":true`, since the existing account
Worker does not support HEAD. These are liveness checks: they do not spend a map credit
or query PostgreSQL. The separate account `/health` is a deeper database check;
avoid frequent external checks that keep Neon compute awake.

Free allowance: 50 monitors, five-minute interval. It supports commercial use.
https://help.uptimerobot.com/en/articles/11604710-who-should-use-uptimerobot-s-free-plan

## Cloudflare provider controls

The `SERVICE_CONFIG` KV binding stores `providers-v1`. The checked-in
`server/maps/providers-v1.json` is the all-enabled starting configuration.
Change only known booleans, keeping `version: 1`. Unknown properties, URLs,
quota overrides and verification-policy fields are ignored.

```sh
npx wrangler kv key put providers-v1 --path server/maps/providers-v1.json --binding SERVICE_CONFIG --remote --config server/maps/wrangler.jsonc
```

Supported switches: `openfreemap`, `esri-imagery`, `geoapify-tiles`,
`geoapify-matching`, `locationiq-matching`. The gateway checks matching and tile
switches before reserving credits or contacting a provider. A disabled matching
provider returns 503/Retry-After, allowing the existing client to use its next
provider. Valid ambiguous/NoMatch results still never trigger provider shopping.

`GET /v1/config` exposes only these public switches. Workers coalesce and cache KV
reads for one minute, with a five-minute KV cache; changes are eventually
consistent. Android refreshes asynchronously at startup/map creation at most
every six hours; failures retry after 15 minutes. Its last configuration expires
after 24 hours. Offline defaults enable the existing provider chain and preserve
saved progress. Server-side hard daily caps remain authoritative even if KV is
missing, malformed or unavailable. OpenFreeMap/Esri switches require the updated
app and take effect on its next config refresh; matching switches work on older
gateway clients too. No public write endpoint exists.

KV Free: 100,000 reads/day, 1,000 writes/day, 1 GB storage. Quota exhaustion fails
operations on Workers Free instead of adding usage charges.
https://developers.cloudflare.com/kv/platform/pricing/

## Optional notifications

Settings has separate friend-request and weekly-reminder switches, both on by
default for new and existing users without a saved choice. Explicit opt-outs stay
off, including across logout and sign-in. Android notification permission and an account are required for friend
pushes; weekly reminders need only notification permission. Weekly reminders use
an inexact local Monday 18:00 alarm, rescheduled after reboot/app update, with no
exact-alarm permission or background location work.

Firebase initialization requires friend notifications to be enabled with sign-in
and Android notification permission, or existing independent Crashlytics consent.
Messaging auto-init remains false; startup, sign-in and permission changes explicitly
call `register()` when eligible, coalescing repeated foreground registration for five minutes. The app uses the current
`onRegistered` callback and HTTP v1 `fid` targeting instead of deprecated tokens. Firebase's
automatic init provider stays removed. Enabling notifications does not enable
Crashlytics reporting. No Firebase Analytics dependency is included.

The existing account backend uses FCM HTTP v1 with a dedicated service account
and a custom role containing only `cloudmessaging.messages.create`. Store its JSON
as the private function variable `FCM_SERVICE_ACCOUNT_JSON`, never in the APK,
Git, logs or a public Worker binding. OAuth tokens are scoped to Firebase
messaging, cached briefly and never returned to clients. This reuses the existing
Neon Function rather than provisioning Firebase Cloud Functions or Blaze billing.

Apply `server/migrations/20261016_notifications.sql` before deploying. It adds
only `notification_devices`, its expiry index and table grants to the existing
`roadconquest_runtime` role. For a differently named runtime role, grant
SELECT/INSERT/UPDATE/DELETE on this table; no schema or additional session write
permission is needed. Test migrations on a Neon branch before production.

Authenticated POST `/v1/notifications/device` takes `{fid,binding}`; DELETE
revokes only that login session's registration. A new registration replaces that
session's previous token and transfers any matching device token to the current
session. Installation IDs are padded and AES-256-GCM encrypted with a domain-separated key derived
from the existing server pepper; SHA-256 digests support deduplication. Foreign
keys remove registrations on session logout/expiry and account deletion. Stale
registrations expire after 30 days. At most five recent devices per account
receive an event; refresh occurs on app startup/registration renewal.

Only newly committed friend requests trigger generic, data-only notifications.
No usernames, GPS or account credentials are sent to Firebase. Current on-device
notification setting, permission, login and random binding gate display, including messages queued before
logout. Disabling notifications/delete-device-data revokes registration best
effort and immediately blocks local display. A persisted server rate limit caps
delivery attempts at three per recipient per five minutes. Push delivery is
best effort with no durable outbox; an outage does not roll back a friendship,
and the in-app friend inbox remains the source of truth. Pending requests remain
visible there even when a notification is delayed or lost.

FCM is no-cost. Account and Worker traffic still use their existing free quotas.
https://firebase.google.com/products/cloud-messaging
https://firebase.google.com/docs/cloud-messaging/send/v1-api

For verification use FCM `validate_only: true` with an owner-controlled device
token. Do not send a real push to other players as a deployment smoke test.

The deployed public certificate expires October 16, 2027. Rotate the notification sender credential before that date. Its private key exists only in the backend after setup cleanup.
