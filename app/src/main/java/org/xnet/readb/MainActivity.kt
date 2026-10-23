package org.xnet.readb

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
 * re-adb main screen. Two sections toggled by the top bar:
 *   • Terminal — a PTY running rootless Alpine (PRoot). First run bootstraps
 *     the Alpine rootfs from XNet-NGO/deps.
 *   • Tunnels — manage portmux routes: plain forwards, a SOCKS5 proxy, or an
 *     encrypted NoisePSK_NN tunnel (the remote-ADB path), with Start/Stop.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var terminalView: TerminalView
    private lateinit var tunnelStatus: TextView
    private lateinit var routeList: LinearLayout
    private lateinit var bootstrapOverlay: ScrollView
    private lateinit var bootstrapLog: TextView
    private var session: TerminalSession? = null
    private var running = false
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: draw behind the system bars, then pad the root by the
        // bar insets so content isn't occluded.
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
        routeList = findViewById(R.id.routeList)
        bootstrapOverlay = findViewById(R.id.bootstrapOverlay)
        bootstrapLog = findViewById(R.id.bootstrapLog)

        // Single combined view (no tabs): tunnels on top 1/3, terminal bottom 2/3.
        findViewById<Button>(R.id.btnStartStop).setOnClickListener { toggleForwarder(it as Button) }
        findViewById<Button>(R.id.btnAddRoute).setOnClickListener { showAddDialog() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        bootstrapAndStartTerminal()
        refreshRoutes()
    }

    // ── Terminal + Alpine bootstrap ──
    private fun bootstrapAndStartTerminal() {
        if (ProotBootstrap.isInstalled(this)) {
            startTerminal()
            return
        }
        bootstrapOverlay.visibility = View.VISIBLE
        appendLog("re-adb: first run — bootstrapping Alpine (PRoot)...\n")
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                ProotBootstrap.setup(this@MainActivity) { line ->
                    scope.launch { appendLog(line + "\n") }
                }
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
    private fun refreshRoutes() {
        routeList.removeAllViews()
        val routes = RouteStore.load(this)
        if (routes.isEmpty()) {
            routeList.addView(TextView(this).apply {
                text = "No routes. Tap + to add a forward, SOCKS5 proxy, or encrypted tunnel."
                setTextColor(0xFF888888.toInt()); setPadding(24, 24, 24, 24)
            })
            return
        }
        routes.forEach { r ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(16, 16, 16, 16)
            }
            row.addView(TextView(this).apply {
                text = r.summary(); setTextColor(0xFFDDDDDD.toInt())
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            row.addView(Button(this).apply {
                text = "✕"; setOnClickListener {
                    RouteStore.save(this@MainActivity, RouteStore.load(this@MainActivity).filter { it.id != r.id })
                    refreshRoutes()
                }
            })
            routeList.addView(row)
        }
    }

    private fun toggleForwarder(btn: Button) {
        if (running) {
            ForwarderService.stop(this); running = false
            btn.text = "Start"; btn.setTextColor(0xFF4CAF50.toInt()); tunnelStatus.text = "Stopped"
        } else {
            if (RouteStore.load(this).isEmpty()) { tunnelStatus.text = "No routes to start"; return }
            ForwarderService.start(this); running = true
            btn.text = "Stop"; btn.setTextColor(0xFFF44336.toInt())
            tunnelStatus.text = "Running (${RouteStore.load(this).size} route(s))"
        }
    }

    private fun showAddDialog() {
        val ctx = this
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 0) }
        fun field(hint: String, value: String = "", number: Boolean = false): EditText =
            EditText(ctx).apply {
                this.hint = hint; setText(value)
                if (number) inputType = InputType.TYPE_CLASS_NUMBER
            }.also { root.addView(it) }

        val typeSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                listOf("Forward", "SOCKS5 proxy", "Tunnel (encrypted)"))
        }
        root.addView(TextView(ctx).apply { text = "Type" }); root.addView(typeSpinner)

        val name = field("Name (optional)")
        val listenPort = field("Listen port (forward/socks)", number = true)
        val targetAddr = field("Target / local addr", "127.0.0.1")
        val targetPort = field("Target port (e.g. 5555 adbd)", number = true)
        val serverAddr = field("Tunnel server addr")
        val serverPort = field("Tunnel server port", number = true)
        val psk = field("Tunnel PSK (64 hex)")

        AlertDialog.Builder(ctx)
            .setTitle("Add route")
            .setView(ScrollView(ctx).apply { addView(root) })
            .setPositiveButton("Add") { _, _ ->
                val routes = RouteStore.load(ctx).toMutableList()
                val r = when (typeSpinner.selectedItemPosition) {
                    1 -> Route(
                        name = name.text.toString(), socks = true,
                        listenAddr = "127.0.0.1",
                        listenPort = listenPort.text.toString().toIntOrNull() ?: 1080,
                    )
                    2 -> Route(
                        name = name.text.toString(), tunnel = true,
                        serverAddr = serverAddr.text.toString(),
                        serverPort = serverPort.text.toString().toIntOrNull() ?: 0,
                        targetAddr = targetAddr.text.toString().ifBlank { "127.0.0.1" },
                        targetPort = targetPort.text.toString().toIntOrNull() ?: 5555,
                        psk = psk.text.toString().trim(),
                    )
                    else -> Route(
                        name = name.text.toString(),
                        listenAddr = "127.0.0.1",
                        listenPort = listenPort.text.toString().toIntOrNull() ?: 0,
                        targetAddr = targetAddr.text.toString().ifBlank { "127.0.0.1" },
                        targetPort = targetPort.text.toString().toIntOrNull() ?: 0,
                    )
                }
                routes.add(r); RouteStore.save(ctx, routes); refreshRoutes()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.finishIfRunning()
    }
}
