# Engine port notes (portmux tunneling + port-forwarding engine)

Ported from `org.xnet.switchboard` (reference app at `/home/ubuntu/projects/switchboard`)
into `org.xnet.readb` (re-adb). Only the non-UI engine/service/model was ported; the
integrator owns `MainActivity` + `AndroidManifest` + the unified UI.

portmux prebuilt: **v0.3.0** (fetched by `app/build.gradle.kts` into `app/.portmux/`).
noise-c is archived inside `libportmux.a` v0.3.0 — no extra link lib.

## Files ported

### Native (`app/src/main/cpp/`)
| re-adb file            | source (switchboard)   | change |
|------------------------|------------------------|--------|
| `readb-jni.c`          | `switchboard-jni.c`    | JNI symbols renamed `Java_org_xnet_switchboard_SwitchboardNative_*` → `Java_org_xnet_readb_ReadbNative_*`; `TAG "readb-jni"`. Logic identical. |
| `xnix-netd-client.c`   | `xnix-netd-client.c`   | verbatim (comments mention re-adb). |
| `CMakeLists.txt`       | `CMakeLists.txt`       | `project(readb C)`, library `readb` (`System.loadLibrary("readb")`), builds `readb-jni.c` + `xnix-netd-client.c`. |

Native pieces kept intact in `readb-jni.c`:
- `build_egress` (NONE / SOURCE / DEVICE=PROVIDED_FD / CALLBACK)
- live-handle registry: `EgressCtx { _Atomic net_handle_t; int token }`,
  `ctx_registry_reset/add/update`, `MAX_EGRESS_CTX`, `g_ctx_lock` mutex
- `nativeUpdateNetworkHandle`
- `bind_hook` using `android_setsocknetwork` (fail-closed when token>0 and handle==0)
- `provide_pinned_fd` → `xnd_request_socket` (xnix-netd via SCM_RIGHTS)
- `nativeNew` / `nativeAddRoute` / `nativeAddSocks` / `nativeAddTunnelClient` /
  `nativeRun` / `nativeStop` / `nativeDestroy`
- `parse_psk_hex` (64 hex → 32 bytes, `PM_TUNNEL_PSK_LEN`)
- includes **both** `portmux.h` and `portmux-tunnel.h`

CMake link: `target_link_libraries(readb portmux hevtask android log)`;
imports `${PORTMUX_LIBS_DIR}/${ANDROID_ABI}/libportmux.a` + `libhev-task-system.a`;
include `${PORTMUX_INCLUDE_DIR}`.

### Kotlin (`app/src/main/java/org/xnet/readb/`)
| re-adb file             | source                  | change |
|-------------------------|-------------------------|--------|
| `ReadbNative.kt`        | `SwitchboardNative.kt`  | `object ReadbNative`, `System.loadLibrary("readb")`, package renamed. All externals + consts + `tokenFor`. |
| `Route.kt`              | `Route.kt`              | package renamed. Proto/EgressMode/NetTransport enums + Route data class (tunnel/socks/egress fields + `summary()`). |
| `RouteStore.kt`         | `RouteStore.kt`         | package renamed; prefs name `readb.routes`. Tolerant JSON load incl tunnel/socks fields. |
| `NetworkRepository.kt`  | `NetworkRepository.kt`  | package renamed. |
| `NetworkRequester.kt`   | `NetworkRequester.kt`   | package renamed; `onHandleChanged` live-handle callback. |
| `ForwarderService.kt`   | `ForwarderService.kt`   | package renamed; `ReadbNative.*`; channel id `readb.forwarding`; notification title "re-adb"; tap intent → `org.xnet.readb.MainActivity` (same package, referenced by class — integrator creates it). Branch dispatch tunnel/socks/forward + live-handle wiring + FGS (SPECIAL_USE type on API 34+). |

## Exact external signatures (`ReadbNative`)

```kotlin
external fun nativeNew(): Long

external fun nativeAddRoute(
    handle: Long, proto: Int,
    listenAddr: String, listenPort: Int,
    targetAddr: String, targetPort: Int,
    egressKind: Int, egressArg: String,
    networkHandle: Long, token: Int,
): Int

external fun nativeAddSocks(
    handle: Long,
    listenAddr: String, listenPort: Int,
    egressKind: Int, egressArg: String,
    networkHandle: Long, token: Int,
): Int

external fun nativeAddTunnelClient(
    handle: Long,
    serverAddr: String, serverPort: Int,
    targetAddr: String, targetPort: Int,
    pskHex: String, cipher: Int,
    egressKind: Int, egressArg: String,
    networkHandle: Long, token: Int,
): Int

external fun nativeUpdateNetworkHandle(token: Int, netHandle: Long)
external fun nativeRun(handle: Long)
external fun nativeStop(handle: Long)
external fun nativeDestroy(handle: Long)
```

Constants: `EGRESS_NONE=0, EGRESS_SOURCE=1, EGRESS_DEVICE=2, EGRESS_CALLBACK=3`,
`PROTO_TCP=0, PROTO_UDP=1`. `tokenFor(transport) = transport.ordinal + 1` (>0 ⇒ pinned).

## C ↔ Kotlin signature parity (verified against portmux.h / portmux-tunnel.h v0.3.0)

- `nativeAddRoute`: JNI `(env,cls,jlong,jint,jstring,jint,jstring,jint,jint,jstring,jlong,jint)`
  ↔ Kotlin `(Long,Int,String,Int,String,Int,Int,String,Long,Int)` — 10 app args ✓
- `nativeAddSocks`: JNI `(env,cls,jlong,jstring,jint,jint,jstring,jlong,jint)`
  ↔ Kotlin `(Long,String,Int,Int,String,Long,Int)` — 7 app args ✓
- `nativeAddTunnelClient`: JNI `(env,cls,jlong,jstring,jint,jstring,jint,jstring,jint,jint,jstring,jlong,jint)`
  ↔ Kotlin `(Long,String,Int,String,Int,String,Int,Int,String,Long,Int)` — 11 app args ✓
- `nativeUpdateNetworkHandle(jint token, jlong netHandle)` ↔ `(Int, Long)` ✓
- `portmux_add_tunnel_client(self, NULL, 0, server, sport, target, tport, psk[32], cipher, egress)` matches header.
- `PortmuxEgress` fields used: `kind, source_ip, provider, callback, callback_data` — all present in header.
- Enums: `PORTMUX_PROTO_TCP/UDP`, `PORTMUX_EGRESS_SOURCE/PROVIDED_FD/CALLBACK`,
  `PM_TUNNEL_CIPHER_AESGCM/CHACHAPOLY`, `PM_TUNNEL_PSK_LEN=32` — all present.

## Integrator notes
- `ForwarderService.start(ctx)` / `ForwarderService.stop(ctx)` — start/stop the FGS.
- Routes are read from `RouteStore` at service start (fixed for the run; restart to change).
- Requires an `org.xnet.readb.MainActivity` (for the notification tap intent) — referenced only.
- Manifest must declare `ForwarderService` with `foregroundServiceType="specialUse"` and
  the matching FGS + `POST_NOTIFICATIONS` / `CHANGE_NETWORK_STATE` permissions (integrator owns manifest).
