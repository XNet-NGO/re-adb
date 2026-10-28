package org.xnet.readb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlin.concurrent.thread

/**
 * Foreground service that owns a portmux instance and runs its event loop on a
 * dedicated worker thread. No VpnService / system TUN is used.
 *
 * Routes are supplied once at start via the intent extra. Changing routes means
 * restarting the service (portmux routes are fixed at run()).
 */
class ForwarderService : Service() {

    private var handle: Long = 0L
    private var worker: Thread? = null
    private var requester: NetworkRequester? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand (worker=${worker != null})")
        if (worker != null) return START_STICKY

        val routes = try { RouteStore.load(this).filter { it.enabled } } catch (t: Throwable) {
            Log.e(TAG, "RouteStore.load failed", t); emptyList()
        }
        Log.i(TAG, "loaded ${routes.size} enabled route(s): ${routes.joinToString { it.summary() }}")
        if (routes.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!startForegroundSafely(routes.size)) {
            Log.e(TAG, "startForeground failed; aborting")
            stopSelf()
            return START_NOT_STICKY
        }
        Log.i(TAG, "entered foreground")

        val req = NetworkRequester(this)
        requester = req

        // Push live network-handle changes into the native CALLBACK egress so a
        // long-lived route/SOCKS proxy survives a Wi-Fi<->cellular handoff or a
        // revoked-then-regranted network: bind_hook reads the latest handle per
        // connection instead of a frozen one. Fail-closed on loss (handle 0).
        req.onHandleChanged = { transport, handle ->
            ReadbNative.nativeUpdateNetworkHandle(
                ReadbNative.tokenFor(transport), handle,
            )
        }

        // All portmux setup + run happens on the worker thread. resolveHandle()
        // may block (waiting for requestNetwork to resolve), so it must not run
        // on the main thread; and the hev-task system is thread-local, so
        // new/add/run belong together on one thread.
        worker = thread(name = "portmux-run") {
          try {
            val h = ReadbNative.nativeNew()
            if (h == 0L) {
                Log.e(TAG, "portmux_new failed")
                stopSelf()
                return@thread
            }
            handle = h

            routes.forEach { r ->
                val kind = when (r.egress) {
                    EgressMode.NONE -> ReadbNative.EGRESS_NONE
                    EgressMode.SOURCE -> ReadbNative.EGRESS_SOURCE
                    EgressMode.DEVICE -> ReadbNative.EGRESS_DEVICE
                    EgressMode.NETWORK -> ReadbNative.EGRESS_CALLBACK
                }
                // For NETWORK egress, request the chosen transport and use the
                // resolved handle (falls back to the stored handle on timeout).
                // The token groups this route for live handle updates.
                val netHandle: Long
                val token: Int
                if (r.egress == EgressMode.NETWORK) {
                    netHandle = req.resolveHandle(r.transport, r.networkHandle)
                    token = ReadbNative.tokenFor(r.transport)
                } else {
                    netHandle = 0L
                    token = 0
                }
                val rc = if (r.tunnel) {
                    // Encrypted tunnel client: dial server, splice to target.
                    // For an echo-test route, start the in-app loopback echo
                    // server and splice the tunnel to it instead of adbd.
                    val (tAddr, tPort) = if (r.echo) {
                        EchoServer.start()
                        "127.0.0.1" to EchoServer.PORT
                    } else {
                        r.targetAddr to r.targetPort
                    }
                    ReadbNative.nativeAddTunnelClient(
                        h, r.serverAddr, r.serverPort, tAddr, tPort,
                        r.psk, r.cipher, kind, r.egressArg, netHandle, token,
                    )
                } else if (r.socks) {
                    // SOCKS5 proxy: no fixed target; the same egress policy is
                    // applied to every upstream the proxy opens.
                    ReadbNative.nativeAddSocks(
                        h, r.listenAddr, r.listenPort, kind, r.egressArg, netHandle, token,
                    )
                } else {
                    val proto = if (r.proto == Proto.TCP) ReadbNative.PROTO_TCP
                    else ReadbNative.PROTO_UDP
                    ReadbNative.nativeAddRoute(
                        h, proto, r.listenAddr, r.listenPort,
                        r.targetAddr, r.targetPort, kind, r.egressArg, netHandle, token,
                    )
                }
                if (rc != 0) Log.w(TAG, "add route failed (rc=$rc): ${r.summary()}")
                else Log.i(TAG, "added route rc=0: ${r.summary()}")
            }

            Log.i(TAG, "forwarder run loop start")
            ReadbNative.nativeRun(h) // blocks until stop
            Log.i(TAG, "forwarder run loop exit")
          } catch (t: Throwable) {
            Log.e(TAG, "worker crashed", t)
          }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (handle != 0L) {
            ReadbNative.nativeStop(handle)
            worker?.join(3000)
            ReadbNative.nativeDestroy(handle)
            handle = 0L
        }
        worker = null
        requester?.releaseAll()
        requester = null
        EchoServer.stop()
        super.onDestroy()
    }

    /**
     * Enter the foreground with the correct service type. On API 34+ the type
     * must be passed to startForeground(); declaring it in the manifest alone
     * is not sufficient. Returns false if the platform refuses (caught so the
     * service can stop cleanly instead of crashing).
     */
    private fun startForegroundSafely(routeCount: Int): Boolean {
        val notif = buildNotification(routeCount)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground error: ${t.message}")
            false
        }
    }

    private fun buildNotification(routeCount: Int): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Forwarding",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while re-adb is forwarding ports"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

        return builder
            .setContentTitle("re-adb")
            .setContentText(
                if (routeCount == 1) "Forwarding 1 route"
                else "Forwarding $routeCount routes",
            )
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "ForwarderService"
        private const val CHANNEL_ID = "readb.forwarding"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) {
            val i = Intent(ctx, ForwarderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ctx.startForegroundService(i)
            else
                ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ForwarderService::class.java))
        }
    }
}
