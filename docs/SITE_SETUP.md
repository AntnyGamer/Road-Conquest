# Road Conquest website

This directory is the static Road Conquest website, deployed to GitHub Pages by the workflow at `.github/workflows/road-conquest-site.yml`. No build, tracking scripts, cookies, or third-party dependencies are required.

## Enable the website

1. In [repository Pages settings](https://github.com/AntnyGamer/Road-Conquest/settings/pages), choose **Build and deployment → Source → GitHub Actions**.
2. On the **Actions** tab, run **Deploy Road Conquest website** if it has not already run on the main branch.
3. The project site is expected at https://antnygamer.github.io/Road-Conquest/ . Confirm the published URL in the Pages settings; do not treat it as live until Pages deployment succeeds.
4. In Play Console, set the public **Website** field under Store settings → Contact details to the publicly accessible developer website URL.

## Advertising and app-ads.txt

**Important:** A project Pages URL with the path `/Road-Conquest/` is *not* the root of its host. Liftoff Monetize requires app-ads.txt at the accepted developer domain's root (e.g. `https://example.com/app-ads.txt`) and currently says subdomains are not accepted. A `docs/app-ads.txt` file here would be served at `/Road-Conquest/app-ads.txt`, which is not enough for that requirement. Do not claim app ads validation has passed just because this site works.

To complete Liftoff verification, configure a domain/hosting arrangement accepted by Liftoff that serves `/app-ads.txt` at its root, and use that exact domain in the Google Play listing and Liftoff dashboard. Insert your actual Liftoff seller line and authorized demand-partner lines from the Liftoff dashboard; **do not use invented or example seller IDs**. Once updated, Liftoff's app-store discovery may take about 24 hours to refresh.

Reference: https://support.vungle.com/hc/en-us/articles/210985478-Publisher-Applications-Setting-Up-and-FAQ

## Maintenance

The website is plain HTML, CSS, and SVG; update `index.html` and `favicon.svg`. The Play Store link uses Android package `com.roadconquest.app`. Keep the privacy policy and account deletion URLs aligned with the app's deployed account-service routes.
