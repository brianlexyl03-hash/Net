# MultiPath Mobile Privacy Policy

**Effective date:** 17 November 2026

MultiPath Mobile is an Android network-diagnostics and per-connection routing application. This policy describes the current source-code implementation and must be checked against each release build.

## Information handled on the device

The app reads Android-provided network state and capabilities to list routes available to the app. During user-initiated network checks and speed tests, it contacts the configured HTTPS connectivity-check and speed-test endpoints to measure response time and throughput.

When the user chooses **Scan nearby Wi-Fi**, the app requests Android's location permission because Android requires it for Wi-Fi scan results on supported versions. It reads nearby network details exposed by Android, including network name (SSID), signal strength, channel, band and security type, and analyses them on the device. The app does not use this feature to obtain or transmit the device's GPS coordinates. Nearby network names can themselves reveal information about a user's surroundings, so use this feature only if you are comfortable granting the permission.

Traffic measurements, route-slot preferences, scan results and connection-log information are handled locally by the app. Diagnostic reports can include device manufacturer/model, Android version, network transport labels, interface names, route validation state, latency, measured speed and test errors. A report is shared only after the user selects the share-report action and chooses a destination.

## Data collection and sharing

The project has no app account, advertising SDK, analytics SDK or first-party backend configured. It does not intentionally collect browsing history or account credentials. Wi-Fi passwords entered by the user are stored encrypted on the device using the Android Keystore and are not sent to the app developer. If the user configures an optional exit server, traffic is sent to the server they selected; MultiPath does not operate that server. Network test requests necessarily reach the selected third-party test endpoints, which may process technical request data under their own policies.

## Permissions

The app uses Internet, network-state and Wi-Fi-state permissions for route discovery and diagnostics. It requests location permission only when the user starts a nearby Wi-Fi scan, as required by Android to access Wi-Fi scan results. It may request notification permission for the foreground VPN service and battery-optimisation exemption to help keep user-started connections active. Android displays its own VPN consent prompt before the local VPN service can start.

## Retention and deletion

Session measurements are held locally and are cleared when the app process/session is discarded or when the user clears measurements in the app. Route-slot preferences and saved Wi-Fi connection details remain in local app storage until changed, cleared in Android app settings, or the app is uninstalled. A diagnostic report shared by the user is handled by the destination they choose.

## Children, security and changes

The app is not designed specifically for children. This policy may be updated when the implementation changes. Contact: brianlexyl03@gmail.com.

