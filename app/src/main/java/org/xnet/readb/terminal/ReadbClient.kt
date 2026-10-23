package org.xnet.readb.terminal

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.xnet.readb.terminal.view.TerminalView
import org.xnet.readb.terminal.view.TerminalViewClient

/**
 * Minimal client bridging the termux emulator/view callbacks into re-adb's
 * terminal UI. [onRedraw] is invoked whenever the view should repaint, and
 * [onFinished] when the shell exits. Keeps terminal concerns out of the Activity.
 */
class ReadbClient(
    private val view: TerminalView,
    private val onRedraw: () -> Unit = {},
    private val onFinished: (TerminalSession) -> Unit = {},
    private val ctrlDown: () -> Boolean = { false },
    private val altDown: () -> Boolean = { false },
) : TerminalSessionClient, TerminalViewClient {

    companion object { private const val TAG = "readb-term" }

    // ── TerminalSessionClient ──
    override fun onTextChanged(session: TerminalSession) { view.onScreenUpdated(); onRedraw() }
    override fun onTitleChanged(session: TerminalSession) {}
    override fun onSessionFinished(session: TerminalSession) { onFinished(session) }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
    override fun onPasteTextFromClipboard(session: TerminalSession?) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) { onRedraw() }
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: TAG, message ?: "", e)
    }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "", e) }

    // ── TerminalViewClient ──
    override fun onScale(scale: Float): Float = scale
    override fun onSingleTapUp(e: MotionEvent?) { view.requestFocus() }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun readControlKey(): Boolean = ctrlDown()
    override fun readAltKey(): Boolean = altDown()
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false
}
