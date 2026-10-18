# MultiPath Mobile Privacy Policy

**Effective date:** 18 October 2026

MultiPath Mobile is an Android network-diagnostics application. This policy describes the current source-code implementation and must be reviewed against the final release build before publication.

## Information handled on the device

The app reads Android-provided network state and capabilities to list routes available to the app. During user-initiated tests, it contacts the documented HTTPS connectivity-check and speed-test endpoints to measure response time and download throughput. Test results and route-slot preferences are held locally in app memory or Android app preferences.

The diagnostic report can include the device manufacturer/model, Android version, network transport labels, interface names, route validation state, latency, measured speed and test errors. Sharing occurs only after the user selects the share-report action and chooses a destination.

## Data collection and sharing

The current project has no app account, advertising SDK, analytics SDK or first-party backend configured. It does not intentionally collect browsing history or account credentials, and it never reads passwords of networks you did not add. A Wi-Fi password you enter in the app is stored encrypted on your device only (Android Keystore) and is never sent anywhere. Network test requests necessarily reach the third-party test endpoint; those services may process technical request data under their own policies.

## Permissions

The app uses Internet, network-state and Wi-Fi-state permissions to inspect routes and run user-initiated diagnostics. The current implementation does not request location permission.

## Retention and deletion

Session measurements are cleared when the app process/session is discarded or when the user clears measurements in the app. Route-slot settings remain in local app preferences until changed or the app's data is cleared/uninstalled. A diagnostic report shared by the user is handled by the destination they choose.

## Children, security and changes

The app is not designed specifically for children. This policy may be updated when the app's implementation changes. Contact: replace this placeholder with a monitored support email before publishing.

## Play Store publication note

This source policy is not, by itself, a complete Play Store compliance submission. Before release, the publisher must host this policy at a stable public HTTPS URL, provide a real contact address, verify all SDKs and network behaviour in the release APK/AAB, complete the Data safety form accurately, and test on supported physical devices.
