# Community forum

The Android app has a public, paged community feed from the map's Forum button and Settings.
Reading needs no account. Posting, reactions, reporting and blocking use the existing account.
It never publishes GPS history automatically and does not poll in the background.

## Posting and moderation

Users accept the rules before submitting. Text and a contact sheet containing every GIF frame
are checked with OpenAI's moderation endpoint before publication. High-confidence harmful
categories trigger removal: first violation deletes the post, second also mutes forum participation
for seven days, third bans forum participation. Driving, account access and public reading remain
available. Ordinary road/crash discussion and distress alone do not trigger the ladder.
Automation cannot catch every violation; reports go to the configured human moderators.
Moderators can keep/remove reported posts and clear penalties after reviewing an appeal.

One submission per account every **10 minutes**, including Plus and removed submissions.
Successful submissions record the cooldown in PostgreSQL; deletion, reinstall and process restart
do not bypass it. Account locks check it again when publishing, including across server instances.
The client shows remaining minutes. A submission UUID makes network retries idempotent: one post,
one penalty, one cooldown. Moderation outages consume neither a strike nor a cooldown.
Malformed inputs and cooldown rejections never reach the moderation API. One outstanding post per
account per instance and a global cap bound expensive work; existing persistent request limits remain.

Users can delete their posts, report posts, and hide/unhide authors. Reports alone never punish
an account. Removal erases post text and GIF references while retaining the submission fingerprint
for safe retries. Account deletion cascades forum content, reactions, reports, blocks and entitlements.
Appeals use the project's GitHub help page; include a username and post ID, never private GPS data.

## Forum Plus — one-time US$0.49

Create non-consumable Play product **`forum_plus_lifetime`** for `com.roadconquest.app` at US$0.49,
with localized prices and an eligible buy offer. The app displays the actual Google Play price
before checkout; no hard-coded price can disagree with the store. Benefits: a subtle accent border
and direct Giphy/Tenor GIF links (no search or upload feature). Rules, cooldown and ads are unchanged.
GIFs are limited to 2 MB, 512 × 512, 60 frames, and eight million decoded frame pixels.
Only exact HTTPS CDN hosts on port 443 are accepted; redirects are rejected. Clients verify the
SHA-256 of the exact moderated bytes before displaying media; no account token goes to the CDN.

The server verifies purchase state, product, unconsumed status and the account binding with Google,
then acknowledges purchased payments. Pending/cancelled payments grant nothing. Tokens are encrypted
at rest using a separately scoped key derived from PASSWORD_PEPPER; token fingerprints enforce
ownership. Keep that pepper stable. Restore uses the same Road Conquest and Google Play accounts.
Deleting the Road Conquest account removes its Forum Plus access, as disclosed in purchase/deletion UI.
Google signed Pub/Sub notifications cause authoritative Play checks for cancellations and voided
purchases; duplicate notification IDs are retained for 30 days. Verification and refund updates are
serialized per account so an older verification response cannot undo a refund.

## Server setup before enabling

No live resources or Play products are created by this PR. The forum defaults off. Configure in an
isolated test deployment first, apply `server/forum-schema.sql` with a migration role, and keep normal
runtime schema modifications disabled (`APPLY_SCHEMA_ON_STARTUP=0`). The migration grants the existing
`roadconquest_runtime` role access only to the forum tables. Existing cloud infrastructure is reused.

| Setting | Purpose |
| --- | --- |
| `FORUM_ENABLED=1` | Enable community routes and forum modules |
| `FORUM_MODERATION_API_KEY` | Server-only OpenAI moderation credential; without it posting stays unavailable |
| `FORUM_MODERATOR_USER_IDS` | Comma-separated existing numeric account IDs allowed to review reports/appeals |
| `FORUM_PLAY_SERVICE_ACCOUNT_JSON` | Server-only Play Developer API service account with purchase access |
| `FORUM_RTDN_AUDIENCE` | Exact OIDC audience configured on the authenticated Pub/Sub push subscription |
| `FORUM_RTDN_SERVICE_ACCOUNT_EMAIL` | Exact trusted push identity email |

Configure Play real-time developer notifications for one-time products and refunds. Point an
**authenticated, wrapped** Pub/Sub push subscription at `/v1/forum/play-notifications`; authorize the
Google Play notifications publisher on the topic and the Pub/Sub identity token creator as described
in Google's documentation. Both the purchase verifier and notification identity must be configured
before the app offers checkout. A configuration value alone does not prove delivery: send a Console
test notification, then test license-tester purchase, pending, restore and refund end to end.
Keep the feature off until terms/privacy disclosures and moderation/report handling are ready.
There is no live payment or moderation test without credentials; service tests use mock providers.

Server tests use the existing isolated PostgreSQL CI service. For local SQL checks without a cloud
database, optionally install PGlite outside the repo and set `FORUM_PGLITE_MODULE` to its entry module.
CI's real PostgreSQL connections also exercise row-lock concurrency. Run `npm test` in `server`, plus
Android `ForumTest`, `AppearanceTest`, `MapLayoutTest`, and the existing tutorial suite.

## Usage and costs

Feed pages contain at most 20 posts. One query fetches the page, reaction counts, author highlights
and viewer state. Keyset pagination avoids growing offsets; indexes cover the visible feed and
account-owned records. GIF bytes stay on the CDN, never in PostgreSQL. The client caches at most four
2 MB GIFs, loads with two workers and pauses animation when backgrounded/recycled. Play OAuth and
signing keys are cached; duplicate notifications skip the Play request. The new forum inherently
adds database and moderation traffic; these bounds reduce it without weakening moderation or limits.

References: [OpenAI moderation](https://platform.openai.com/docs/guides/moderation),
[Play purchase verification](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.products/get),
[RTDN](https://developer.android.com/google/play/billing/rtdn-reference),
[Pub/Sub identity verification](https://cloud.google.com/pubsub/docs/authenticate-push-subscriptions).
