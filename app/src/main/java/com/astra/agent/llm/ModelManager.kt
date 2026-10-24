package com.astra.agent.llm

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

// ── Model catalogue types ────────────────────────────────────────────────────

enum class ModelKind { TEXT, VISION }

data class CatalogModel(
    val name: String,
    val sizeLabel: String,
    val sizeGb: Double,
    val url: String,
    val note: String,
    val kind: ModelKind = ModelKind.TEXT,
    val mmprojUrl: String? = null
)

enum class Fit { Great, Ok, TooBig }

sealed interface DlState {
    data class Progress(val done: Long, val total: Long) : DlState
    data class Failed(val msg: String) : DlState
}

// ── ModelManager ─────────────────────────────────────────────────────────────

class ModelManager(ctx: Context) {
    private val appCtx = ctx.applicationContext
    val dir = File(appCtx.filesDir, "models").apply { mkdirs() }

    // ── Text models ──────────────────────────────────────────────────────────
    val catalog = listOf(
        CatalogModel("Qwen3 4B (Q4_K_M)", "2.5 GB", 2.5,
            "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
            "Best balance. Needs ~8 GB RAM."),
        CatalogModel("Qwen3 1.7B (Q8_0)", "1.8 GB", 1.8,
            "https://huggingface.co/Qwen/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q8_0.gguf",
            "Faster, for 6 GB phones."),
        CatalogModel("Qwen3 0.6B (Q8_0)", "0.7 GB", 0.7,
            "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf",
            "Tiny, for testing. Weak tool use."),
        CatalogModel("Qwen3 8B (Q4_K_M)", "5.0 GB", 5.0,
            "https://huggingface.co/Qwen/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q4_K_M.gguf",
            "Smartest. Needs 12 GB+ RAM.")
    )

    // ── Vision models ────────────────────────────────────────────────────────
    val visionCatalog = listOf(
        CatalogModel("LLaVA 1.5 7B (Q4_K_M)", "4.1 GB", 4.1,
            "https://huggingface.co/mys/ggml_llava-v1.5-7b/resolve/main/ggml-model-q4_k.gguf",
            "See images + chat. Needs 8 GB RAM.", ModelKind.VISION,
            "https://huggingface.co/mys/ggml_llava-v1.5-7b/resolve/main/mmproj-model-f16.gguf"),
        CatalogModel("LLaVA 1.6 Mistral 7B (Q4_K_M)", "4.4 GB", 4.4,
            "https://huggingface.co/cjpais/llava-1.6-mistral-7b-gguf/resolve/main/llava-1.6-mistral-7b.Q4_K_M.gguf",
            "Better vision understanding. Needs 10 GB RAM.", ModelKind.VISION,
            "https://huggingface.co/cjpais/llava-1.6-mistral-7b-gguf/resolve/main/mmproj-model-f16.gguf"),
        CatalogModel("MiniCPM-V 2.6 (Q4_K_M)", "5.5 GB", 5.5,
            "https://huggingface.co/openbmb/MiniCPM-V-2_6-gguf/resolve/main/MiniCPM-V-2_6-Q4_K_M.gguf",
            "Excellent vision. Needs 12 GB RAM.", ModelKind.VISION,
            "https://huggingface.co/openbmb/MiniCPM-V-2_6-gguf/resolve/main/mmproj-MiniCPM-V-2_6-f16.gguf"),
        CatalogModel("LLaVA 1.5 13B (Q4_K_M)", "7.9 GB", 7.9,
            "https://huggingface.co/mys/ggml_llava-v1.5-13b/resolve/main/ggml-model-q4_k.gguf",
            "Best quality. Needs 16 GB RAM.", ModelKind.VISION,
            "https://huggingface.co/mys/ggml_llava-v1.5-13b/resolve/main/mmproj-model-f16.gguf")
    )

