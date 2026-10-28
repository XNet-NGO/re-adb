package org.xnet.readb

/**
 * Thin Kotlin wrapper over the portmux JNI bridge.
 *
 * The native `portmux_run` blocks, so [nativeRun] must be called on a dedicated
 * worker thread. All other calls are cheap and thread-safe to issue from
 * another thread (stop writes to an eventfd; create/destroy are plain).
 *
 * Network egress pinning is handled entirely in native code via
 * android_setsocknetwork(); the [Route.networkHandle] (from
 * Network.getNetworkHandle()) is passed straight through to nativeAddRoute.
 */
object ReadbNative {

    init {
        System.loadLibrary("readb")
    }

    // Egress kinds mirrored from portmux.h.
    const val EGRESS_NONE = 0
    const val EGRESS_SOURCE = 1
    const val EGRESS_DEVICE = 2
    const val EGRESS_CALLBACK = 3

    // Proto values mirrored from portmux.h (0 TCP, 1 UDP).
    const val PROTO_TCP = 0
    const val PROTO_UDP = 1

    // Reverse-listener proto for an edge/reverse tunnel client (0 TCP, 1 UDP,
    // 2 BOTH dual-stack); mirrored from portmux-tunnel.h.
    const val RPROTO_TCP = 0
    const val RPROTO_UDP = 1
    const val RPROTO_BOTH = 2

    // Max jump-list length (PM_MUX_MAX_HOPS); mirrored from portmux-tunnel.h.
    const val MAX_HOPS = 8

    /**
     * Token grouping CALLBACK (NETWORK-egress) routes that share a transport,
     * so a live network-handle update ([nativeUpdateNetworkHandle]) reaches all
     * of them. Must be > 0 for a pinned route (0 means "no pin" on the native
     * side, which allows the default network). Derived from the transport
     * ordinal; NetworkRequester keys its requests by transport too, so one
     * token == one requested Network.
     */
    fun tokenFor(transport: NetTransport): Int = transport.ordinal + 1

    external fun nativeNew(): Long
    external fun nativeAddRoute(
        handle: Long,
        proto: Int,
        listenAddr: String,
        listenPort: Int,
        targetAddr: String,
        targetPort: Int,
        egressKind: Int,
        egressArg: String,
        networkHandle: Long,
        token: Int,
    ): Int

    /**
     * Register a SOCKS5 proxy listener. No fixed target: each SOCKS client
     * supplies the destination per connection. The same egress policy is
     * applied to every upstream socket the proxy opens, so network pinning
     * (CALLBACK egress) works exactly like a normal route — this is the
     * no-root "SOCKS over cellular/wifi" path.
     *
     * For NETWORK egress pass a [token] (> 0) identifying the transport group;
     * the handle can then be refreshed live via [nativeUpdateNetworkHandle]
     * when the pinned network is lost/regained mid-session (a long-lived SOCKS
     * proxy spans many connections).
     */
    external fun nativeAddSocks(
        handle: Long,
        listenAddr: String,
        listenPort: Int,
        egressKind: Int,
        egressArg: String,
        networkHandle: Long,
        token: Int,
    ): Int

    /**
     * Register an encrypted tunnel CLIENT (Noise NoisePSK_NN): dial
     * [serverAddr]:[serverPort], authenticate with [pskHex] (64 hex chars /
     * 32 bytes), and splice the decrypted stream to [targetAddr]:[targetPort]
     * (e.g. 127.0.0.1:5555 = adbd). [cipher] 0=AES-256-GCM, 1=ChaCha20-Poly1305.
     * The outbound leg honors the same egress surface (incl. the no-root
     * NETWORK pin with live handle refresh).
     *
     * REVERSE/EDGE (portmux v0.4.0/v0.5.0): when [rlistenPort] > 0 the client
     * opens a local plaintext REVERSE LISTENER on [rlistenAddr]:[rlistenPort].
     * For plain reverse (ssh -R) the server splices it to [rtargetAddr]:
     * [rtargetPort]; for a v0.5.0 JUMP route this listener is the local entry
     * that emits OPENJ — configure the jump-list with [nativeSetJumps] right
     * after this call. [rproto] is one of [RPROTO_TCP]/[RPROTO_UDP]/[RPROTO_BOTH].
     * Pass [rlistenPort] = 0 (and the rest empty/0) for a forward-only single
     * hop (backward compatible).
     */
    external fun nativeAddTunnelClient(
        handle: Long,
        serverAddr: String,
        serverPort: Int,
        targetAddr: String,
        targetPort: Int,
        pskHex: String,
        cipher: Int,
        egressKind: Int,
        egressArg: String,
        networkHandle: Long,
        token: Int,
        rlistenAddr: String,
        rlistenPort: Int,
        rtargetAddr: String,
        rtargetPort: Int,
        rproto: Int,
    ): Int

    /**
     * Register an encrypted tunnel PEER (portmux v0.5.0 multi-hop): a single
     * (peek-demux) port server on [listenAddr]:[listenPort] that relays an
     * OPENJ stream onward to the next hop (dialed on demand, honoring the
     * egress) or connects the final target when the jump-list is terminal.
     * Shares the single [pskHex] (64 hex chars)/[cipher] with every peer in the
     * chain. [cipher] 0=AES-256-GCM, 1=ChaCha20-Poly1305. The egress surface
     * matches the other calls (incl. the no-root NETWORK pin for next-hop dials).
     */
    external fun nativeAddTunnelPeer(
        handle: Long,
        listenAddr: String,
        listenPort: Int,
        pskHex: String,
        cipher: Int,
        egressKind: Int,
        egressArg: String,
        networkHandle: Long,
        token: Int,
    ): Int

    /**
     * Configure the EDGE originator's jump-list (portmux v0.5.0) on the
     * most-recently-added tunnel route — call right after the edge
     * [nativeAddTunnelClient] (which MUST have been given a reverse listener,
     * rlistenPort > 0). [hopsAddrCsv]/[hopsPortCsv] are parallel comma-separated
     * ordered next-hops; the first hop MUST equal that client's carrier dest
     * (serverAddr:serverPort). [targetAddr]:[targetPort] is the final
     * destination; [proto] is [PROTO_TCP]/[PROTO_UDP] for the final leg. The
     * hop count must be in 1..[MAX_HOPS].
     */
    external fun nativeSetJumps(
        handle: Long,
        hopsAddrCsv: String,
        hopsPortCsv: String,
        targetAddr: String,
        targetPort: Int,
        proto: Int,
    ): Int

    /**
     * Push a live network-handle update for every CALLBACK route sharing
     * [token]. Pass [netHandle] = 0 to mark the transport unavailable, which
     * makes new upstream connections for that token fail closed (no leak to the
     * default network) until a fresh handle arrives. Safe to call from any
     * thread (the native side updates an atomic under a mutex); may be called
     * on a running engine.
     */
    external fun nativeUpdateNetworkHandle(token: Int, netHandle: Long)

    external fun nativeRun(handle: Long)
    external fun nativeStop(handle: Long)
    external fun nativeDestroy(handle: Long)
}
