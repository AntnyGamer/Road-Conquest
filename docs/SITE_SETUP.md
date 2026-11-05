# Road Conquest website and public legal pages

The Road Conquest homepage is served at the root of the **existing account API's public Cloudflare hostname**:

- Website: https://roadconquest-api.22abongiovanni.workers.dev/
- Privacy Policy: https://roadconquest-api.22abongiovanni.workers.dev/privacy
- Terms of Service: https://roadconquest-api.22abongiovanni.workers.dev/terms
- AdMob seller file: https://roadconquest-api.22abongiovanni.workers.dev/app-ads.txt
- Delete account: https://roadconquest-api.22abongiovanni.workers.dev/delete-account

The Cloudflare hostname routes to the existing Neon function; account sessions, maps, and app data remain separate and unchanged. Source-of-truth site assets are `server/src/public-site.mjs` (homepage and favicon) and `server/src/public-legal.mjs` (Terms and AdMob seller file); `server/src/application.mjs` serves them through the current API host. The `docs/` directory is a readable static reference and must be kept in sync, not deployed to GitHub Pages.

The public website uses the real Android launcher icon from `app/src/main/res/drawable/ic_car.xml` (served via `/favicon.svg`).

AdMob publisher record:

```
google.com, pub-4489303482171969, DIRECT, f08c47fec0942fa0
```

**Google Play Console:** Set the developer website to the **homepage URL above**, and the separate Privacy Policy field to the **/privacy** URL. Google must recognize that URL as the developer website before it can discover app-ads.txt. Do not claim AdMob verification succeeded until Google confirms it.

The old Render website should not be removed until Google Play has switched to this site and AdMob verification is confirmed. Do not delete Cloudflare workers handling account or map services; this website shares their public hostname.
