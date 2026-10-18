# MultiPath — Android network diagnostics (v0.4.0)

A native Android Kotlin project inspired by the MIT-licensed desktop project [uditya-kumar/MultiPath](https://github.com/uditya-kumar/MultiPath). This is an independent Android companion prototype, not an official release or endorsed fork.

## Visual and UX refresh (v0.4.0)

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
- Links to Android Wi-Fi and Internet settings; does not read or store Wi-Fi passwords.
- Includes unit tests for the flow-selection policy and a GitHub Actions workflow that runs tests and builds a debug APK artifact.

## Important limitations

**This is not yet a system-wide internet speed booster or traffic-bonding VPN.** The app inspects and tests routes, but does not redirect ordinary traffic from other apps or merge mobile data and Wi-Fi into one connection. The concurrent benchmark is a measurement tool, not proof of bonding.

A complete Android traffic engine requires a user-approved `VpnService`, a TUN packet reader/writer, per-flow routing and socket binding to Android `Network` objects, DNS and IPv4/IPv6 handling, route failover, UDP/QUIC policy, VPN-conflict handling, battery/background behavior and real-device testing. True single-download bonding usually requires a remote bonding endpoint as well. Those parts must not be advertised as complete until implemented and verified.

### Hardware and Android route constraints

- Redmi 15C has two physical SIM slots; the app cannot create extra SIM radios.
- Safaricom/Airtel/Telkom carrier labels may not be exposed to ordinary apps for every route, so routes are labelled by transport rather than guessed carrier.
- Android usually represents an external phone hotspot as a Wi-Fi route. Separate Wi-Fi/hotspot slot values are user limits, not a way to force the phone to connect to several Wi-Fi networks simultaneously.
- The app can only test routes Android exposes to it. A USB-tethered connection may appear as Ethernet or another transport depending on Android/device support.

## Build and release

Open the project root in Android Studio with Android SDK Platform 35 and JDK 17. Allow Gradle sync, then run the `app` configuration on an Android 8.0+ device or emulator. CI runs unit tests, Android lint and a debug build. A version tag such as `v0.4.0` triggers a release bundle build; the resulting AAB is unsigned and still requires publisher signing, Play App Signing setup and release verification. See `PLAY_STORE_READINESS.md` before publication.

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

This ZIP is arranged with the Android project at the repository root. Extract it and upload the contents of this folder (including the hidden `.github` folder) to the root of your GitHub repository. The GitHub Actions workflow will then run automatically on push.
