package com.astra.agent.llm

import com.astra.agent.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** One question to the model with no chat history: cloud in Fast Mode (with a key), otherwise the local model. Works offline when local. */
object Completion {
    private val thinkRe = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)

    fun usesCloud(s: Settings) = s.fastMode && s.openRouterKey.isNotBlank()

    suspend fun ask(s: Settings, system: String, user: String, maxTokens: Int, temperature: Float = 0.3f): String = withContext(Dispatchers.Default) {
        val job = currentCoroutineContext()[Job]
        val out: String
        if (usesCloud(s)) {
            val msgs = listOf(OpenRouterClient.Message("system", system), OpenRouterClient.Message("user", user))
            val models = (listOf(s.openRouterModel) + OpenRouterClient.fallbackModels + OpenRouterClient.AUTO_FREE).filter { it.isNotBlank() }.distinct().take(3)
            var last: Exception? = null
            val sb = StringBuilder()
            var ok = false
            for (m in models) {
                try {
                    sb.setLength(0)
                    OpenRouterClient.streamChat(s.openRouterKey, m, msgs, maxTokens, temperature) { sb.append(it); job?.isActive != false }
                    ok = true; break
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { last = e; if (e is ORException && e.code == 401) break; if (sb.isNotEmpty()) { ok = true; break } }
            }
            if (!ok) throw last ?: IllegalStateException("No answer from the cloud model.")
            out = sb.toString()
        } else {
            if (LlmEngine.loadedName == null) throw IllegalStateException("No model loaded. Load a model on the Models tab or turn on Fast Mode.")
            val prompt = "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user /no_think<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
            val sb = StringBuilder()
            LlmEngine.generate(prompt, maxTokens, temperature, 0.9f) { sb.append(it); job?.isActive != false }
            out = sb.toString()
        }
        out.replace(thinkRe, "").replace("<|im_end|>", "").trim()
    }
}
