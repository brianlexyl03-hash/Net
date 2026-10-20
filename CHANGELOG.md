# Changelog

## 0.9.1
- Split the 1,000-line main screen into section files (`MainSections`, `MainDialogs`, `MainDiagnostics`, `UiKit`).
- Failures in the VPN engine and sessions are logged through `Logger` instead of being swallowed.
- Added unit tests for TCP and UDP sessions, packet edge cases, data limits, traffic history, app stats and the custom block list.
- Added a unit-test coverage report to CI, an `.editorconfig`, Dependabot, a Dockerfile and docker-compose.
- Rewrote the README; added architecture and contributing documents.

## 0.9.0
- Network check: per-source diagnosis and cross-source certificate comparison.
- Wi-Fi nearby: channel analysis and quietest-channel suggestion.
- Connection log with uptime per source.

## 0.8.0
- DNS blocker (ads, trackers, adult, custom list).
- Per-app traffic list with a block switch.
- Gaming mode with live ping, jitter and loss.

## 0.7.0
- Keep-awake locks and battery-exemption prompt.
- Data limits per source.
- Optional encrypted exit server (Shadowsocks, AES-256-GCM).

## 0.6.0
- New simplified interface, live traffic graph, add extra Wi-Fi.

## 0.5.0
- Local VPN engine: per-connection balancing across Wi-Fi and mobile data.

## 0.4.0
- Route discovery, per-route speed tests, concurrent benchmark.
