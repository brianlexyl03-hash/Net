# Contributing

## Set up

Requirements: JDK 17 and the Android SDK (platform 35). Then:

```bash
./gradlew testDebugUnitTest   # unit tests
./gradlew lintDebug           # Android lint
./gradlew assembleDebug       # debug APK in app/build/outputs/apk/debug
```

No local SDK? Use the container:

```bash
docker compose run --rm test
```

The `Add Gradle wrapper` workflow (Actions tab, run once) commits `gradlew` if it is missing.

## Making a change

1. One feature or fix per commit, with the test that proves it in the same commit.
2. Keep formatting changes out of feature commits.
3. Run the unit tests before pushing; CI runs tests, lint and a build on every push.
4. Code that parses packets or touches the network should not swallow exceptions silently; log with `Logger`.

## Where things go

See `docs/ARCHITECTURE.md`. Keep pure logic free of Android classes so it stays unit-testable.
