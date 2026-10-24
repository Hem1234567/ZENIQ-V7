package com.astra.agent.term

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

/** Bridges the terminal widget to ZENIQ: clipboard, keyboard, virtual Ctrl key and session-finished state. */
class TermClient(private val app: Context) : TerminalSessionClient, TerminalViewClient {
    var view: TerminalView? = null
    var ctrl by mutableStateOf(false)
    var finished by mutableStateOf(false)

    // session side
    override fun onTextChanged(changedSession: TerminalSession) { view?.onScreenUpdated() }
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) { finished = true }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        val cm = app.getSystemService(ClipboardManager::class.java)
        if (text != null) cm?.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val cm = app.getSystemService(ClipboardManager::class.java)
        val t = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString()
        if (!t.isNullOrEmpty()) session?.emulator?.paste(t)
    }
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null

    // view side
    override fun onScale(scale: Float): Float = 1.0f
    override fun onSingleTapUp(e: MotionEvent) {
        val v = view ?: return
        v.requestFocus()
        app.getSystemService(InputMethodManager::class.java)?.showSoftInput(v, 0)
    }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = ctrl
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean { if (ctrl) ctrl = false; return false }
    override fun onEmulatorSet() {}

    // logging (shared by both interfaces); terminal contents are never logged
    override fun logError(tag: String, message: String) {}
    override fun logWarn(tag: String, message: String) {}
    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
    override fun logStackTrace(tag: String, e: Exception) {}
}

/** Holds the one interactive terminal so it survives tab switches and screen rotation (it ends if Android kills the app process). */
object TermSessions {
    var session: TerminalSession? = null
        private set
    private var key: String? = null
    private var client: TermClient? = null

    fun client(ctx: Context): TermClient = client ?: TermClient(ctx.applicationContext).also { client = it }

    /** Returns the running session for [workspace], or starts a new one. A different project restarts the shell in that project. */
    fun ensure(ctx: Context, workspace: File?): TerminalSession {
        val k = workspace?.absolutePath ?: ""
        val cur = session
        if (cur != null && key == k && cur.isRunning) return cur
        cur?.finishIfRunning()
        TermEnv.writeResolvConf(ctx)
        val args = TermEnv.prootArgs(ctx, workspace, TermEnv.interactiveShell())
        val c = client(ctx)
        c.finished = false; c.ctrl = false
        val s = TerminalSession(args[0], ctx.filesDir.absolutePath, args.toTypedArray(), TermEnv.hostEnv(ctx), 4000, c)
        session = s; key = k
        return s
    }

    fun restart(ctx: Context, workspace: File?): TerminalSession { session?.finishIfRunning(); session = null; return ensure(ctx, workspace) }
    fun stop() { session?.finishIfRunning(); session = null }
    fun send(text: String) { val s = session ?: return; if (!s.isRunning) return; val b = text.toByteArray(); s.write(b, 0, b.size) }
}
