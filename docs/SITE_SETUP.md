# Road Conquest site — GitHub Pages only

This `docs/` folder contains the complete static marketing and legal website:
- `index.html` — landing page
- `privacy.html` — full privacy policy, adapted from the app's current privacy policy
- `terms.html` — full terms of service
- `app-ads.txt` — exact AdMob seller record
- `favicon.svg`, `robots.txt`, and `.nojekyll` — supporting files

No Cloudflare or Render service is required to **serve these public static pages**. Road Conquest's *app* still uses Cloudflare and Neon for accounts, map services, and account deletion; these APIs must **not** be deleted.

## Root-domain requirement for AdMob

The existing repository is `AntnyGamer/Road-Conquest`. A normal project Pages site would have the URL `https://antnygamer.github.io/Road-Conquest/` and serve `app-ads.txt` under `/Road-Conquest/`. **AdMob looks at the hostname root**, which means it instead checks `https://antnygamer.github.io/app-ads.txt`; a file in the project subdirectory is insufficient.

**Publish this static website as a GitHub *user site***:
1. Create a **public** GitHub repository named **`AntnyGamer.github.io`** in the `AntnyGamer` account (must match username exactly). This repository does not currently exist and cannot be created through the available GitHub connector actions.
2. Copy the files in this `docs/` folder into the **root of that user-site repository**. The accompanying GitHub Action "Export GitHub Pages website" creates a downloadable ZIP artifact containing these root-ready files.
3. In the new user-site repository's **Settings → Pages**, enable **Deploy from a branch**, using `main` and `/(root)`.
4. Verify the three URLs return HTTP 200 (not a GitHub 404):
   - `https://antnygamer.github.io/`
   - `https://antnygamer.github.io/privacy.html`
   - `https://antnygamer.github.io/terms.html`
   - `https://antnygamer.github.io/app-ads.txt`
5. **Only after verifying all live URLs**, use Play Console to set the *developer website* to `https://antnygamer.github.io/` and the *privacy-policy URL* to `https://antnygamer.github.io/privacy.html`, then request an AdMob recheck.
6. After the new site is confirmed live, Android Settings should link to `https://antnygamer.github.io/privacy.html` and `https://antnygamer.github.io/terms.html`. Those source changes are staged on the website PR and should not be released until the pages exist.

AdMob may require a custom domain if it refuses GitHub's `github.io` domain. Never claim verification is complete until AdMob confirms it.

## Temporary live services and cleanup

The current marketing website on Render and public legal routes on the account backend should **not** be disabled until GitHub Pages works and Google Play and AdMob have switched to it. Rendering this static site does not require them. Render account service deletion and GitHub repository creation are not available through the current connected management actions; do not report either as completed.

## Keep production backends untouched

Cloudflare's account and map worker endpoints and the Neon account backend are part of Road Conquest's operational infrastructure, not GitHub Pages. Do not delete them, remove their Android endpoint configuration, or modify their secrets as part of a website-hosting change. The existing account deletion form remains part of the backend and is linked for that function.
