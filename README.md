# MultiPath — Android network diagnostics (v0.5.0)

A native Android Kotlin project inspired by the MIT-licensed desktop project [uditya-kumar/MultiPath](https://github.com/uditya-kumar/MultiPath). This is an independent Android companion prototype, not an official release or endorsed fork.

## Visual and UX refresh (v0.5.0)

- New custom MultiPath launcher icon: dark navy tile with a violet/cyan/mint multi-route mark; adaptive and round launcher icon resources included.
- Replaced the placeholder letter avatar in the app header with the branded mark and simplified the top-level product identity.
- Refined the home-screen hero copy, status chip, spacing, and button touch targets for clearer scanability on phones.
- Kept the screen honest about what the prototype can do: route discovery and benchmarking, not system-wide bonded internet.

## Features in this revision

- Discovers internet-capable Wi-Fi, cellular and Ethernet routes that Android exposes to the app.
- Shows validated internet status, interface name, Android-reported downstream/upstream estimates, HTTPS latency and measured download rate per route.
- Lets users configure cellular, Wi-Fi and extra hotspot route limits, saved locally.
- Provides an ordinary per-route download test capped at 4 MB per route.
- Provides a concurrent multi-route benchmark capped at 2 MB per route (maximum 20 MB for ten exposed routes). It measures total bytes delivered during the same wall-clock interval, alongside individual route results.
- Shows the fastest measured route, the sum of separately measured route rates, and the latest concurrent benchmark as clearly distinct metrics.
- Can clear measurements and share a diagnostic report. The report includes device/Android version, route status and test results, but not Wi-Fi passwords, credentials or browsing history.
- Links to Android Wi-Fi and Internet settings; does not read Wi-Fi passwords. Wi-Fi networks you add yourself are stored encrypted on the device with the Android Keystore.
- Includes unit tests for the flow-selection policy and a GitHub Actions workflow that runs tests and builds a debug APK artifact.

## Traffic balancer (v0.7.0)

Tap **Start balancing** to run an on-device VPN (`MultiPathVpnService`). It reads IPv4 packets from the TUN interface, terminates each TCP connection and UDP flow in a small user-space relay, and re-opens it over a socket bound to a specific Android `Network`. `FlowBalancer` picks the Wi-Fi or mobile-data route for every new connection; if connecting fails it retries on another route. No server or account is needed.

- Works on Android 8.0+ (min SDK 26) with the standard VPN consent dialog.
- Keeps mobile data connected while Wi-Fi is the default so both links can carry traffic.
- Per-connection balancing: many parallel connections speed up, a single download stays capped by its one route.
- IPv6 traffic bypasses the tunnel; DNS goes through 1.1.1.1 / 8.8.8.8 over the chosen route.
- Only one VPN can be active at a time on Android; some OEM battery managers may stop background apps, so exclude MultiPath from battery optimisation.
- Not Play-Store verified; test on your own devices (see `PLAY_STORE_READINESS.md`).

### Also in 0.7.0
- **Keep awake:** holds a Wi-Fi lock and wake lock while balancing and offers a battery-optimisation exemption. It cannot turn a radio back on if you or the OEM switch it off.
- **Data limit per source:** set an allowance for mobile data, a hotspot or any source; new connections stop using it once the limit is reached. It counts MultiPath traffic only.
- **Exit server:** optional encrypted exit (Shadowsocks, aes-256-gcm) so websites see your own server's location. You need a server; none is provided. While it is on, IPv6 and non-DNS UDP are blocked so nothing leaks around it.

## Important limitations

**This is not packet-level bonding.** True single-download bonding needs a remote bonding endpoint, which this project does not include. The concurrent benchmark remains a measurement tool.

### Hardware and Android route constraints

- Redmi 15C has two physical SIM slots; the app cannot create extra SIM radios.
- Safaricom/Airtel/Telkom carrier labels may not be exposed to ordinary apps for every route, so routes are labelled by transport rather than guessed carrier.
- Android usually represents an external phone hotspot as a Wi-Fi route. Separate Wi-Fi/hotspot slot values are user limits, not a way to force the phone to connect to several Wi-Fi networks simultaneously.
- The app can only test routes Android exposes to it. A USB-tethered connection may appear as Ethernet or another transport depending on Android/device support.

## Build and release

Open the project root in Android Studio with Android SDK Platform 35 and JDK 17. Allow Gradle sync, then run the `app` configuration on an Android 8.0+ device or emulator. CI runs unit tests, Android lint and a debug build. A version tag such as `v0.5.0` triggers a release bundle build; the resulting AAB is unsigned and still requires publisher signing, Play App Signing setup and release verification. See `PLAY_STORE_READINESS.md` before publication.

## Data usage and privacy

- The ordinary test downloads up to 4 MB per eligible route.
- The concurrent test downloads up to 2 MB per eligible route, potentially 20 MB across ten routes.
- The app asks for confirmation before starting speed tests because mobile-data charges may apply.
- Tests contact `connectivitycheck.gstatic.com` and `speed.cloudflare.com`.
- There is no backend, account, analytics, or stored browsing history in this prototype. Measurements are held in memory for the current app session.

## Technical details

- Kotlin / native Android SDK; min SDK 26, compile SDK 35, target SDK 34.
- Android Gradle Plugin 8.7.3; Kotlin 2.0.21; Gradle 8.9; JDK 17.
- The `FlowBalancer` scheduler is a tested policy prototype for assigning independent flows. It is not connected to an Android VPN/TUN engine in this revision.


## Repository upload note

This project is arranged with the Android project at the repository root. Extract it and upload the contents of this folder (including the hidden `.github` folder) to the root of your GitHub repository. The GitHub Actions workflow will then run automatically on push.
