# Road Conquest — Cloudflare Pages (static website)

The website, Privacy Policy, Terms of Service, icon, and AdMob seller file are independent static assets in `docs/`. These files are ready to be deployed by **Cloudflare Pages** using the **existing** `AntnyGamer/Road-Conquest` GitHub repository. No second repository, domain purchase, account migration, or additional Android backend service is required.

## Cloudflare Pages project settings

In the existing Cloudflare account, go to **Workers & Pages → Create application → Pages → Import an existing Git repository**.

| Field | Value |
| --- | --- |
| Project name | `roadconquest` (if available) |
| GitHub repository | `AntnyGamer/Road-Conquest` |
| Production branch | `main` after PR #138 is merged; before merging, the staging branch is `site/road-conquest-pages` |
| Framework preset | None |
| Root directory | `/` (repository root) |
| Build command | `exit 0` (or leave blank) |
| Build output directory | `docs` |

Deploy the project. The intended address is `https://roadconquest.pages.dev/` **if Cloudflare assigns that project name**; the actual address must be confirmed in the Cloudflare dashboard.

The static site includes:
- `index.html` — Road Conquest homepage, app icon, Google Play link
- `privacy.html` — full Privacy Policy
- `terms.html` — full Terms of Service
- `app-ads.txt` — exact AdMob seller line
- `favicon.svg` — icon traced from the app's own launcher vector
- `_redirects` — /privacy and /terms aliases
- `_headers`, `robots.txt`, `.nojekyll` — static-hosting metadata

## Only switch after verification

Verify that the actual `pages.dev` hostname serves the homepage, `/privacy.html`, `/terms.html` and `/app-ads.txt` over HTTPS with HTTP 200. Then update the Play Console **developer website** to the new homepage and **Privacy Policy** to `/privacy.html`. Once verified, update Android Settings' web and legal links for new app versions.

The existing **`roadconquest-api.22abongiovanni.workers.dev` account API** and **`roadconquest-maps.22abongiovanni.workers.dev` map service** must remain unchanged indefinitely to preserve older installed app versions. Account deletion continues to link to the existing backend because it is a functional service, not a static page.

Do **not** disable the current Cloudflare Worker homepage, existing public legal routes, or the backup Render website until the new pages.dev site, Play listing, and AdMob verification are confirmed.

A `pages.dev` subdomain may still not satisfy Liftoff Monetize's root-domain requirements. The completed setup does not guarantee Liftoff verification.

Do not put API secrets into Pages build variables; this is a static site and needs none.
