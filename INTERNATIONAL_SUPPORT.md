# International compatibility and release criteria

Road Conquest stores GPS fixes, matched road geometry, fog-grid cells and travel history using worldwide latitude/longitude coordinates. Neither the primary Geoapify matcher nor the LocationIQ fallback is hard-coded to the US. Country and region overlays use OpenStreetMap searches with geoBoundaries as an optional, bounded backup. US municipality discovery has an additional official Census-specific verification path.

## Geographic failure modes and mitigations

- Countries and regions: Android Geocoder may omit the English country label while returning only a valid ISO two-letter code. The resolver derives an English label locally and retains a stable country key. It prioritizes fully country-coded results over adjacent code-less postal results, without losing valid partial addresses when the coded result lacks a town or region.
- Towns: Nominatim administrative boundaries may use a local-language name when Android supplies an English name (for example, München/Munich). Search asks for English labels and bounded English namedetails aliases. Town award acceptance still requires a correctly classified administrative polygon and geographic containment; a conflicting ISO country code is rejected.
- Map display and matching: provider coverage and road data vary by location. A valid recorded GPS fix must not be turned into an invented road because the service lacks routable geometry. GPS stays saved for retry. Actual road accuracy cannot be guaranteed in every country.
- Fog: saved one-mile cells use a globally aligned grid, wrap at the date line, and render within Web Mercator's latitude limit. Very high polar latitudes are outside normal map rendering.
- Locales: the app's user-facing strings are currently English. This is not a localization release.
- Foreign towns or populations may remain unavailable if the provider has no matching boundary or metadata, or requests cannot be completed.

## External scale blocker

The configured default worldwide administrative boundary provider is the public Nominatim instance (see OVERLAY_PROVIDER.txt). Its official usage policy places a **one-request-per-second ceiling on the entire application**, not on each installation, and discourages automated periodic geocoding. Current per-process throttling does not enforce a global multi-user budget. A user-configurable remote endpoint allows replacing the instance without rebuilding the app, but a production-grade shared proxy/cached provider or a provider with sufficient licensed quota must be arranged before promising robust service to hundreds of simultaneous explorers.

References: https://operations.osmfoundation.org/policies/nominatim/ and https://nominatim.org/release-docs/latest/api/Search/

## Regression matrix

Automated fixture tests exercise ISO-only country discovery (GB, JP, DE, CA, BR, AU, IN), conflicting national addresses, translated town names, incorrect country codes, and GPS polygon containment. Existing tests exercise date-line behavior and strict road quality. These are not physical-drive or live-provider country-by-country tests.

Before an international availability claim, perform real-road GPS recording, matching and border-crossing checks across multiple countries, verify legal/usage limits for every API, and test offline retries and provider outages.
