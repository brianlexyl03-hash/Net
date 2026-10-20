# Architecture

MultiPath is an Android app that runs a local VPN and relays each connection over a chosen network (Wi-Fi, mobile data, USB tether, or an extra Wi-Fi it joined). There is no MultiPath server.

## Packet path

```
apps -> TUN interface -> MultiPathVpnService.packetLoop
                           |-- Blocker (DNS for ads/trackers answered locally)
                           |-- AppResolver (which app owns the flow) -> AppBlock
                           |-- TcpSession / UdpSession
                                  |-- RouteSelector -> FlowBalancer (picks a network per flow)
                                  |-- socket bound to that Android Network
                                  |-- optional Shadowsocks tunnel to an exit server
```

## Main pieces

| File | Responsibility |
| --- | --- |
| `MultiPathVpnService` | Owns the TUN device, parses IPv4, creates sessions, foreground notification, wake/Wi-Fi locks |
| `TcpSession` | User-space TCP endpoint facing the app (handshake, sequence numbers, windowing) and a real socket facing the network |
| `UdpSession` | One UDP flow; DNS goes through the exit tunnel when one is enabled |
| `PacketUtil` | IPv4/TCP/UDP building, parsing and checksums (pure, unit tested) |
| `FlowBalancer` | Scores routes by capacity, validation, latency and active flows (pure, unit tested) |
| `Routing` | `RoutePolicy` (which sources are allowed), `RouteSelector`, `VpnStats` counters |
| `NetworkMonitor` | Watches Android networks, keeps mobile data alive, merges in extra Wi-Fi |
| `ExtraWifi`, `SecretStore` | Joins additional Wi-Fi with `WifiNetworkSpecifier`; passwords encrypted with Android Keystore |
| `Shadowsocks`, `ExitServer` | Encrypted (AES-256-GCM) client for an exit server the user runs |
| `Blocker` | DNS-level blocking of ad/tracker/adult/custom domains |
| `Apps.kt` | `AppResolver`, `AppStats`, `AppBlock`, `Gaming` |
| `PingMonitor` | Delay, jitter and loss per source every two seconds |
| `Bundles` | Per-source data limits |
| `NetCheck`, `WifiRadar`, `NetLog` | Diagnostics, nearby Wi-Fi scan, connection history |
| `TrafficHistory`, `LiveGraphView` | Real per-second throughput and the chart |
| `MainActivity` | Programmatic UI |

## Design decisions

- **Per-connection balancing, not packet bonding.** A single connection uses one route. Real bonding needs a far-end server that speaks a multipath protocol.
- **Our own sockets bypass the VPN** (`addDisallowedApplication`) and are bound to a specific `Network`, so there are no routing loops.
- **No TUN checksum offload is assumed**; every generated packet carries valid IP and TCP checksums.
- **IPv6** is blocked inside the tunnel only when an exit server is on (to avoid leaking the real address); otherwise it bypasses the tunnel.
- **Pure logic is kept free of Android types** so it runs in plain JVM unit tests.

## Testing

Unit tests live in `app/src/test`. They cover packet building, TCP session failure handling, Shadowsocks framing, the DNS blocker, flow balancing, bundles, traffic history and Wi-Fi channel analysis. Code that needs a device (VPN permission, real networks) is checked manually using the debug APK from CI.
