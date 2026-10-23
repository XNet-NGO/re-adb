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
        val client = ReadbClient(terminalView, onRedraw = { terminalView.onScreenUpdated() })
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
        } else {
            if (RouteStore.load(this).isEmpty()) { tunnelStatus.text = "Add a route first"; return }
            ForwarderService.start(this); running = true
            btn.text = "Stop"; btn.setTextColor(0xFFF44336.toInt())
        }
        updateStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.finishIfRunning()
    }
}
