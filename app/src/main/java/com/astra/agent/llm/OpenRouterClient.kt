package com.astra.agent.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** Error from OpenRouter with HTTP status so callers can decide whether to fall back to another model. */
class ORException(val code: Int, message: String) : Exception(message)

/**
 * Calls OpenRouter for fast cloud inference (streaming SSE).
 * Also fetches the LIVE list of free models, because free model ids change often
 * and a hard-coded list quickly goes stale ("model is not free / no endpoints").
 */
object OpenRouterClient {

    private const val BASE = "https://openrouter.ai/api/v1"
    const val AUTO_FREE = "openrouter/free"

    data class Message(val role: String, val content: String, val images: List<String> = emptyList())
    data class ORModel(val id: String, val name: String, val contextLength: Int, val free: Boolean,
                       val vision: Boolean = false, val tools: Boolean = false)

    /** Free model ids discovered at runtime; used as fallbacks when the selected one fails. */
    @Volatile var fallbackModels: List<String> = listOf(AUTO_FREE)
    /** Free models that accept images (used when the user attaches a photo). */
    @Volatile var visionModels: List<String> = emptyList()

    val defaultFree = listOf(
        ORModel(AUTO_FREE, "Auto \u2022 best available free model", 0, true),
        ORModel("meta-llama/llama-3.3-70b-instruct:free", "Llama 3.3 70B (Free)", 65536, true),
        ORModel("google/gemma-3-27b-it:free", "Gemma 3 27B (Free)", 96000, true),
        ORModel("mistralai/mistral-small-3.2-24b-instruct:free", "Mistral Small 3.2 (Free)", 96000, true),
        ORModel("qwen/qwen3-14b:free", "Qwen3 14B (Free)", 40960, true)
    )

    private fun friendly(code: Int, body: String): String {
        val raw = try {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        } catch (_: Exception) { "" }.ifBlank { body.take(200) }
        return when {
            code == 401 -> "Invalid OpenRouter API key. Check the key in Settings."
            code == 402 -> "This model needs credits. Pick a free model in Settings."
            code == 404 && raw.contains("data policy", true) ->
                "OpenRouter blocked free models by your privacy settings. Open openrouter.ai/settings/privacy and enable free-model access."
            code == 404 -> "This model is currently unavailable (${raw.take(120)})."
            code == 429 -> "Free model is rate-limited right now."
            else -> "OpenRouter error $code: ${raw.take(200)}"
        }
    }

    /** Fetches models from OpenRouter and returns only the free ones (prompt + completion price = 0). */
    suspend fun fetchFreeModels(): List<ORModel> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL("$BASE/models").openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != 200) return@withContext emptyList()
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(text).getJSONArray("data")
            val out = ArrayList<ORModel>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val id = m.optString("id")
                if (id.isBlank()) continue
                val pr = m.optJSONObject("pricing")
                val free = pr != null &&
                    pr.optString("prompt", "1").toDoubleOrNull() == 0.0 &&
                    pr.optString("completion", "1").toDoubleOrNull() == 0.0
                if (!free) continue
                // text-output models only
                val outMods = m.optJSONObject("architecture")?.optJSONArray("output_modalities")
                if (outMods != null) {
                    var textOk = false
                    for (k in 0 until outMods.length()) if (outMods.optString(k) == "text") textOk = true
                    if (!textOk) continue
                }
                val arch = m.optJSONObject("architecture")
                val inMods = arch?.optJSONArray("input_modalities")
                var vision = false
                if (inMods != null) for (k in 0 until inMods.length()) if (inMods.optString(k) == "image") vision = true
                val sp = m.optJSONArray("supported_parameters")
                var tools = false
                if (sp != null) for (k in 0 until sp.length()) if (sp.optString(k) == "tools") tools = true
                out.add(ORModel(id, m.optString("name", id), m.optInt("context_length", 0), true, vision, tools))
            }
            out.sortedByDescending { it.contextLength }
        } catch (_: Exception) {
            emptyList()
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Streams a chat completion. [onChunk] is called for each text piece; return false to cancel.
     * Throws [ORException] on HTTP errors.
     */
    suspend fun streamChat(
        apiKey: String,
        model: String,
        messages: List<Message>,
        maxTokens: Int = 1024,
        temperature: Float = 0.7f,
        onChunk: (String) -> Boolean
    ): String = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("max_tokens", maxTokens)
            put("temperature", temperature.toDouble())
            // Hide reasoning tokens so we never wait on / display hidden "thinking" text
            put("reasoning", JSONObject().put("exclude", true))
            put("messages", JSONArray().apply {
                messages.forEach { msg ->
                    put(JSONObject().apply {
                        put("role", msg.role)
                        if (msg.images.isEmpty()) {
                            put("content", msg.content)
                        } else {
                            put("content", JSONArray().apply {
                                put(JSONObject().put("type", "text").put("text", msg.content))
                                msg.images.forEach { b64 ->
                                    put(JSONObject().put("type", "image_url").put("image_url",
                                        JSONObject().put("url", "data:image/jpeg;base64,$b64")))
                                }
                            })
                        }
                    })
                }
            })
        }.toString()

        val conn = (URL("$BASE/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("HTTP-Referer", "https://github.com/Hem1234567/Hems-ZENIQ-04")
            setRequestProperty("X-Title", "ZENIQ Android")
            connectTimeout = 15_000
            readTimeout = 12_000          // a model that has not started within 12 s is skipped (the next one is tried)
            doOutput = true
            outputStream.use { it.write(body.toByteArray()) }
        }

        try {
            val code = conn.responseCode
            if (code != 200) {
                val err = try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
                throw ORException(code, friendly(code, err))
            }

            val sb = StringBuilder()
            BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (!l.startsWith("data:")) continue
                    val data = l.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    try {
                        val json = JSONObject(data)
                        json.optJSONObject("error")?.let { e ->
                            throw ORException(e.optInt("code", 500), e.optString("message", "Model error"))
                        }
                        val choices = json.optJSONArray("choices") ?: continue
                        if (choices.length() == 0) continue
                        val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue
                        val content = delta.optString("content", "")
                        if (content.isNotEmpty() && content != "null") {
                            sb.append(content)
                            if (!onChunk(content)) break
                        }
                    } catch (e: ORException) {
                        throw e
                    } catch (_: Exception) { /* skip malformed SSE lines */ }
                }
            }
            sb.toString()
        } finally {
            conn.disconnect()
        }
    }
}
