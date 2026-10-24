package com.astra.agent.term

import android.content.Context
import android.net.ConnectivityManager
import java.io.File

/**
 * Paths and process launch lines for the embedded Linux environment.
 *
 * How it runs without root and without Termux: an Alpine Linux root file system lives in the app's private storage and is entered with
 * PRoot (shipped inside the APK as libproot.so / libproot-loader.so, so Android allows executing it). PRoot only *maps* the guest programs
 * into memory through its loader, which is what makes this work on current Android versions where executing downloaded files is blocked.
 */
object TermEnv {
    const val PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    fun base(ctx: Context): File = File(ctx.filesDir, "term").also { it.mkdirs() }
    fun rootfs(ctx: Context): File = File(base(ctx), "rootfs")
    fun tmp(ctx: Context): File = File(base(ctx), "tmp").also { it.mkdirs() }
    fun downloads(ctx: Context): File = File(base(ctx), "download").also { it.mkdirs() }
    fun logFile(ctx: Context): File = File(base(ctx), "setup.log")
    fun stateFile(ctx: Context): File = File(base(ctx), "state.properties")
    fun proot(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir, "libproot.so")
    fun loader(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir, "libproot-loader.so")

    /** Environment for the PRoot process itself (not for the guest). No secrets, no proxy or DNS overrides. */
    fun hostEnv(ctx: Context): Array<String> = arrayOf(
        "PROOT_LOADER=" + loader(ctx).absolutePath,
        "PROOT_TMP_DIR=" + tmp(ctx).absolutePath,
        "PROOT_NO_SECCOMP=1",
        "PATH=/system/bin",
        "HOME=" + ctx.filesDir.absolutePath,
        "TMPDIR=" + tmp(ctx).absolutePath
    )

    /** Guest environment is set explicitly through env -i so nothing from Android leaks in. */
    private fun guestEnv(): List<String> = listOf(
        "/usr/bin/env", "-i", "HOME=/root", "USER=root", "LOGNAME=root", "PATH=$PATH", "TERM=xterm-256color",
        "COLORTERM=truecolor", "LANG=C.UTF-8", "USE_BUILTIN_RIPGREP=0", "SHELL=/bin/sh"
    )

    fun interactiveShell(): List<String> = guestEnv() + listOf("/bin/sh", "-l")
    fun command(cmd: String): List<String> = guestEnv() + listOf("/bin/sh", "-c", cmd)

    /** Full argv (argv[0] is the PRoot binary). [workspace] is bound at /workspace and becomes the working directory. */
    fun prootArgs(ctx: Context, workspace: File?, inner: List<String>): List<String> {
        val a = ArrayList<String>()
        a += proot(ctx).absolutePath
        a += listOf("-r", rootfs(ctx).absolutePath, "-0", "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "--link2symlink", "-p", "-L", "--tcsetsf2tcsets")
        if (workspace != null) {
            File(rootfs(ctx), "workspace").mkdirs()
            a += listOf("-b", workspace.absolutePath + ":/workspace", "-w", "/workspace")
        } else a += listOf("-w", "/root")
        a += inner
        return a
    }

    /** Android has no resolv.conf for guests: copy the DNS servers of the active network (never a hard-coded server). */
    fun writeResolvConf(ctx: Context) {
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
            val dns = cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.mapNotNull { it.hostAddress }?.filter { !it.contains('%') }.orEmpty()
            if (dns.isEmpty()) return
            val f = File(rootfs(ctx), "etc/resolv.conf")
            f.parentFile?.mkdirs()
            f.writeText(dns.joinToString("\n") { "nameserver $it" } + "\n")
        } catch (_: Throwable) {}
    }

    fun hasBinary(ctx: Context, rel: String): Boolean =
        try { java.nio.file.Files.exists(File(rootfs(ctx), rel).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) } catch (_: Throwable) { false }

    fun freeBytes(ctx: Context): Long = ctx.filesDir.usableSpace
}
