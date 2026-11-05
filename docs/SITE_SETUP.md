# Road Conquest website and app-ads.txt

The website source lives in `docs/`. It has no build dependencies, trackers, or analytics scripts.

## Currently hosted website

- Website: https://road-conquest-antnygamer.onrender.com/
- Privacy Policy: https://road-conquest-antnygamer.onrender.com/privacy.html
- Terms of Service: https://road-conquest-antnygamer.onrender.com/terms.html
- AdMob sellers file: https://road-conquest-antnygamer.onrender.com/app-ads.txt
- Render dashboard: https://dashboard.render.com/static/srv-dbmb1588v5cc73e3c830

Render is set to deploy the Git branch `site/road-conquest-pages` automatically and serve the `docs` directory at the site root. Verify that new commits are reflected in a **successful latest deployment** before linking to newly added pages. The Google Play package name is `com.roadconquest.app`.

## AdMob verification

The `docs/app-ads.txt` file contains exactly the seller line supplied by the publisher in AdMob:

```text
google.com, pub-4489303482171969, DIRECT, f08c47fec0942fa0
```

The file must be publicly accessible with HTTP 200 from the `/app-ads.txt` URL under the hostname that Google Play advertises as the app's developer website. A project Pages path such as `/Road-Conquest/app-ads.txt` is not equivalent to a root path. Use the **Render site URL above** in Google Play Console → Grow users → Store presence → Store settings → Store listing contact details → Website. After updating the listing, allow time for Google to discover it, then use AdMob → Apps → app-ads.txt → Check for updates. Check the exact URL AdMob reports it crawled.

Google's documentation: https://support.google.com/admob/answer/9363762

## GitHub Pages alternative

The workflow `.github/workflows/road-conquest-site.yml` can deploy these same assets to GitHub Pages. To use it, enable GitHub Actions as the Pages source under https://github.com/AntnyGamer/Road-Conquest/settings/pages and merge the site's PR into `main`. The resulting project site would typically be https://antnygamer.github.io/Road-Conquest/ ; this should *not* be used as the AdMob developer website unless `/app-ads.txt` is correctly served at the hostname root.

## Maintenance

Update `docs/index.html`, `docs/privacy.html`, and `docs/terms.html`. The privacy policy is mirrored from `server/src/application.mjs`; keep the copy synchronized if the app's data handling changes. The account-deletion link points to the deployed account service. The legal pages are intended as practical templates and should be reviewed for local legal requirements before broader commercial use.
