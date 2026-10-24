package com.astra.agent.term

import android.content.Context
import android.os.Build
import android.system.Os
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Properties
import kotlin.coroutines.coroutineContext

enum class Stage(val label: String) {
    NOT_INITIALIZED("Not set up yet"), CHECKING("Checking this phone"), PREPARING_STORAGE("Preparing storage"),
    DOWNLOADING("Downloading Linux base system"), EXTRACTING("Unpacking Linux base system"), SELF_TEST("Testing the Linux environment"),
    INSTALLING_PACKAGES("Installing Node.js and tools"), INSTALLING_CLAUDE("Installing Claude Code"), VERIFYING("Verifying Claude Code"),
    READY("Ready"), ERROR("Needs attention")
}

data class SetupUi(
    val stage: Stage = Stage.NOT_INITIALIZED,
    val message: String = "Claude Code runs inside ZENIQ in its own Linux environment. First setup downloads about 250 MB and needs internet once.",
    val progress: Float = -1f,
    val error: String? = null,
    val failedAt: Stage? = null,
    val claudeVersion: String? = null,
    val busy: Boolean = false
)

/**
 * One-time (and repairable) installation of the embedded environment: Alpine root file system -> Node.js and tools -> official Claude Code CLI.
 * Every step records its result in state.properties, so an interrupted setup resumes and a valid install is never downloaded again.
 * The environment is only marked READY after `claude --version` really ran inside it.
 */
object TermSetup {
    private val _ui = MutableStateFlow(SetupUi())
    val ui = _ui.asStateFlow()
    private var job: Job? = null
    /** Setup keeps running when the user leaves the screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private const val MIN_FREE = 1_200_000_000L
    private const val ALPINE_BASE = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/"

    // ── state file ──────────────────────────────────────────────────────────
    private fun props(ctx: Context) = Properties().also { p -> try { TermEnv.stateFile(ctx).inputStream().use { p.load(it) } } catch (_: Exception) {} }
    private fun save(ctx: Context, p: Properties) { try { TermEnv.stateFile(ctx).outputStream().use { p.store(it, "ZENIQ terminal state (no secrets)") } } catch (_: Exception) {} }
    private fun flag(ctx: Context, key: String) = props(ctx).getProperty(key) == "1"
    private fun setProp(ctx: Context, key: String, value: String?) { val p = props(ctx); if (value == null) p.remove(key) else p.setProperty(key, value); save(ctx, p) }

    // ── log (never contains credentials: only installer output) ─────────────
    fun log(ctx: Context, line: String) {
        try {
            val f = TermEnv.logFile(ctx)
            if (f.length() > 400_000) f.writeText(f.readText().takeLast(150_000))
            f.appendText(line.take(600) + "\n")
        } catch (_: Exception) {}
    }
    fun readLog(ctx: Context): String = try { TermEnv.logFile(ctx).readText().takeLast(24_000) } catch (_: Exception) { "(no log yet)" }

    private fun set(stage: Stage, msg: String, progress: Float = -1f) { _ui.value = _ui.value.copy(stage = stage, message = msg, progress = progress, error = null, failedAt = null, busy = true) }
    private fun fail(ctx: Context, at: Stage, msg: String) { log(ctx, "ERROR [$at] $msg"); _ui.value = _ui.value.copy(stage = Stage.ERROR, failedAt = at, error = msg, message = msg, progress = -1f, busy = false) }

    // ── public API ──────────────────────────────────────────────────────────
    /** Called when the terminal tab opens: shows READY only after the installed CLI answers, otherwise shows what is missing. */
    fun refresh(ctx: Context, scope: CoroutineScope) {
        if (job?.isActive == true || _ui.value.stage == Stage.READY) return
        val app = ctx.applicationContext
        val p = props(app)
        val complete = p.getProperty("claude") == "1" && TermEnv.hasBinary(app, "bin/busybox") && TermEnv.hasBinary(app, "usr/bin/claude")
        if (!complete) {
            val interrupted = File(TermEnv.base(app), "rootfs.tmp").exists() || TermEnv.downloads(app).listFiles()?.any { it.name.endsWith(".part") } == true || p.getProperty("rootfs") == "1"
            _ui.value = SetupUi(Stage.NOT_INITIALIZED, if (interrupted) "A previous setup did not finish. Tap Resume to continue where it stopped." else SetupUi().message)
            return
        }
        job = scope.launch {
            _ui.value = SetupUi(Stage.VERIFYING, "Checking the installed environment…", busy = true)
            TermEnv.writeResolvConf(app)
            val v = claudeVersion(app)
            if (v != null) _ui.value = SetupUi(Stage.READY, "Claude Code $v is installed.", claudeVersion = v)
            else fail(app, Stage.VERIFYING, "The installed environment did not start. Tap Repair environment (your projects are not touched).")
        }
    }

