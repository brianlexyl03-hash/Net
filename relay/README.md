# Multipath relay server

This folder contains the relay-side server required for true single-flow bonding.

The Android app can still do per-flow routing locally without a server, but one connection cannot be *bonded* across Wi‑Fi and cellular unless the client and the relay agree on a packet sequencing and reassembly protocol.

## What this server does

This scaffold performs three things:

- accepts a QUIC connection from a MultiPath client
- stores per-session and per-path metadata
- demonstrates the relay-side packet receive and forward flow

A production implementation would add:

- MASQUE CONNECT-IP flow semantics
- packet sequence numbers and reassembly
- path selection and return scheduling
- traffic authentication and rate limiting
- encrypted relay session keys and abuse prevention

## Run locally

1. Generate a certificate:

```bash
mkdir -p certs
openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout certs/tls.key -out certs/tls.crt \
  -subj "/CN=localhost" -days 365
```

2. Start the relay:

```bash
cd relay
RELAY_BIND=:443 \
RELAY_TLS_CERT=./certs/tls.crt \
RELAY_TLS_KEY=./certs/tls.key \
go run .
```

3. Point the Android client at the relay endpoint.

## Production notes

- Use a real CA-issued certificate for production.
- Run behind a TLS-terminating load balancer or directly on a public IP/port.
- Keep a backend service for auth, quotas, abuse prevention, and metrics.
- Separate control-plane and data-plane components if the relay grows.
