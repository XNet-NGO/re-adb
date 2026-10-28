package org.xnet.readb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.xnet.readb.shell.ProotExecutor
import java.io.File
import kotlin.concurrent.thread

/**
 * Run a one-shot command inside the rootless Alpine (PRoot) environment,
 * driven over adb. Lets a host drive the Alpine terminal non-interactively
 * (the interactive TerminalView cannot be fed via `adb shell`), for
 * testing/automation. Available in debug AND release builds.
 *
 *   adb shell am broadcast -a org.xnet.readb.RUN_CMD \
 *       -n org.xnet.readb/.AlpineCommandReceiver --es cmd 'uname -a; id'
 *
 * Output -> logcat (tag "readb-alpinecmd") AND <filesDir>/alpinecmd.out.
 *
 * Trust boundary: this is gated by device/adb access (and, for the tunnel,
 * the Noise PSK) — the same boundary that already lets an operator control the
 * device. No separate token. The command runs under the same rootless PRoot
 * confinement as the interactive terminal (no real host root, no host
 * filesystem beyond the proot binds).
 */
class AlpineCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra(EXTRA_CMD)
        if (cmd.isNullOrBlank()) {
            Log.w(TAG, "RUN_CMD with no '$EXTRA_CMD' extra; ignoring")
            return
        }
        val timeout = intent.getLongExtra(EXTRA_TIMEOUT_MS, 60_000L)
        val appCtx = context.applicationContext
        val pending = goAsync()
        thread(name = "readb-alpinecmd") {
            try {
                Log.i(TAG, "RUN: $cmd")
                val out = ProotExecutor.exec(appCtx, cmd, timeout)
                out.chunked(3500).forEach { Log.i(TAG, it) }
                try { File(appCtx.filesDir, OUT_FILE).writeText(out) }
                catch (t: Throwable) { Log.w(TAG, "write $OUT_FILE failed: ${t.message}") }
                Log.i(TAG, "DONE (${out.length} bytes) -> $OUT_FILE")
            } catch (t: Throwable) {
                Log.e(TAG, "exec error", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "readb-alpinecmd"
        const val ACTION = "org.xnet.readb.RUN_CMD"
        const val EXTRA_CMD = "cmd"
        const val EXTRA_TIMEOUT_MS = "timeout_ms"
        const val OUT_FILE = "alpinecmd.out"
    }
}
