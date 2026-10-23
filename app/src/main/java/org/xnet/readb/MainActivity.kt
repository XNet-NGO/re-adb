package org.xnet.readb

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xnet.readb.shell.ProotBootstrap
import org.xnet.readb.terminal.ReadbClient
import org.xnet.readb.terminal.ReadbTerminal
import org.xnet.readb.terminal.view.TerminalView

/**
 * re-adb main screen, one combined view:
 *   • top 1/3 — Tunnels & ports: simple accordion forms (Forward / SOCKS5 /
 *     Tunnel) with validated inputs, an active-routes list, and Start/Stop.
 *   • bottom 2/3 — a PTY terminal running rootless Alpine (PRoot); first run
 *     bootstraps the Alpine rootfs from XNet-NGO/deps.
 * Edge-to-edge, with system-bar insets applied as padding.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var terminalView: TerminalView
    private lateinit var tunnelStatus: TextView
    private lateinit var bootstrapOverlay: ScrollView
    private lateinit var bootstrapLog: TextView
    private lateinit var panel: TunnelPanel
    private var session: TerminalSession? = null
    private var running = false
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        terminalView = findViewById(R.id.terminalView)
        tunnelStatus = findViewById(R.id.tunnelStatus)
        bootstrapOverlay = findViewById(R.id.bootstrapOverlay)
        bootstrapLog = findViewById(R.id.bootstrapLog)

        panel = TunnelPanel(findViewById(R.id.routeList)) { updateStatus() }
        panel.render()

        findViewById<Button>(R.id.btnStartStop).setOnClickListener { toggleForwarder(it as Button) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        bootstrapAndStartTerminal()
    }

    // ── Terminal + Alpine bootstrap ──
    private fun bootstrapAndStartTerminal() {
        if (ProotBootstrap.isInstalled(this)) { startTerminal(); return }
        bootstrapOverlay.visibility = View.VISIBLE
        appendLog("re-adb: first run — bootstrapping Alpine (PRoot)...\n")
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                ProotBootstrap.setup(this@MainActivity) { line -> scope.launch { appendLog("$line\n") } }
            }
            if (ok) {
                appendLog("\nAlpine ready. Launching shell...\n")
                bootstrapOverlay.visibility = View.GONE
                startTerminal()
            } else {
                appendLog("\nBootstrap FAILED — see log above.\n")
            }
        }
    }

    private fun startTerminal() {
        val client = ReadbClient(
            terminalView,
            onRedraw = { terminalView.onScreenUpdated() },
            ctrlDown = { ctrlArmedOrLocked() },
            altDown = { altArmedOrLocked() },
        )
        val s = try {
            ReadbTerminal.newAlpineSession(this, client)
        } catch (t: Throwable) {
            appendLog("terminal error: ${t.message}\n"); bootstrapOverlay.visibility = View.VISIBLE
            return
        }
        session = s
        terminalView.setTerminalViewClient(client)
        terminalView.attachSession(s)
        terminalView.setTextSize(28)
        terminalView.requestFocus()
        setupExtraKeys()
    }

    private fun appendLog(s: String) { bootstrapLog.append(s) }

    // ── Tunnels / ports ──
    private fun updateStatus() {
        val n = RouteStore.load(this).size
        tunnelStatus.text = if (running) "Running · $n route(s)" else "Stopped · $n route(s)"
    }

    private fun toggleForwarder(btn: Button) {
        if (running) {
            ForwarderService.stop(this); running = false
            btn.text = "Start"; btn.setTextColor(0xFF4CAF50.toInt())
            updateStatus()
        } else {
            val routes = RouteStore.load(this)
            if (routes.isEmpty()) { tunnelStatus.text = "Add a route first"; return }
            ForwarderService.start(this); running = true
            btn.text = "Stop"; btn.setTextColor(0xFFF44336.toInt())
            updateStatus()
            // For tunnel routes, probe the server's reachability so the UI can
            // tell "connected-ish" from "can't reach server" (firewall/NAT).
            routes.firstOrNull { it.tunnel }?.let { probeTunnel(it) }
        }
    }

    /** Best-effort TCP reachability probe to the tunnel server, off the main
     *  thread. Updates the status line; does NOT prove the Noise handshake, only
     *  that the server port is reachable (the common failure is it is not). */
    private fun probeTunnel(r: Route) {
        tunnelStatus.text = "Tunnel: reaching ${r.serverAddr}:${r.serverPort}…"
        scope.launch {
            val reachable = withContext(Dispatchers.IO) {
                try {
                    java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress(r.serverAddr, r.serverPort), 6000)
                        true
                    }
                } catch (_: Throwable) { false }
            }
            tunnelStatus.text = if (reachable)
                "Tunnel: ${r.serverAddr}:${r.serverPort} reachable — handshaking"
            else
                "Tunnel: CANNOT reach ${r.serverAddr}:${r.serverPort} (server down or port closed/firewalled)"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.finishIfRunning()
    }

    // ── Extra keys row (ported from ShellBox) ──
    private enum class StickyState { IDLE, ARMED, LOCKED }
    private data class StickyKey(
        val label: String, val isModifier: Boolean, val seq: String = "",
        var state: StickyState = StickyState.IDLE, var view: TextView? = null,
    )

    private val stickyKeys = listOf(
        StickyKey("ESC", false, "\u001b"),
        StickyKey("TAB", false, "\t"),
        StickyKey("CTRL", true),
        StickyKey("ALT", true),
        StickyKey("↑", false, "\u001b[A"),
        StickyKey("↓", false, "\u001b[B"),
        StickyKey("←", false, "\u001b[D"),
        StickyKey("→", false, "\u001b[C"),
        StickyKey("HOME", false, "\u001b[H"),
        StickyKey("END", false, "\u001b[F"),
        StickyKey("DEL", false, "\u001b[3~"),
    )

    private fun ctrlArmedOrLocked() = stickyKeys.first { it.label == "CTRL" }.state != StickyState.IDLE
    private fun altArmedOrLocked() = stickyKeys.first { it.label == "ALT" }.state != StickyState.IDLE

    private fun refreshSticky(k: StickyKey) {
        k.view?.setBackgroundColor(
            when (k.state) {
                StickyState.IDLE -> 0xFF0F1729.toInt()
                StickyState.ARMED -> 0xFF1A3A1A.toInt()
                StickyState.LOCKED -> 0xFF3A1A1A.toInt()
            }
        )
        k.view?.setTextColor(
            when (k.state) {
                StickyState.IDLE -> 0xFFF8FAFC.toInt()
                StickyState.ARMED -> 0xFF88FF88.toInt()
                StickyState.LOCKED -> 0xFFFF8888.toInt()
            }
        )
    }

    private fun setupExtraKeys() {
        val row = findViewById<LinearLayout>(R.id.extraKeys)
        row.removeAllViews()
        val d = resources.displayMetrics.density
        stickyKeys.forEach { key ->
            val tv = TextView(this).apply {
                text = key.label; textSize = 12f
                setTextColor(0xFFF8FAFC.toInt()); gravity = android.view.Gravity.CENTER
                typeface = android.graphics.Typeface.MONOSPACE
                setBackgroundColor(0xFF0F1729.toInt())
                layoutParams = LinearLayout.LayoutParams(0, (40 * d).toInt(), 1f).apply {
                    marginEnd = (1 * d).toInt()
                }
                setOnClickListener {
                    if (key.isModifier) {
                        key.state = when (key.state) {
                            StickyState.IDLE -> StickyState.ARMED
                            StickyState.ARMED -> StickyState.LOCKED
                            StickyState.LOCKED -> StickyState.IDLE
                        }
                        refreshSticky(key)
                    } else {
                        sendKey(key.seq)
                    }
                }
            }
            key.view = tv
            row.addView(tv)
        }
    }

    private fun sendKey(data: String) {
        val s = session ?: return
        val ctrl = stickyKeys.first { it.label == "CTRL" }
        val alt = stickyKeys.first { it.label == "ALT" }
        if (ctrl.state != StickyState.IDLE && data.length == 1) {
            val code = data[0].lowercaseChar().code - 'a'.code + 1
            if (code in 1..26) s.write(byteArrayOf(code.toByte()), 0, 1) else s.write(data)
        } else {
            s.write(data)
        }
        // clear ARMED (single-shot) modifiers after use
        listOf(ctrl, alt).forEach { if (it.state == StickyState.ARMED) { it.state = StickyState.IDLE; refreshSticky(it) } }
    }
}
