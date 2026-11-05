# Road Conquest website and legal pages

The static marketing site is hosted on Render from `docs/`. Its legal links point exclusively to Road Conquest's canonical Cloudflare account Worker; the older Render privacy page is a redirect.

## URLs

- Website (Render): https://road-conquest-antnygamer.onrender.com/
- **Canonical Privacy Policy** (Cloudflare): https://roadconquest-api.22abongiovanni.workers.dev/privacy
- **Canonical Terms of Service** (Cloudflare, requires Worker deployment): https://roadconquest-api.22abongiovanni.workers.dev/terms
- AdMob sellers file on Render (LIVE): https://road-conquest-antnygamer.onrender.com/app-ads.txt
- AdMob sellers file on Cloudflare (requires Worker deployment): https://roadconquest-api.22abongiovanni.workers.dev/app-ads.txt

The Google Play listing's **Privacy Policy** field should use the Cloudflare Privacy Policy URL, while **Developer website** should use the Render marketing URL. These are different Play Console fields. Do not use the GitHub Pages project path for the developer website: it does not serve `app-ads.txt` at the hostname root. These Play Console values must be changed in Play Console separately; a GitHub change cannot update them.

## Cloudflare publishing

The existing `roadconquest-api` Worker is the public ingress for the Neon account API. The `server/worker/ingress.mjs` route serves `/terms` and `/app-ads.txt` directly without relying on the Neon function or database. The Terms HTML and exact AdMob seller line are stored in `server/worker/legal-pages.mjs`.

The code change does **not** deploy the live Worker. From an authenticated Cloudflare Wrangler environment, use the existing deployment process:

```sh
npm --prefix server ci --ignore-scripts --no-audit --no-fund
npm --prefix server run build:ingress
npx wrangler deploy --config server/worker/wrangler-public.jsonc
```

**Important:** `server/worker/wrangler-public.jsonc` explicitly targets `roadconquest-api`, the Worker name shown in the public hostname. The older `wrangler.jsonc` targets a DIFFERENT name (`roadconquest-ingress`); use the public config for this address. Verify the Cloudflare account and worker identity before deploying. Preserve `API_ORIGIN`, `RATE_LIMIT_IP_SIGNATURE_KEY`, and all other deployed secrets/configuration. After deployment, verify `/privacy`, `/terms`, `/app-ads.txt`, and authenticated API endpoints. Build artifacts and local repositories do not automatically update Cloudflare.

## AdMob

The exact line supplied by the app publisher is:

```text
google.com, pub-4489303482171969, DIRECT, f08c47fec0942fa0
```

AdMob crawls `app-ads.txt` at the host in Google Play's developer website field, not the Privacy Policy URL. The Render root file above is publicly reachable over HTTPS and is the recommended verification location; the multi-level `workers.dev` API hostname might not be treated as the root by AdMob's subdomain crawler. After changing the Play listing, use **Check for updates** in AdMob and inspect the exact domain its diagnostics report.

## GitHub Pages (optional alternative)

The workflow `.github/workflows/road-conquest-site.yml` also supports Pages. Enable GitHub Actions as the Pages source at https://github.com/AntnyGamer/Road-Conquest/settings/pages before attempting Pages publication. The marketing site and app-ads.txt are deployed independently to Render from the website branch.

## Maintenance

Android Settings opens the canonical Cloudflare legal pages regardless of custom account API build overrides. Keep `server/worker/legal-pages.mjs` updated when Terms change and `server/src/application.mjs` updated when privacy practices change; the Cloudflare `/privacy` route is still proxied to the existing Neon application. The static `docs/terms.html` is only a fallback copy.