    // ── Device info ──────────────────────────────────────────────────────────
    val ramGb: Double = run {
        val mi = ActivityManager.MemoryInfo()
        (appCtx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        mi.totalMem / 1_073_741_824.0
    }

    /** Free disk space in GB */
    val freeDiskGb: Double get() {
        return try { StatFs(dir.path).let { it.availableBlocksLong * it.blockSizeLong / 1_073_741_824.0 } } catch (_: Throwable) { 99.0 }
    }

    fun fit(m: CatalogModel): Fit {
        val need = m.sizeGb + (if (m.kind == ModelKind.VISION) 2.2 else 1.6)
        return when { need <= ramGb * 0.60 -> Fit.Great; need <= ramGb * 0.72 -> Fit.Ok; else -> Fit.TooBig }
    }

    fun recommended(): CatalogModel =
        catalog.filter { fit(it) != Fit.TooBig }.maxByOrNull { it.sizeGb } ?: catalog.minByOrNull { it.sizeGb }!!

    fun recommendedVision(): CatalogModel =
        visionCatalog.filter { fit(it) != Fit.TooBig }.maxByOrNull { it.sizeGb }
            ?: visionCatalog.minByOrNull { it.sizeGb }!!

    // ── Download state ───────────────────────────────────────────────────────
    val downloads = MutableStateFlow<Map<String, DlState>>(emptyMap())

    fun fileFor(url: String): File {
        val name = url.substringAfterLast('/').substringBefore('?').let {
            // Sanitise filename — remove any characters that could cause issues
            it.replace(Regex("[^a-zA-Z0-9._\\-]"), "_").take(120)
        }
        return File(dir, name.ifBlank { "model.gguf" })
    }

    fun installed(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") && f.length() > 1_000_000L }
            ?.sortedBy { it.name } ?: emptyList()

    /**
     * Opens a connection following redirects (including HTTP→HTTPS).
     * Android's HttpURLConnection follows same-protocol redirects but NOT
     * HTTP→HTTPS ones. HuggingFace always redirects to its CDN over HTTPS,
     * so we must follow manually.
     */
    private fun openWithRedirects(startUrl: String, rangeHeader: String?): Pair<HttpURLConnection, Int> {
        var url = startUrl
        repeat(8) { hop ->
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false   // we handle it ourselves
            conn.connectTimeout = 60_000
            conn.readTimeout    = 120_000
            conn.setRequestProperty("User-Agent", "ZENIQ/1.0")
            if (rangeHeader != null && hop == 0) conn.setRequestProperty("Range", rangeHeader)
            conn.connect()
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                    ?: throw RuntimeException("Redirect with no Location header (HTTP $code)")
                conn.disconnect()
                url = if (location.startsWith("http")) location
                      else URL(URL(url), location).toString()
            } else {
                return Pair(conn, code)
            }
        }
        throw RuntimeException("Too many redirects — download failed")
    }

    /**
     * Downloads a model with resume support and automatic redirect following.
     * - Follows HTTP redirects (required for HuggingFace → CDN redirects).
     * - Retries up to 3 times with 2 s backoff on DNS/network failures.
     * - Checks free disk space before starting.
     * - Uses a .part temp file so a partial download never looks complete.
     * - Updates progress at most every 500 ms to avoid flooding StateFlow.
     */
    suspend fun download(url: String) = withContext(Dispatchers.IO) {
        val target = fileFor(url)
        val part   = File(target.path + ".part")

        var lastError: Exception? = null
        repeat(3) { attempt ->
            if (attempt > 0) {
                try { Thread.sleep(2000L) } catch (_: InterruptedException) {}
            }
            var conn: HttpURLConnection? = null
            try {
                var have = if (part.exists()) part.length() else 0L
                val rangeHeader = if (have > 0) "bytes=$have-" else null

                val (c, code) = openWithRedirects(url, rangeHeader)
                conn = c
                if (code == 200) have = 0       // server ignored Range, restart from 0
                if (code != 200 && code != 206) throw RuntimeException("HTTP $code")

                val total = have + conn.contentLengthLong.coerceAtLeast(0L)

                val needed = total - have
                if (needed > 0 && freeDiskGb < (needed / 1_073_741_824.0) * 1.1) {
                    throw RuntimeException("Not enough storage. Free up space and try again.")
                }

                RandomAccessFile(part, "rw").use { raf ->
                    if (code == 200) raf.setLength(0)
                    raf.seek(have)
                    val buf  = ByteArray(256 * 1024)
                    var done = have
                    var last = 0L
                    conn.inputStream.use { ins ->
                        while (isActive) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - last > 500) {
                                last = now
                                downloads.update { it + (url to DlState.Progress(done, total)) }
                            }
                        }
                    }
                    // Final progress update
                    downloads.update { it + (url to DlState.Progress(done, total)) }
                }

                if (!isActive) throw CancellationException()

                // Atomic rename — only replace target if download completed fully
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                downloads.update { it - url }
                return@withContext   // ✅ success

            } catch (e: CancellationException) {
                downloads.update { it - url }
                conn?.disconnect()
                throw e
            } catch (e: UnknownHostException) {
                conn?.disconnect()
                lastError = RuntimeException(
                    "Cannot reach download server — check your internet connection " +
                    "(Wi-Fi or mobile data) and try again.", e)
            } catch (e: Exception) {
                conn?.disconnect()
                lastError = e
            }
        }

        // All retries failed — report error
        downloads.update { it + (url to DlState.Failed(
            lastError?.message?.take(180) ?: "Download failed. Check your internet connection."
        )) }
    }

    fun delete(f: File) { try { f.delete() } catch (_: Throwable) {} }

    fun deletePartial(url: String) {
        try { File(fileFor(url).path + ".part").delete() } catch (_: Throwable) {}
        downloads.update { it - url }
    }

    fun partialSize(url: String): Long =
        try { File(fileFor(url).path + ".part").let { if (it.exists()) it.length() else 0L } }
        catch (_: Throwable) { 0L }

    /** Total GB used by all model files + partial downloads */
    fun usedDiskGb(): Double =
        (dir.listFiles()?.sumOf { it.length() } ?: 0L) / 1_073_741_824.0
}
