# re-adb

**Remote ADB over an encrypted userspace tunnel** — reach a device's `adbd`
from a remote box with **no TUN, no root, and IP + a pre-shared key only** (no
certificates, no PKI).

Built on the [portmux](https://github.com/XNet-NGO/portmux) encrypted tunnel
(`tunnel-server` / `tunnel-client`, Noise `NoisePSK_NN`: ephemeral X25519 +
32‑byte PSK, AES‑256‑GCM / ChaCha20‑Poly1305). The device runs a tunnel client
(e.g. via [switch-mcp](https://github.com/XNet-NGO/switch-mcp) `add_tunnel`, or
[Switchboard](https://github.com/XNet-NGO/switchboard)'s Tunnel route, or the
`portmux` CLI); a rendezvous server exposes a local bridge port that `adb
connect` speaks to, and the ADB protocol flows encrypted through the tunnel to
the device's `adbd`.

## How it works (Scenario B — rendezvous)

```
operator box:  adb connect 127.0.0.1:BRIDGE
                   │  (plain, localhost)
               portmux tunnel-server  ◄──────── Noise/AES-GCM (one outbound
                   │ bridge                        conn from the device)
                   └── relay ─────────────────────┐
                                                  ▼
                                          device (tunnel client)
                                             │ plain splice
                                             ▼
                                          127.0.0.1:5555  (adbd)
```

- The **device dials out** (NAT/firewall friendly); nothing listens on the
  device's public interfaces.
- The tunnel's outbound leg can be **egress‑pinned** to a chosen network
  (Wi‑Fi / cellular) with no root.
- A wrong PSK fails the Noise handshake — no data flows.

## Status

Verified end‑to‑end: `adb connect` + `adb shell` through the tunnel to a
Pixel's `adbd`, driven by switch-mcp `add_tunnel` over MCP (AES‑256‑GCM). This
repo will package that into a turnkey remote‑ADB workflow.

## Components

- **portmux** — the engine + `tunnel-server` / `tunnel-client` CLI.
- **switch-mcp** — headless, agent‑controllable tunnel client (`add_tunnel`).
- **Switchboard** — human‑UI tunnel client (Tunnel route type).

## License

MIT (planned), matching the XNet toolchain.