    fun start(ctx: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch {
            try { runSetup(app) }
            catch (e: CancellationException) { fail(app, _ui.value.stage, "Setup was cancelled. Tap Resume to continue."); throw e }
            catch (e: Exception) { fail(app, _ui.value.stage, e.message ?: e.javaClass.simpleName) }
        }
    }

    fun cancel() { job?.cancel() }

    /** Repair keeps the Linux base but reinstalls tools and Claude Code. [wipe] removes everything inside the environment (projects are stored elsewhere and are not touched). */
    fun repair(ctx: Context, wipe: Boolean, scope: CoroutineScope) {
        val app = ctx.applicationContext
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            if (wipe) { File(TermEnv.base(app), "rootfs.tmp").deleteRecursively(); TermEnv.rootfs(app).deleteRecursively(); TermEnv.stateFile(app).delete() }
            else { setProp(app, "packages", null); setProp(app, "claude", null); setProp(app, "claudeVersion", null) }
            TermEnv.downloads(app).listFiles()?.forEach { it.delete() }
            _ui.value = SetupUi()
            try { runSetup(app) }
            catch (e: CancellationException) { fail(app, _ui.value.stage, "Setup was cancelled. Tap Resume to continue."); throw e }
            catch (e: Exception) { fail(app, _ui.value.stage, e.message ?: e.javaClass.simpleName) }
        }
    }

    /** Removes Claude's saved sign-in (supported way: delete its credential files). Projects and installed tools stay. */
    fun resetSignIn(ctx: Context) {
        val root = TermEnv.rootfs(ctx)
        File(root, "root/.claude/.credentials.json").delete()
        File(root, "root/.claude.json").delete()
    }

    // ── setup steps ─────────────────────────────────────────────────────────
    private suspend fun runSetup(ctx: Context) {
        log(ctx, "=== setup started ${java.util.Date()} ===")
        // 1. compatibility
        set(Stage.CHECKING, "Checking this phone…")
        if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) return fail(ctx, Stage.CHECKING, "This phone is not 64-bit ARM (${Build.SUPPORTED_ABIS.joinToString()}). The embedded Linux environment supports arm64 phones only.")
        if (!TermEnv.proot(ctx).exists() || !TermEnv.loader(ctx).exists()) return fail(ctx, Stage.CHECKING, "The PRoot runtime is missing from this build of the app. Reinstall the APK.")
        // 2. storage
        set(Stage.PREPARING_STORAGE, "Checking free storage…")
        val needDownload = !flag(ctx, "rootfs") || !TermEnv.hasBinary(ctx, "bin/busybox")
        if (needDownload && TermEnv.freeBytes(ctx) < MIN_FREE)
            return fail(ctx, Stage.PREPARING_STORAGE, "Not enough free storage: about 1.2 GB is needed, ${TermEnv.freeBytes(ctx) / 1_000_000} MB is free. Free some space and tap Retry.")
        // 3. root file system
        if (needDownload) {
            val net = TermNet.report(ctx, "dl-cdn.alpinelinux.org")
            if (!net.ok) return fail(ctx, Stage.DOWNLOADING, net.detail)
            setProp(ctx, "rootfs", null)
            set(Stage.DOWNLOADING, "Finding the current Linux base system…")
            val (file, sha) = resolveRootfs(ctx)
            val tarball = File(TermEnv.downloads(ctx), file)
            download(ctx, ALPINE_BASE + file, tarball, sha) { done, total ->
                _ui.value = _ui.value.copy(stage = Stage.DOWNLOADING, message = "Downloading Linux base system… ${done / 1024 / 1024} MB", progress = if (total > 0) done.toFloat() / total else -1f)
            }
            set(Stage.EXTRACTING, "Unpacking Linux base system…")
            val tmp = File(TermEnv.base(ctx), "rootfs.tmp")
            tmp.deleteRecursively()
            withContext(Dispatchers.IO) { extractTarGz(tarball, tmp) }
            TermEnv.rootfs(ctx).deleteRecursively()
            if (!tmp.renameTo(TermEnv.rootfs(ctx))) throw IOException("Could not move the unpacked system into place.")
            tarball.delete()
            setProp(ctx, "rootfs", "1"); setProp(ctx, "alpine", file)
        }
        // 4. self test (proves PRoot works on this phone before anything else is installed)
        set(Stage.SELF_TEST, "Testing the Linux environment…")
        TermEnv.writeResolvConf(ctx)
        val test = StringBuilder()
        val code = runInRoot(ctx, "echo zeniq-ok; uname -m", 60_000) { test.appendLine(it); log(ctx, it) }
        if (code != 0 || !test.contains("zeniq-ok"))
            return fail(ctx, Stage.SELF_TEST, "The Linux environment would not start on this phone (exit $code). Last output: ${test.toString().trim().takeLast(400)}. Tap View logs for details.")
        // 5. packages
        if (!flag(ctx, "packages") || !TermEnv.hasBinary(ctx, "usr/bin/node")) {
            val net = TermNet.report(ctx, "dl-cdn.alpinelinux.org")
            if (!net.ok) return fail(ctx, Stage.INSTALLING_PACKAGES, net.detail)
            set(Stage.INSTALLING_PACKAGES, "Installing Node.js, git and tools…")
            // Force HTTP — Alpine HTTPS needs kernel features PRoot on Android doesn't emulate.
            // --no-scripts skips post-install hooks that call fchdir() (not emulated by PRoot).
            // apk exits non-zero when it can't write its own database (also uses fchdir), but
            // ALL binaries are already on disk before that write — check the binary, not exit code.
            val cmd = "sed -i 's|https://|http://|g' /etc/apk/repositories 2>/dev/null; " +
                "apk update --allow-untrusted 2>&1; " +
                "apk add --no-cache --allow-untrusted --no-scripts " +
                "bash git curl nodejs npm ripgrep libgcc libstdc++ ca-certificates nano 2>&1; " +
                "exit 0"
            runInRoot(ctx, cmd, 30 * 60_000L) { log(ctx, it); _ui.value = _ui.value.copy(message = "Installing Node.js and tools…\n" + it.take(80)) }
            // apk may exit non-zero (DB write fails under PRoot fchdir gap) even when every
            // package binary is fully installed. Only the binary presence matters.
            if (!TermEnv.hasBinary(ctx, "usr/bin/node")) return fail(ctx, Stage.INSTALLING_PACKAGES,
                "Node.js not found after install. Check your internet and tap Retry, or View logs.")
            setProp(ctx, "packages", "1")
        }
        // 6. Claude Code (official npm package; Alpine/arm64-musl is a supported platform)
        if (!flag(ctx, "claude") || !TermEnv.hasBinary(ctx, "usr/bin/claude")) {
            val net = TermNet.report(ctx, "registry.npmjs.org")
            if (!net.ok) return fail(ctx, Stage.INSTALLING_CLAUDE, net.detail)
            set(Stage.INSTALLING_CLAUDE, "Installing Claude Code (a few minutes)…")
            val rc = runInRoot(ctx, "NPM_CONFIG_UPDATE_NOTIFIER=false npm install -g @anthropic-ai/claude-code 2>&1", 40 * 60_000L) {
                log(ctx, it); _ui.value = _ui.value.copy(message = "Installing Claude Code…\n" + it.take(80))
            }
            if (rc != 0 || !TermEnv.hasBinary(ctx, "usr/bin/claude")) return fail(ctx, Stage.INSTALLING_CLAUDE, "Claude Code installation failed (exit $rc). Tap Retry, or View logs.")
        }
        // 7. verify, only then READY
        set(Stage.VERIFYING, "Verifying Claude Code…")
        val v = claudeVersion(ctx) ?: return fail(ctx, Stage.VERIFYING, "Claude Code was installed but did not start. Tap Repair environment, or View logs.")
        setProp(ctx, "claude", "1"); setProp(ctx, "claudeVersion", v); setProp(ctx, "installedAt", System.currentTimeMillis().toString())
        log(ctx, "=== ready: Claude Code $v ===")
        _ui.value = SetupUi(Stage.READY, "Claude Code $v is installed.", claudeVersion = v)
    }

    /** Runs `claude --version` inside the environment. Returns the version or null. */
    suspend fun claudeVersion(ctx: Context): String? {
        val out = StringBuilder()
        val rc = runInRoot(ctx, "claude --version", 90_000) { out.appendLine(it) }
        if (rc != 0) { log(ctx, "claude --version failed: " + out.toString().takeLast(300)); return null }
        return Regex("""\d+\.\d+\.\d+""").find(out)?.value
    }

    /** Runs a shell command inside the Linux environment without a terminal, streaming output lines. Cancellable, with a timeout. */
    suspend fun runInRoot(ctx: Context, cmd: String, timeoutMs: Long, onLine: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder(TermEnv.prootArgs(ctx, null, TermEnv.command(cmd))).redirectErrorStream(true)
        pb.environment().clear()
        for (kv in TermEnv.hostEnv(ctx)) { val i = kv.indexOf('='); pb.environment()[kv.substring(0, i)] = kv.substring(i + 1) }
        val p = pb.start()
        var timedOut = false
        val watchdog = launch { try { delay(timeoutMs); timedOut = true } finally { p.destroyForcibly() } }
        try {
            p.inputStream.bufferedReader().forEachLine { onLine(it) }
            val rc = p.waitFor()
            if (timedOut) { onLine("[timed out after ${timeoutMs / 1000}s]"); -1 } else rc
        } finally { watchdog.cancel(); p.destroyForcibly() }
    }

    // ── downloads ───────────────────────────────────────────────────────────
    private suspend fun httpText(url: String): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        var wait = 2000L
        repeat(4) { attempt ->
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000
                if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
                return@withContext c.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) { last = e; if (attempt < 3) { delay(wait); wait *= 2 } }
        }
        throw IOException("Could not read $url (${last?.message}).")
    }

    private suspend fun resolveRootfs(ctx: Context): Pair<String, String> {
        val yaml = httpText(ALPINE_BASE + "latest-releases.yaml")
        val blocks = yaml.split(Regex("(?m)^-\\s*$"))
        for (b in blocks) {
            val file = Regex("""(?:file|iso):\s*(alpine-minirootfs-[\w.\-]+-aarch64\.tar\.gz)""").find(b)?.groupValues?.get(1) ?: continue
            val sha = Regex("""sha256:\s*([0-9a-f]{64})""").find(b)?.groupValues?.get(1) ?: continue
            log(ctx, "rootfs: $file sha256=$sha")
            return file to sha
        }
        throw IOException("Could not find the Alpine Linux base system in the official release list.")
    }

    /** Resumable download with timeouts, exponential backoff (max 4 attempts) and SHA-256 verification. Never loops forever. */
    private suspend fun download(ctx: Context, url: String, dest: File, sha256: String, onProgress: (Long, Long) -> Unit) {
        if (dest.exists() && sha(dest) == sha256) return
        val part = File(dest.path + ".part")
        var wait = 3000L
        var lastError = "unknown"
        for (attempt in 1..4) {
            coroutineContext.ensureActive()
            try {
                withContext(Dispatchers.IO) {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 15000; c.readTimeout = 30000
                    val have = if (part.exists()) part.length() else 0L
                    if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                    val code = c.responseCode
                    if (code == 416) return@withContext   // already complete, verified below
                    if (code == 403 || code == 404) throw NoRetry("The server answered HTTP $code for the Linux base system.")
                    if (code != 200 && code != 206) throw IOException("HTTP $code")
                    val resume = code == 206 && have > 0
                    val total = c.contentLengthLong.let { if (it > 0) it + (if (resume) have else 0) else -1L }
                    var done = if (resume) have else 0L
                    java.io.FileOutputStream(part, resume).use { out ->
                        c.inputStream.use { ins ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = ins.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n; onProgress(done, total)
                            }
                        }
                    }
                }
                if (sha(part) == sha256) { part.renameTo(dest); return }
                part.delete(); lastError = "checksum mismatch (download was damaged)"
            } catch (e: NoRetry) { throw IOException(e.message) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { lastError = e.message ?: e.javaClass.simpleName }
            log(ctx, "download attempt $attempt failed: $lastError")
            if (attempt < 4) { _ui.value = _ui.value.copy(message = "Download interrupted ($lastError). Retrying in ${wait / 1000}s…"); delay(wait); wait *= 2 }
        }
        throw IOException("Download failed after 4 attempts: $lastError. Check your connection and tap Retry — it resumes where it stopped.")
    }
    private class NoRetry(msg: String) : Exception(msg)

    private fun sha(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { ins -> val buf = ByteArray(64 * 1024); while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ── tar.gz extraction (keeps symlinks, refuses paths that escape the target) ─────
    @Suppress("DEPRECATION")
    private fun extractTarGz(src: File, dest: File) {
        dest.mkdirs()
        val root = dest.toPath().normalize()
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(FileInputStream(src), 1 shl 16))).use { tin ->
            var e: TarArchiveEntry? = tin.nextTarEntry
            while (e != null) {
                val name = e.name.removePrefix("./").trimStart('/')
                val out = File(dest, name)
                if (name.isNotEmpty() && out.toPath().normalize().startsWith(root)) {
                    try {
                        when {
                            e.isDirectory -> { out.mkdirs(); try { Os.chmod(out.path, (e.mode and 0xFFF) or 0x1C0) } catch (_: Throwable) {} }
                            e.isSymbolicLink -> { out.parentFile?.mkdirs(); try { Os.remove(out.path) } catch (_: Throwable) {}; Os.symlink(e.linkName, out.path) }
                            e.isLink -> {
                                out.parentFile?.mkdirs()
                                val target = File(dest, e.linkName.removePrefix("./").trimStart('/'))
                                if (target.exists() && target.toPath().normalize().startsWith(root)) target.copyTo(out, overwrite = true)
                            }
                            e.isFile -> {
                                out.parentFile?.mkdirs()
                                out.outputStream().buffered().use { tin.copyTo(it) }
                                try { Os.chmod(out.path, (e.mode and 0xFFF) or 0x180) } catch (_: Throwable) {}
                            }
                            else -> {}   // device nodes and fifos are not needed (PRoot binds /dev)
                        }
                    } catch (ex: Exception) { throw IOException("Unpacking failed at $name: ${ex.message}") }
                }
                e = tin.nextTarEntry
            }
        }
    }
}
