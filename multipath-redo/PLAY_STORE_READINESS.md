# MultiPath Mobile — Release readiness checklist

This project is a native Android network-diagnostics app. **It is not currently a production-verified, system-wide bonding VPN.** Do not market it as one.

## Implemented in this source
- Native Kotlin Android application, custom dark UI and adaptive launcher icon.
- Android network-route discovery, route-specific HTTPS latency checks, bounded route speed tests and a concurrent benchmark.
- Explicit mobile-data usage confirmation and local diagnostics sharing.
- Unit-test workflow, Android lint workflow, debug APK artifact and tagged unsigned AAB artifact.
- Privacy-policy draft that describes the current implementation and highlights publication prerequisites.

## Must be completed before Play Store production release
- [ ] Build and run tests/lint on CI; fix every failure and review warnings.
- [ ] Test on physical devices across Android 8–16, Wi-Fi-only, cellular-only, Wi-Fi + cellular, VPN active, no internet, captive portal, network switching and low-memory conditions.
- [ ] Validate all network test edge cases, timeouts, cancellation, lifecycle races and mobile-data cost messaging.
- [ ] Conduct security review of every dependency and network destination.
- [ ] Decide the final public support email, host the privacy policy on HTTPS and add the public privacy URL to Play Console.
- [ ] Complete Play Console Data safety, content rating, target audience, app access and store listing declarations truthfully.
- [ ] Generate a release AAB, sign it with the publisher's protected upload key, enable Play App Signing and verify the uploaded bundle.
- [ ] Supply screenshots from the actual running app, not mockups; create Play listing graphics and a feature graphic.
- [ ] Verify accessibility, contrast, font scaling, TalkBack labels, offline states, and tablet/small-screen layouts.
- [ ] Complete internal/closed testing and address all crashes and policy feedback.
- [ ] If implementing actual traffic bonding: build the user-consented VpnService/TUN engine, route binding, DNS/IP handling, failover, remote bonding endpoint if needed, leak prevention, battery handling, VPN conflict handling and comprehensive security/performance testing before claiming the feature.

## Version
- Application ID: `dev.multipath.android` (confirm publisher ownership before public release)
- Version: `0.4.0` / versionCode `4`
- minSdk 26, targetSdk 35
- CI builds are test artifacts. A tagged unsigned AAB is not signed and is not ready to upload as a final release without publisher signing and verification.
