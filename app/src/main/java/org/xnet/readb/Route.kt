package org.xnet.readb

import java.util.UUID

enum class Proto { TCP, UDP }

enum class EgressMode {
    /** Default route / loopback. */
    NONE,

    /** Bind upstream socket to a local source IP ([Route.egressArg]). */
    SOURCE,

    /** SO_BINDTODEVICE to interface name ([Route.egressArg]); needs root/CAP_NET_RAW. */
    DEVICE,

    /** Pin to an Android Network via android_setsocknetwork (no root). */
    NETWORK,
}

/** Transport to request for a NETWORK-egress route. ANY uses the specific
 *  [Route.networkHandle] as-is without requesting a particular transport. */
enum class NetTransport { ANY, WIFI, CELLULAR, ETHERNET }

/**
 * A single forwarding rule: expose [listenAddr]:[listenPort] and forward to
 * [targetAddr]:[targetPort], optionally pinning the egress path.
 */
data class Route(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val proto: Proto = Proto.TCP,
    val listenAddr: String = "127.0.0.1",
    val listenPort: Int = 0,
    val targetAddr: String = "127.0.0.1",
    val targetPort: Int = 0,
    val egress: EgressMode = EgressMode.NONE,
    val egressArg: String = "",
    /** Opaque Android Network handle for EgressMode.NETWORK (0 = none).
     *  Used as a fallback/hint; the service prefers a freshly requested
     *  network of [transport] when that resolves. */
    val networkHandle: Long = 0L,
    /** For EgressMode.NETWORK: which transport to requestNetwork() for. */
    val transport: NetTransport = NetTransport.ANY,
    /** True if this route is a SOCKS5 proxy listener (no fixed target; the
     *  destination is supplied per-connection by the SOCKS client). The
     *  [proto] and [targetAddr]/[targetPort] fields are ignored for SOCKS. */
    val socks: Boolean = false,
    /** True if this route is an encrypted tunnel CLIENT (NoisePSK_NN): dials
     *  [serverAddr]:[serverPort] and splices to [targetAddr]:[targetPort].
     *  [psk] is 64 hex chars (stored in prefs — see note below); [cipher]
     *  0=AES-256-GCM, 1=ChaCha20-Poly1305. NOTE: the PSK is persisted in
     *  SharedPreferences in the clear, acceptable for this local human-UI app
     *  but not a secret store. */
    val tunnel: Boolean = false,
    val serverAddr: String = "",
    val serverPort: Int = 0,
    val psk: String = "",
    val cipher: Int = 0,
    /** True if this route is a multi-hop EDGE tunnel client (portmux v0.5.0):
     *  it opens a local plaintext reverse listener on [jumpListenPort] that
     *  emits OPENJ built from [jumpHops] + the final target
     *  ([targetAddr]:[targetPort], [jumpProto]). The carrier dials the first
     *  hop; [serverAddr]:[serverPort] MUST equal that first hop. */
    val jump: Boolean = false,
    /** Ordered next-hops as "addr:port" strings (1..8). hops[0] is the carrier
     *  dest and MUST equal [serverAddr]:[serverPort]. */
    val jumpHops: List<String> = emptyList(),
    /** Local plaintext reverse-listener port for the edge OPENJ entry (>0). */
    val jumpListenPort: Int = 0,
    /** Final-leg proto for a jump route: 0=TCP, 1=UDP. */
    val jumpProto: Int = 0,
    /** True if this route is a relay PEER (portmux v0.5.0): a single-port
     *  peek-demux server on [listenAddr]:[listenPort] that relays OPENJ to the
     *  next hop or connects the terminal target. Shares [psk]/[cipher] with the
     *  rest of the chain. */
    val peer: Boolean = false,
    /** TESTING: if true this tunnel targets the in-app loopback echo server
     *  (127.0.0.1:[EchoServer.PORT]) instead of adbd, so the encrypted pipe can
     *  be verified end-to-end with plain `nc` on the box. */
    val echo: Boolean = false,
    /** True if this jump route is a REVERSE EDGE (portmux v0.7.0 OPENR): the
     *  phone dials OUT the carrier chain ([jumpHops]) but does NOT open a local
     *  entry listener. Instead the TERMINAL peer (far end, e.g. the runner's
     *  PM_RLISTEN :2222) accepts `adb connect` and emits OPENR back up the
     *  chain; this edge connects its reverse target ([targetAddr]:[targetPort],
     *  the phone's own adbd 127.0.0.1:5555) when OPENR arrives. Only meaningful
     *  with [jump]=true. When false, a jump route is a FORWARD edge (opens a
     *  local [jumpListenPort] entry and the terminal dials the target). */
    val reverseEdge: Boolean = false,
    /** Whether this route participates when the forwarder runs. Per-route
     *  Start/Stop toggles this; the service only runs enabled routes. */
    val enabled: Boolean = true,
) {
    fun summary(): String {
        val eg = when (egress) {
            EgressMode.NONE -> ""
            EgressMode.SOURCE -> "  src=$egressArg"
            EgressMode.DEVICE -> "  dev=$egressArg"
            EgressMode.NETWORK -> "  via ${transport.name.lowercase()}"
        }
        if (peer) {
            return "peer    $listenAddr:$listenPort  (relay, encrypted)$eg"
        }
        if (jump) {
            val tgt = if (echo) "echo-test" else "$targetAddr:$targetPort"
            if (reverseEdge) {
                // A reverse edge carries data the OPPOSITE way to the carrier-
                // dial order: this device dials out along jumpHops[0..n], but an
                // `adb connect` enters at the TERMINAL (the LAST hop) and flows
                // back up the chain to THIS device's reverse target (adbd). Show
                // it in that real reverse-flow direction so the list isn't read
                // backwards: terminal ⇐ relays ⇐ this device.
                val revFlow = jumpHops.reversed().joinToString(" ← ")
                return "rjump   adb@[$revFlow] ← this:$tgt  (reverse, encrypted OPENR)$eg"
            }
            val chain = jumpHops.joinToString(" → ")
            return "jump    :$jumpListenPort ⇒ $chain ⇒ $tgt  (encrypted)$eg"
        }
        if (tunnel) {
            val tgt = if (echo) "echo-test" else "$targetAddr:$targetPort"
            return "tunnel  → $serverAddr:$serverPort ⇒ $tgt  (encrypted)$eg"
        }
        if (socks) {
            return "socks5  $listenAddr:$listenPort$eg"
        }
        val p = if (proto == Proto.TCP) "tcp" else "udp"
        return "$p  $listenAddr:$listenPort → $targetAddr:$targetPort$eg"
    }
}
