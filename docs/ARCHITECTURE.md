# Architecture

MultiPath is designed as a relay-backed multipath VPN. The local app handles traffic interception,
route selection, and packet classification. The relay server handles packet sequencing,
reassembly, and return path scheduling for a single connection that spans multiple links.

## Packet path

```
apps -> TUN interface -> MultiPathVpnService.packetLoop
                        |-- DnsPolicy (plain DNS, DoH, DoT enforcement)
                        |-- Ipv6Policy (prevent IPv6 leaks when relay is offline)
                        |-- AppResolver / AppBlock
                        |-- TcpSession / UdpSession
                                |-- RouteSelector -> Android Network selection
                                |-- packet scheduler -> relay tunnel
                                |-- Wi-Fi or cellular uplink by health/latency/throughput
                                |-- relay reassembly and return scheduling
                                                |
                                                v
                                 remote relay server (QUIC + MASQUE-like IP relay)
                                                |
                                                +--> packet ordering + reassembly
                                                +--> load balancing across Wi‑Fi and cellular paths
                                                +--> forward to the destination
                                                +--> send replies back to the client
```

## Design decisions

- Local Android code is responsible for intercepting traffic and choosing routes.
- The relay server is required for true single-flow bonding across multiple links.
- IPv6 is captured and forwarded when the relay is available; otherwise it is blocked.
- DNS is enforced by TUN-level interception and relay-side policy checks.
- Per-app mode remains Android-10+ and must be scoped to avoid unnecessary broad package visibility.

## Current repository status

This repository is now structured around the correct model: local multipath routing plus a relay-backed
multipath architecture. The original per-connection-only design is intentionally kept as a
Phase 1 / Phase 2 baseline, while the current architecture explicitly recognizes the relay server as
required for single-flow bonding.
