package org.xnet.readb

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xnet.readb.shell.ProotBootstrap
import org.xnet.readb.shell.ProotExecutor

/**
 * re-adb main screen, one no-scroll view:
 *   • top — Tunnels & ports: the saved-routes list fills the screen so every
 *     tunnel/port is visible at once (no scrolling), plus Start/Stop.
 *   • bottom — a compact ONE-SHOT Alpine prompt: type a command, Run, and a
 *     small confirmation window shows its output. No interactive PTY.
 *
 * The one-shot model (ProotExecutor.exec) replaces the full Termux terminal:
 * it reclaims ~2/3 of the screen for the tunnel UI and is all most operators
 * need (apk add, quick checks, scripts). Non-interactive automation still goes
 * through AlpineCommandReceiver over adb.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tunnelStatus: TextView
    private lateinit var bootstrapOverlay: ScrollView
    private lateinit var bootstrapLog: TextView
    private lateinit var cmdInput: EditText
    private lateinit var cmdOutput: TextView
    private lateinit var cmdOutputScroll: ScrollView
    private lateinit var btnRun: Button
    private lateinit var panel: TunnelPanel
    private var running = false
    private var alpineReady = false
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

        tunnelStatus = findViewById(R.id.tunnelStatus)
        bootstrapOverlay = findViewById(R.id.bootstrapOverlay)
        bootstrapLog = findViewById(R.id.bootstrapLog)
        cmdInput = findViewById(R.id.cmdInput)
        cmdOutput = findViewById(R.id.cmdOutput)
        cmdOutputScroll = findViewById(R.id.cmdOutputScroll)
        btnRun = findViewById(R.id.btnRun)

        panel = TunnelPanel(
            findViewById(R.id.routeList),
            onChanged = { updateStatus() },
            onRestart = { restartForwarder() },
            isRunning = { running },
        )
        panel.render()

        findViewById<Button>(R.id.btnStartStop).setOnClickListener { toggleForwarder(it as Button) }

        // one-shot prompt wiring
        btnRun.setOnClickListener { runCommand() }
        cmdInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { runCommand(); true } else false
        }
        setPromptEnabled(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        bootstrapAlpine()
    }

    // ── Alpine bootstrap (needed before the one-shot prompt can exec) ──
    private fun bootstrapAlpine() {
        if (ProotBootstrap.isInstalled(this)) {
            alpineReady = true
            setPromptEnabled(true)
            cmdOutput.text = "Alpine ready. Type a command and tap Run."
            return
        }
        bootstrapOverlay.visibility = View.VISIBLE
        appendLog("re-adb: first run — bootstrapping Alpine (PRoot)...\n")
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                ProotBootstrap.setup(this@MainActivity) { line -> scope.launch { appendLog("$line\n") } }
            }
            if (ok) {
                appendLog("\nAlpine ready.\n")
                bootstrapOverlay.visibility = View.GONE
                alpineReady = true
                setPromptEnabled(true)
                cmdOutput.text = "Alpine ready. Type a command and tap Run."
            } else {
                appendLog("\nBootstrap FAILED — see log above.\n")
            }
        }
    }

    private fun appendLog(s: String) { bootstrapLog.append(s) }

    // ── One-shot command prompt ──
    private fun setPromptEnabled(on: Boolean) {
        cmdInput.isEnabled = on
        btnRun.isEnabled = on
        btnRun.alpha = if (on) 1f else 0.4f
        if (!on && !alpineReady) cmdOutput.text = "Preparing Alpine…"
    }

    private fun runCommand() {
        if (!alpineReady) return
        val cmd = cmdInput.text.toString().trim()
        if (cmd.isEmpty()) return
        setPromptEnabled(false)
        cmdOutput.text = "$ $cmd\n…"
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                try { ProotExecutor.exec(this@MainActivity, cmd, 30_000) }
                catch (t: Throwable) { "error: ${t.message}" }
            }
            val shown = if (out.isBlank()) "(no output)" else out.trimEnd()
            cmdOutput.text = "$ $cmd\n$shown"
            // keep the newest output in view
            cmdOutputScroll.post { cmdOutputScroll.fullScroll(View.FOCUS_DOWN) }
            cmdInput.text?.clear()
            setPromptEnabled(true)
            cmdInput.requestFocus()
        }
    }

    // ── Tunnels / ports ──
    private fun updateStatus() {
        val enabled = RouteStore.load(this).count { it.enabled }
        tunnelStatus.text = if (running) "Running · $enabled on" else "Stopped · $enabled on"
    }

    /**
     * Re-apply the enabled route set. The native engine's routes are fixed at
     * run(), so a change (enable/disable/edit/delete of a running route) means
     * stop + start. Called by per-route Start/Stop/Edit/Delete.
     */
    private fun restartForwarder() {
        val enabled = RouteStore.load(this).count { it.enabled }
        if (running) { ForwarderService.stop(this); running = false }
        if (enabled > 0) {
            scope.launch {
                withContext(Dispatchers.IO) { Thread.sleep(300) }
                ForwarderService.start(this@MainActivity); running = true
                syncStartStopButton()
                updateStatus()
                RouteStore.load(this@MainActivity).firstOrNull { it.enabled && it.tunnel }?.let { probeTunnel(it) }
            }
        }
        syncStartStopButton()
        updateStatus()
    }

    private fun syncStartStopButton() {
        val btn = findViewById<Button>(R.id.btnStartStop)
        if (running) {
            btn.text = "Stop"
            btn.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
        } else {
            btn.text = "Start"
            btn.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
        }
    }

    private fun toggleForwarder(btn: Button) {
        if (running) {
            ForwarderService.stop(this); running = false
            syncStartStopButton()
            updateStatus()
        } else {
            val enabled = RouteStore.load(this).filter { it.enabled }
            if (enabled.isEmpty()) {
                tunnelStatus.text = "No enabled tunnels — add/Start one"
                return
            }
            android.util.Log.i("re-adb", "Start tapped -> starting ForwarderService with ${enabled.size} enabled route(s)")
            ForwarderService.start(this); running = true
            syncStartStopButton()
            updateStatus()
            enabled.firstOrNull { it.tunnel }?.let { probeTunnel(it) }
        }
    }

    /** Best-effort TCP reachability probe to the tunnel server, off the main
     *  thread. Updates the status line; does NOT prove the Noise handshake. */
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
}
