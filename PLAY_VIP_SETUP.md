# VIP lifetime Google Play product

Road Conquest contains a one-time, non-consumable Google Play Billing purchase:

- **Product ID:** `vip_lifetime`
- **Type:** One-time product / in-app product; **buy** purchase option (not a subscription and not consumable)
- **US price to configure in Play Console:** **$2.99 USD**; local prices and taxes are controlled by Google Play
- **Benefits:** ad-free Garage, Achievements, Leaderboard, and Settings screens; rewarded-ad button hidden; Gold car color, Gold road color, and Golden Road Conquest UI/launcher icon available to equip without spending points
- **Existing point purchases:** unchanged; VIP does not refund points already spent or grant free non-gold cosmetics
- **Ownership:** attached to the purchaser's Google Play account, not the optional Road Conquest account

## Enable in Google Play Console

1. Upload a signed Android App Bundle with the billing integration to an **internal or closed testing** track, with app ID `com.roadconquest.app` and a previously unused version code. For Beta 48, use the signed GitHub Releases bundle with **version code 74**, provided Play Console has not already reserved it; Beta 47 used code 73 and Beta 46 used code 72. Play reserves version codes across testing tracks and draft releases.
2. In **Monetize with Play → Products → One-time products**, create the product `vip_lifetime` and a buy purchase option. Set the US price to **$2.99** and select the countries where it should be sold.
3. Activate/publish the product and purchase option. The product ID must match *exactly*; once published, the app reads the user's real local price from Play before enabling the checkout button.
4. Under **Settings → License testing**, add tester Google accounts. Enroll those accounts in the internal test and install the Play-distributed build. Test purchases can use Play's test payment methods without real charges.
5. Test a purchase and confirm: VIP is shown as owned; ads disappear; all three gold cosmetics are free to equip; app restart preserves VIP; reinstalling from Play restores VIP; pending payments do not grant VIP.
6. Refund/revoke a **test** purchase and confirm a successful new Play ownership query removes VIP access. Test temporary offline startup after a confirmed purchase, and ensure existing VIP owners retain access.

The app acknowledges completed purchases through Play Billing 9.1. It uses an on-device entitlement cache so confirmed purchases can work offline, and corrects ownership on a successful Play query. An unavailable store/network is not interpreted as a refund. Local driving-history deletion does not remove Google Play ownership.

**Production hardening:** For a revenue-bearing public release, connect purchase-token validation and Voided Purchases / refund notifications to an authenticated backend using the Google Play Developer API. Local purchase callbacks and SharedPreferences are sufficient for integration testing but are **not tamper-resistant purchase verification**. The current implementation does not claim server-side verification.

The product and US price **cannot be published through repository code**. They must be configured with the app's merchant permissions in Play Console. Until that is done, the Garage shows VIP as unavailable and does not attempt a fake payment.
