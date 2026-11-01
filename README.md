# MultiPath

[![Android CI](https://github.com/brianlexyl03-hash/Net/actions/workflows/android.yml/badge.svg)](https://github.com/brianlexyl03-hash/Net/actions/workflows/android.yml)

An Android app that shares your phone's internet across every connection Android exposes (Wi-Fi, mobile data, USB tethering, extra Wi-Fi you add), with live traffic graphs, a DNS blocker, per-app controls and diagnostics. It runs entirely on the phone: there is no MultiPath server, account or analytics.

Version 0.9.1 · Android 8.0+ (min SDK 26) · Kotlin, no third-party runtime dependencies.

## Features

- **Traffic balancer.** A local VPN (`MultiPathVpnService`) terminates each TCP/UDP flow in a user-space relay and re-opens it over a socket bound to a specific Android `Network`. `FlowBalancer` picks the route per connection and retries on another if one fails.
- **Live graph.** Real per-second download/upload per source, built from the bytes the relay moves.
- **Sources.** Switch each source on or off, set a data limit per source, add extra Wi-Fi (Android 10+; whether two Wi-Fi links can run at once depends on the phone's chip).
- **Apps using internet.** Per-app speed and totals, with a switch to cut an app off (Android 10+).
- **Gaming mode.** Measures delay, jitter and loss per source and sends chosen games over the lowest-delay source.
- **Blocker.** Answers DNS lookups for ad, tracker, adult and custom domains with "does not exist".
- **Exit server (optional).** Encrypted Shadowsocks (AES-256-GCM) tunnel to a server you run, so websites see that server's location.
- **Network check, Wi-Fi nearby, connection log.** Plain-language diagnosis, channel advice and uptime history.
- **Keep awake.** Wi-Fi and wake locks while balancing, plus a battery-exemption prompt.

## Build and test

```bash
./gradlew testDebugUnitTest   # unit tests (JDK 17 + Android SDK 35)
./gradlew lintDebug
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
docker compose run --rm test  # same tests, no local SDK needed
```

If `gradlew` is missing, run the **Add Gradle wrapper** workflow once from the Actions tab. CI (`.github/workflows/android.yml`) runs tests, lint and a debug build on every push, and publishes a debug APK under Releases for pushes to `main`. See `CONTRIBUTING.md` and `docs/ARCHITECTURE.md`.

## Status

A working prototype. The packet engine, DNS blocker, Shadowsocks framing, balancing policy, data limits and statistics are unit tested on the JVM. Behaviour that needs real hardware (VPN permission, two live networks, extra Wi-Fi, per-app lookup) is verified by hand using the CI-built debug APK, and results differ between phones. It is not Play Store ready; see `PLAY_STORE_READINESS.md`.

## Install

Download `app-debug.apk` from the newest release, allow installs from unknown sources, open it, tap **Start**, and accept the VPN prompt. The APK is debug-signed, not Play Store signed.

## Limitations

- **Per-connection balancing, not packet bonding.** One connection uses one route, so a single download cannot exceed that route. Many parallel connections speed up. Bonding one connection across links needs a far-end server that this project does not include.
- IPv6 traffic bypasses the tunnel unless an exit server is on (then it is blocked to prevent leaks).
- Apps using DNS-over-HTTPS or their own DNS servers bypass the blocker.
- Only one VPN can be active on Android at a time.
- Some phones cannot hold two Wi-Fi connections or two SIM data connections at once; the app reports what Android exposes.
- Data limits count only traffic relayed by MultiPath, which can differ from the carrier's meter.
- Per-app features need Android 10+ and the `QUERY_ALL_PACKAGES` permission, which Google Play restricts. See `PLAY_STORE_READINESS.md` before publishing.

## Privacy

- No backend, account or analytics. Measurements, counters and the connection log stay on the device.
- Wi-Fi passwords you add and the exit-server password are stored encrypted with the Android Keystore and never sent anywhere except to the network or server you chose.
- Speed tests contact `connectivitycheck.gstatic.com` and `speed.cloudflare.com`. The network check contacts `connectivitycheck.gstatic.com`, `api.ipify.org` and a few large websites to compare certificates.
- With an exit server on, your traffic goes to that server. MultiPath does not operate any server.

## Project layout

```
app/src/main/java/dev/multipath/android   app code (see docs/ARCHITECTURE.md)
app/src/test/java/dev/multipath/android   JVM unit tests
docs/                                     architecture and privacy policy
.github/workflows/                        CI, release and Gradle-wrapper workflows
```

## Roadmap

- Split `MainActivity` further and add instrumented tests for the VPN permission flow.
- Optional far-end aggregator so one connection can use several links.
- WireGuard exit option next to Shadowsocks.
- Signed release builds and a Play Store listing.

## License

See `LICENSE`.

## Contact and collaboration

Software engineer open to thoughtful collaboration, engineering discussions and project contributions.

- Email: [brianlexyl03@gmail.com](mailto:brianlexyl03@gmail.com)
- WhatsApp: [+254 769 485 902](https://wa.me/254769485902)

Please include the repository, issue or feature area in your message.

## Developer portfolio and releases

See the [central project, download and achievement index](https://github.com/brianlexyl03-hash/fuskamo/blob/main/PORTFOLIO.md) for verified APK downloads, web-app deployment status, engineering achievements and collaboration details.
