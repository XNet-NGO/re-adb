# re-adb

One Android app that combines **a rootless Alpine terminal**, **userspace
TCP/UDP port forwarding**, and an **encrypted tunnel** — so you can reach a
device's `adbd` (or any local service) from a remote box with **no TUN, no
root, and IP + a pre-shared key only** (no certificates, no PKI).

- **Platform:** Android, `compileSdk`/`targetSdk` 35, `minSdk` 26
- **Package:** `org.xnet.readb`
- **ABIs:** arm64‑v8a (primary), armeabi‑v7a, x86_64, x86

## What's in it

| Capability | How | Source lineage |
|---|---|---|
| **Alpine terminal** | Real PTY (Termux `terminal-emulator`, `libtermux.so` forkpty) running a rootless **Alpine 3.21** rootfs via **PRoot** (`libproot-xed.so`, `ptrace`-based chroot — no root). | ported from [ShellBox](https://github.com/XNet-NGO/shellbox) |
| **Port forwarding** | Userspace TCP/UDP forwards with selectable listen/target ports and optional **egress pinning** (Wi‑Fi / cellular, no root). | [portmux](https://github.com/XNet-NGO/portmux) via [Switchboard](https://github.com/XNet-NGO/switchboard) |
| **Encrypted tunnel** | Noise `NoisePSK_NN` (ephemeral X25519 + 32‑byte PSK; AES‑256‑GCM / ChaCha20‑Poly1305). The device dials out to a rendezvous server; traffic to the server's bridge port flows encrypted to a local target. | portmux v0.3.0 |
| **SOCKS5 proxy** | On‑device SOCKS5 (CONNECT + UDP ASSOCIATE), per‑connection egress pinning. | portmux |

All four run on one engine (`libreadb.so`, a JNI shim over portmux's static
libs) plus the PRoot/PTY natives — no `VpnService`, no root.

## Remote ADB (the headline use case)

```
operator box:  adb connect 127.0.0.1:BRIDGE
                   │  (plain, localhost)
               portmux tunnel-server  ◄──────── Noise/AES-GCM (one outbound
                   │ bridge                        conn from the phone)
                   └── relay ─────────────────────┐
                                                  ▼
                                      re-adb (tunnel client) ── plain splice ──▶ 127.0.0.1:5555 (adbd)
```

In the app: **Tunnels** tab → add a *Tunnel* route (server addr/port, 64‑hex
PSK, target `127.0.0.1:5555`) → **Start**. On the box run
`portmux tunnel-server <ip>:PORT 127.0.0.1:BRIDGE <pskfile>`, then
`adb connect 127.0.0.1:BRIDGE`. You can also drive the whole flow from the
Alpine terminal (`apk add android-tools`).

## UI

- **Terminal** tab — the Alpine shell (bootstraps the rootfs from
  [XNet-NGO/deps](https://github.com/XNet-NGO/deps) on first run, with a live
  log overlay).
- **Tunnels** tab — add/remove routes (Forward / SOCKS5 / Tunnel) and
  Start/Stop the foreground forwarder service.

## Build

Requires the Android SDK (platform 35) and NDK r28. Prebuilt artifacts are
fetched automatically by Gradle:

- **portmux** static libs (`v0.3.0`) from the portmux Releases →
  `app/.portmux/`, linked into `libreadb.so` by CMake.
- **PRoot** natives (`libproot-xed.so`, `libproot.so`, `libproot32.so`,
  `libtalloc.so`, `libbsdtar.so`) from `XNet-NGO/deps` `native-latest` →
  `jniLibs/arm64-v8a/`.
- The **Alpine rootfs** (`box-alpine-3.21-aarch64.tar.xz`) is downloaded at
  runtime from `XNet-NGO/deps` `rootfs-alpine-3.21.3`.

```sh
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease   # diagnostic (debuggable) release build
```

Toolchain: AGP 8.7, Kotlin 2.0, Gradle 8.13, JVM 17, Views (the terminal is a
native View; the tunnel panel is plain Views too).

## Security notes

- The tunnel PSK is stored in app preferences **in the clear** — fine for this
  local human‑UI app, not a secret store.
- PRoot is `ptrace`-based: it fakes root *inside* the container but grants no
  real privilege on the host OS.
- Per‑network egress pinning can be refused by a non‑bypassable (lockdown) VPN
  — that's OS policy; the forward then fails closed.

## License

PRoot is GPL‑2.0; portmux and the XNet components are MIT. See upstreams.
