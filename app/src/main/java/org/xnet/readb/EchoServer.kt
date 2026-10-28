package org.xnet.readb

import android.util.Log
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Dead-simple loopback TCP echo server for TESTING the encrypted tunnel without
 * depending on adbd.
 *
 * When a tunnel route targets this server (127.0.0.1:[PORT]), anything the box
 * sends through the tunnel is written straight back, so you can prove the
 * encrypted pipe carries bytes bidirectionally with plain `nc`:
 *
 *     echo hello | nc 127.0.0.1 17100   # on the box (bridge) -> "hello" comes back
 *
 * It greets each new connection with a banner so even a bare connect proves the
 * splice reached the phone.
 */
object EchoServer {
    const val PORT = 5599            // pinned loopback echo target for tunnel tests
    private const val TAG = "readb-echo"

    @Volatile private var server: ServerSocket? = null

    val isRunning: Boolean get() = server != null

    /** Idempotent: starts the echo listener on 127.0.0.1:[PORT] if not already up. */
    @Synchronized
    fun start() {
        if (server != null) return
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("127.0.0.1", PORT))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "bind failed on $PORT: ${t.message}")
            return
        }
        server = ss
        Log.i(TAG, "echo server listening on 127.0.0.1:$PORT")
        thread(name = "readb-echo-accept", isDaemon = true) {
            while (true) {
                val sock = try { ss.accept() } catch (_: Throwable) { break }
                handle(sock)
            }
            Log.i(TAG, "echo accept loop ended")
        }
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (_: Throwable) {}
        server = null
    }

    private fun handle(sock: Socket) {
        thread(name = "readb-echo-conn", isDaemon = true) {
            Log.i(TAG, "echo client connected: ${sock.remoteSocketAddress}")
            try {
                sock.tcpNoDelay = true
                val out = sock.getOutputStream()
                out.write("re-adb echo: connected. Anything you send is echoed back.\n".toByteArray())
                out.flush()
                val input = sock.getInputStream()
                val buf = ByteArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    out.flush()
                }
            } catch (t: Throwable) {
                Log.i(TAG, "echo client error: ${t.message}")
            } finally {
                try { sock.close() } catch (_: Throwable) {}
                Log.i(TAG, "echo client closed")
            }
        }
    }
}
