# Road Conquest — Cloudflare Pages

**Live website:** https://roadconquest.pages.dev/

The public website, Privacy Policy, Terms of Service, app icon and AdMob seller file are static assets in `docs/`, deployed to the Cloudflare Pages project `roadconquest` from the existing GitHub repository `AntnyGamer/Road-Conquest`.

## Pages configuration

| Setting | Value |
| --- | --- |
| Project | `roadconquest` |
| URL | `https://roadconquest.pages.dev/` |
| Repository | `AntnyGamer/Road-Conquest` |
| Production branch | `main` after PR #138 merges (initial setup used `site/road-conquest-pages`) |
| Framework | None / static |
| Root directory | `/` |
| Build command | Blank |
| Build output directory | `docs` |

## Live URLs

- Homepage: https://roadconquest.pages.dev/
- Privacy Policy: https://roadconquest.pages.dev/privacy
- Terms: https://roadconquest.pages.dev/terms
- Advertising sellers: https://roadconquest.pages.dev/app-ads.txt

Cloudflare Pages automatically canonicalizes `/privacy.html` to `/privacy` and `/terms.html` to `/terms`. **Do not create redirects in the opposite direction**: the combination caused `ERR_TOO_MANY_REDIRECTS`. The conflicting `_redirects` file was deleted and both clean and `.html` URLs were retested successfully.

`app-ads.txt` contains `google.com, pub-4489303482171969, DIRECT, f08c47fec0942fa0`. Its HTTP accessibility does **not** itself confirm AdMob or Liftoff verification; Liftoff may require a registrable root domain and a separate seller authorization.

## Legacy app compatibility

Keep both existing Cloudflare Workers and the account/backend service online:
- `https://roadconquest-api.22abongiovanni.workers.dev` (accounts, API, deletion and old legal links)
- `https://roadconquest-maps.22abongiovanni.workers.dev` (map service)

**Never rename the Cloudflare Workers account subdomain, redirect its API paths, or delete these workers**: released versions of Road Conquest already rely on them.

Android's `road_conquest_website_url` now targets the live Pages website, and `SettingsActivity` derives the Privacy and Terms links from that shared resource. This affects **new builds only**; existing installs continue using their earlier URL.

## Google Play and old hosting

Set the Play Console **developer website** field to `https://roadconquest.pages.dev/` and its **Privacy Policy** field to `https://roadconquest.pages.dev/privacy` once authorized. The external Play Console listing must be updated separately and is not changed by pushing GitHub code. The old Render static website should remain available until the Play listing is updated and the advertising systems have recognized the new domain.

No domain purchase, additional repository, or API secrets are required for this static site.
