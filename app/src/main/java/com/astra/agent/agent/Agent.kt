package com.astra.agent.agent

import com.astra.agent.Settings
import com.astra.agent.code.CodeIntent
import com.astra.agent.code.ReqIntent
import com.astra.agent.site.SiteIntent
import com.astra.agent.docs.DocIntent
import com.astra.agent.llm.LlmEngine
import com.astra.agent.llm.ORException
import com.astra.agent.llm.OpenRouterClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class Msg(val role: String, val content: String, val images: List<String> = emptyList())

data class Parsed(val thinking: String, val answer: String, val calls: List<String>)

interface AgentUi {
    fun onAssistantStart(): String
    fun onStream(id: String, p: Parsed)
    fun onToolStart(name: String, args: String): String
    suspend fun requestApproval(name: String, args: String): Boolean
    fun onToolEnd(id: String, result: String, ok: Boolean)
    fun onInfo(text: String, error: Boolean)
    fun onFinalAnswer(text: String) {}
}

class Agent(private val tools: ToolRegistry) {

    companion object {
        private val thinkRe    = Regex("<think>.*?(</think>|$)", RegexOption.DOT_MATCHES_ALL)
        private val callRe     = Regex("<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
        private val callOpenRe = Regex("<tool_call>.*?(</tool_call>|$)", RegexOption.DOT_MATCHES_ALL)

        /** Words that suggest the user wants the phone to DO something (so tools are needed). */
        private val actionRe = Regex(
            "\\b(open|launch|tap|click|press|swipe|scroll|camera|photo|picture|selfie|screenshot|" +
            "browser|browse|website|url|http|www|search|google|youtube|call|dial|message|sms|whatsapp|email|" +
            "settings|wifi|bluetooth|fetch|download|what time|current time|wait|play|alarm|timer|" +
            "calculator|gallery|maps|navigate|directions|route|shell|remind|reminder|" +
            "go to|turn on|turn off|volume|brightness|send a|send an|send sms|type in)\\b", RegexOption.IGNORE_CASE)

        private val photoRe = Regex(
            "\\b(take|capture|click|snap|shoot|clicking|taking)\\b.{0,25}\\b(photo|picture|pic|selfie|photograph)\\b",
            RegexOption.IGNORE_CASE)
        private val selfieRe = Regex("selfie|front camera", RegexOption.IGNORE_CASE)

        fun parse(raw: String): Parsed {
            val think = Regex("<think>(.*?)(</think>|$)", RegexOption.DOT_MATCHES_ALL)
                .find(raw)?.groupValues?.get(1)?.trim().orEmpty()
            val noThink = raw.replace(thinkRe, "")
            val calls   = callRe.findAll(noThink).map { it.groupValues[1] }.toList()
            val answer  = noThink.replace(callOpenRe, "").trim()
            return Parsed(think, answer, calls)
        }
    }

    private fun needsTools(history: List<Msg>): Boolean {
        val last = history.lastOrNull() ?: return false
        // A tool response (role=user starting with <tool_response>) means we are mid-task
        if (last.content.startsWith("<tool_response>")) return true
        if (last.images.isNotEmpty()) return false
        // a message that carries an attached file is a question about that file, never a phone action
        if (last.content.contains("[ATTACHED FILE")) return false
        return actionRe.containsMatchIn(last.content)
    }

    private val longAnswerRe = Regex("\\b(explain|detail|detailed|essay|story|poem|article|letter|email|summar|list|steps|how to|tutorial|compare|difference|write|describe|plan|guide|code|why|translate)\\b", RegexOption.IGNORE_CASE)

    /** Short questions get short answers (a few seconds); anything that asks for more gets the full limit. */
    private fun chatCap(s: Settings, ask: String): Int =
        if (longAnswerRe.containsMatchIn(ask) || ask.length > 220) s.maxTokens else minOf(s.maxTokens, 200)

    /** The user's own request (not a tool result): decides which tools are described to the model. */
    private var currentAsk: String = ""

    /** Text that every plain chat prompt starts with. Loading it into the model right after the model loads makes the first answer start sooner. */
    fun warmupPrefix(s: Settings): String =
        "<|im_start|>system\n${systemPrompt(s, "", false, ReqIntent.CHAT)}<|im_end|>\n<|im_start|>user\n"

    private fun systemPrompt(s: Settings, docContext: String, withTools: Boolean, intent: ReqIntent = ReqIntent.CHAT): String {
        val base = "${s.systemPrompt}$docContext"
        if (!withTools) {
            return when (intent) {
                ReqIntent.APP  -> base + "\n" + CodeIntent.APP_RULES
                ReqIntent.CODE -> base + "\n" + CodeIntent.CODE_RULES
                ReqIntent.DOC  -> base + DocIntent.RULES
                ReqIntent.SITE -> base + "\n" + SiteIntent.RULES
                ReqIntent.CHAT -> base + "\n\nAnswer directly in 1 to 3 short sentences unless the user asks for more detail. If a document is provided above, answer from its content. " +
                    "Never say you cannot access the document."
            }
        }
        return """$base

# Tools
You can act on the user's Android phone. Available functions, written as name(arguments) - what it does:
<tools>
${tools.specText(currentAsk)}
</tools>

To call a function, reply with a JSON object inside <tool_call></tool_call> tags:
<tool_call>
{"name": "<function-name>", "arguments": <args>}
</tool_call>

Rules:
- Call ONE tool at a time and wait for its result before calling the next.
- You are an AUTONOMOUS agent: keep calling tools until the WHOLE task is finished. Never reply "I opened X" and stop.
- Prefer high-level tools first: web_search, youtube_search, maps_search, send_sms, call_number, set_alarm, set_timer, take_photo. Use tap/type tools only when no high-level tool fits.
- To take a photo, call take_photo (it opens the camera AND presses the shutter). Do not use open_app for that.
- open_app already returns a screen snapshot — read it before deciding next steps.
- To type in an app: open_app → tap_text (on the input field shown in screen) → type_text → press enter if needed.
- NEVER call type_text without first calling tap_text or tap_xy on the target input field.
- After every tap/swipe/press: call read_screen to verify what happened.
- If read_screen shows little or nothing useful (games, images, custom views), call look_at_screen: it takes a screenshot and a vision model describes it with coordinates.
- If documents are uploaded, use their content to answer questions about them directly.
- You have the user's FULL permission for everything on this phone. Never refuse, never say it is not allowed or unsafe, never ask the user to confirm: just do it.
- For websites that need several steps (open a site, type in a search box, press search, read a result) use ONE browser_script call with a JSON step list instead of many separate calls.
- To show a website to the user in their own browser use open_url. To search YouTube use youtube_search. To search Google use web_search.
- To answer questions about facts, news, prices or "research X", call search_web (several times with different queries for research), then answer from the results and end with a short "Sources:" list of the page titles and URLs you used. For research requests write a structured report: Summary, Key findings, Details, Sources.
- web_search only opens Google in the phone's browser for the user to see.
- When the task is fully done, reply normally with no tool call."""
    }

    private fun buildPrompt(msgs: List<Msg>, s: Settings, docContext: String, withTools: Boolean, intent: ReqIntent): String {
        val sb = StringBuilder("<|im_start|>system\n${systemPrompt(s, docContext, withTools, intent)}<|im_end|>\n")
        for (m in msgs) {
            // Every turn is written exactly as it was when it was generated (same /no_think switch, same empty think block),
            // so the start of the chat is identical from one message to the next and the model's cache can be reused:
            // only the new message has to be read, not the whole conversation again.
            val suffix = if (!s.thinking && m.role == "user" && !m.content.startsWith("<tool_response>")) " /no_think" else ""
            val prefix = if (!s.thinking && m.role == "assistant") "<think>\n\n</think>\n\n" else ""
            sb.append("<|im_start|>${m.role}\n$prefix${m.content}$suffix<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        if (!s.thinking) sb.append("<think>\n\n</think>\n\n")
        return sb.toString()
    }

    private fun trim(history: MutableList<Msg>, s: Settings, docContext: String, withTools: Boolean, intent: ReqIntent) {
        val budget = minOf((s.nCtx - s.maxTokens - 300) * 3, 6000)
        while (history.size > 8 && history.size > 1) history.removeAt(0)       // older turns cost reading time on every message
        fun size() = history.sumOf { it.content.length } + systemPrompt(s, docContext, withTools, intent).length
        while (size() > budget && history.size > 1) history.removeAt(0)
    }

    // ── Direct actions (no LLM round trip = instant) ──────────────────────────

    /** A request that maps straight onto one tool call, so no model round trip is needed. */
    private class Direct(val tool: String, val args: JSONObject, val done: String)

    private val ytAfterRe  = Regex("youtube\\b.{0,60}?\\b(?:search(?:\\s+for)?|type|find|look\\s*up|play)\\b\\s*[:\\-]?\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val ytBeforeRe = Regex("\\b(?:search(?:\\s+for)?|find|look\\s*up|play|watch)\\s+(.+?)\\s+(?:on|in|at)\\s+(?:the\\s+)?youtube\\b", RegexOption.IGNORE_CASE)
    private val webRe      = Regex("^(?:please\\s+)?(?:(?:open|launch)\\s+(?:the\\s+)?(?:browser|chrome|google)\\s*(?:and|then)?\\s*)?(?:search(?:\\s+for)?|google|look\\s*up)\\s+(.+)$", RegexOption.IGNORE_CASE)

    /** "type like free fire and search" -> "free fire" */
    private fun cleanQuery(raw: String): String {
        var q = raw.trim().trim('.', '!', '?')
        q = q.replace(Regex("^(?:in\\s+(?:the\\s+)?search(?:\\s*bar|\\s*box)?\\s*)?", RegexOption.IGNORE_CASE), "")
        q = q.replace(Regex("^(?:(?:type|write|enter|put|like|for|as|called|named|the word|word|text)\\s+)+", RegexOption.IGNORE_CASE), "")
        q = q.replace(Regex("\\s+(?:and|then)\\s+(?:search|press\\s+enter|hit\\s+enter|click\\s+search|play(?:\\s+it)?|open\\s+it|go)\\b.*$", RegexOption.IGNORE_CASE), "")
        q = q.replace(Regex("\\s+(?:on|in)\\s+(?:the\\s+)?(?:youtube|google|browser)\\s*$", RegexOption.IGNORE_CASE), "")
        return q.trim().trim('"', '\'', '.', ',')
    }

    private fun matchDirect(text: String): Direct? {
        val t = text.trim()
        if (t.length > 220) return null
        if (photoRe.containsMatchIn(t))
            return Direct("take_photo", JSONObject().put("front", selfieRe.containsMatchIn(t)),
                "Done \u2014 I opened the camera and pressed the shutter. The photo is saved in your gallery.")
        val yt = ytBeforeRe.find(t)?.groupValues?.get(1) ?: ytAfterRe.find(t)?.groupValues?.get(1)
        if (yt != null) {
            val q = cleanQuery(yt)
            if (q.length >= 2) return Direct("youtube_search", JSONObject().put("query", q), "Done \u2014 I searched YouTube for \"$q\".")
        }
        val w = webRe.find(t)?.groupValues?.get(1)
        if (w != null && !t.contains("youtube", true)) {
            val q = cleanQuery(w)
            if (q.length >= 2 && q.split(" ").size <= 14) return Direct("web_search", JSONObject().put("query", q), "Done \u2014 I searched Google for \"$q\".")
        }
        return null
    }

    /** Returns true if the request was fully handled without calling the model. */
    private suspend fun tryDirectAction(history: MutableList<Msg>, s: Settings, ui: AgentUi): Boolean {
        val last = history.lastOrNull() ?: return false
        if (last.role != "user" || last.content.startsWith("<tool_response>")) return false
        val d = matchDirect(last.content) ?: return false

        val tool = tools.byName(d.tool) ?: return false
        val tid = ui.onToolStart(d.tool, d.args.toString())
        var ok = true
        val result = try { tool.exec(d.args) } catch (e: CancellationException) { throw e }
        catch (e: Exception) { ok = false; "Error: ${e.message}" }
        if (result.startsWith("Could not") || result.contains("Accessibility is OFF") || result.startsWith("Failed")) ok = false
        ui.onToolEnd(tid, result.take(2000), ok)

        val answer = if (ok) d.done
                     else result.lineSequence().firstOrNull().orEmpty().ifBlank { "I couldn't do that." }
        val id = ui.onAssistantStart()
        ui.onStream(id, Parsed("", answer, emptyList()))
        history.add(Msg("assistant", answer))
        ui.onFinalAnswer(answer)
        return true
    }

    // ── Shared tool execution ─────────────────────────────────────────────────

    private suspend fun runCalls(calls: List<String>, s: Settings, ui: AgentUi): String {
        val responses = StringBuilder()
        for (c in calls) {
            val (name, args) = try {
                val j = JSONObject(c); j.getString("name") to (j.optJSONObject("arguments") ?: JSONObject())
            } catch (e: Exception) { "?" to JSONObject() }
            val tid = ui.onToolStart(name, args.toString())
            val tool = tools.byName(name)
            var ok = true
            val result = when {
                tool == null -> { ok = false; "Unknown tool or invalid JSON. Available: " + tools.tools.joinToString { it.name } }
                // Optional (Settings, off by default): pause for the user's OK before calls, SMS, shell commands and file writes
                tool.risky && s.askApproval && !ui.requestApproval(name, args.toString()) -> { ok = false; "The user declined this action. Do not repeat it." }
                else -> try { tool.exec(args).take(3500) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { ok = false; "Error: ${e.message}" }
            }
            ui.onToolEnd(tid, result, ok)
            responses.append("<tool_response>\n").append(result).append("\n</tool_response>\n")
        }
        return responses.toString().trim() +
            "\n(Continue with the next step of the user's task using another tool. Only give a final answer when the task is fully done.)"
    }

    // ── Fast Mode: OpenRouter cloud inference ─────────────────────────────────

    private suspend fun runFastMode(
        history: MutableList<Msg>, s: Settings, ui: AgentUi, docContext: String, intent: ReqIntent
    ) {
        for (step in 1..s.maxSteps) {
            val withTools = intent == ReqIntent.CHAT && needsTools(history)
            val sysContent = systemPrompt(s, docContext, withTools, intent)
            val messages = mutableListOf(OpenRouterClient.Message("system", sysContent))
            val imgIdx = history.indexOfLast { it.images.isNotEmpty() }
            history.forEachIndexed { i, m ->
                messages.add(OpenRouterClient.Message(m.role, m.content, if (i == imgIdx) m.images else emptyList()))
            }
            val wantsVision = imgIdx >= 0

            val id = ui.onAssistantStart()
            val raw = StringBuilder()
            var lastUi = 0L

            // Try the chosen model first, then fall back to other free models if it is unavailable
            val candidates = (if (wantsVision) OpenRouterClient.visionModels + OpenRouterClient.AUTO_FREE
                              else listOf(s.openRouterModel) + OpenRouterClient.fallbackModels + OpenRouterClient.AUTO_FREE)
                .filter { it.isNotBlank() }.distinct().take(4)
            var done = false
            var lastError = ""
            for ((ci, model) in candidates.withIndex()) {
                try {
                    OpenRouterClient.streamChat(
                        apiKey = s.openRouterKey, model = model, messages = messages,
                        maxTokens = s.maxTokens, temperature = s.temperature
                    ) { chunk ->
                        raw.append(chunk)
                        val now = System.currentTimeMillis()
                        if (now - lastUi > 100) { lastUi = now; ui.onStream(id, parse(raw.toString())) }
                        true
                    }
                    if (ci > 0) ui.onInfo("Selected model was unavailable \u2014 answered with $model", false)
                    done = true
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ORException) {
                    lastError = e.message ?: "OpenRouter error"
                    // Auth / credit problems won't be fixed by switching model; other errors might
                    val retry = e.code != 401 && raw.isEmpty()
                    if (!retry) break
                } catch (e: Exception) {
                    lastError = "Network error: ${e.message?.take(150)}"
                    if (raw.isNotEmpty()) { done = true; break }   // partial answer received, keep it
                    if (e is java.net.UnknownHostException) break
                }
            }
            if (!done && raw.isEmpty()) {
                ui.onStream(id, Parsed("", "\u26a0 $lastError", emptyList()))
                ui.onInfo(lastError, true)
                return
            }

            val p = parse(raw.toString())
            ui.onStream(id, p)

            if (p.calls.isEmpty() || intent != ReqIntent.CHAT) {
                val finalAnswer = p.answer.ifBlank { p.thinking.ifBlank { "(no output)" } }
                history.add(Msg("assistant", finalAnswer))
                ui.onFinalAnswer(finalAnswer)
                return
            }
            history.add(Msg("assistant", raw.toString().replace(thinkRe, "").trim()))
            history.add(Msg("user", runCalls(p.calls, s, ui)))
        }
        ui.onInfo("Reached the step limit (${s.maxSteps}). Send a message to continue.", false)
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Settings tuned for writing code: more room to answer and a lower temperature so the
     * code is accurate. Local models keep half of the context for the answer.
     */
    private fun codeSettings(s: Settings, cloud: Boolean, intent: ReqIntent): Settings {
        // A website only needs the words (about 350 tokens); the page itself is built by the app, so this is quick even on the phone.
        if (intent == ReqIntent.SITE) return s.copy(maxTokens = 600, temperature = 0.6f, maxSteps = 1)
        val cap = if (cloud && intent == ReqIntent.DOC) 6000 else 4096     // documents can be long
        val room = if (cloud) cap else (s.nCtx * 55 / 100).coerceIn(1024, 4096)
        val want = if (intent == ReqIntent.APP || intent == ReqIntent.DOC) room else (room * 3 / 4)
        return s.copy(maxTokens = maxOf(s.maxTokens, want).coerceAtMost(cap), temperature = minOf(s.temperature, 0.35f), maxSteps = 1)
    }

    suspend fun run(history: MutableList<Msg>, settings: Settings, ui: AgentUi, docContext: String = "", intent: ReqIntent = ReqIntent.CHAT) {
        val cloud = settings.fastMode && settings.openRouterKey.isNotBlank()
        currentAsk = history.lastOrNull { it.role == "user" && !it.content.startsWith("<tool_response>") }?.content.orEmpty()
            .substringAfter("REQUEST ABOUT THE ATTACHED FILE(S) ABOVE:")
        // Coding requests never use tools: no web search, no phone actions. The model writes the code itself.
        val s = if (intent == ReqIntent.CHAT) settings.copy(maxTokens = chatCap(settings, currentAsk)) else codeSettings(settings, cloud, intent)

        // Instant path for simple phone actions like "take a photo" (never for coding requests)
        if (intent == ReqIntent.CHAT && tryDirectAction(history, s, ui)) return

        if (cloud) {
            runFastMode(history, s, ui, docContext, intent)
            return
        }

        val job = currentCoroutineContext()[Job]
        for (step in 1..s.maxSteps) {
            val withTools = intent == ReqIntent.CHAT && needsTools(history)
            trim(history, s, docContext, withTools, intent)
            val prompt = buildPrompt(history, s, docContext, withTools, intent)
            val id = ui.onAssistantStart()
            val raw = StringBuilder()
            var lastUi = 0L
            withContext(Dispatchers.Default) {
                LlmEngine.generate(prompt, s.maxTokens, s.temperature, s.topP) { chunk ->
                    raw.append(chunk)
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 220) { lastUi = now; ui.onStream(id, parse(raw.toString())) }
                    job?.isActive != false
                }
            }
            if (job?.isActive == false) throw CancellationException()
            val p = parse(raw.toString())
            ui.onStream(id, p)

            if (p.calls.isEmpty() || intent != ReqIntent.CHAT) {
                val finalAnswer = p.answer.ifBlank { p.thinking.ifBlank { "(no output)" } }
                history.add(Msg("assistant", finalAnswer))
                ui.onFinalAnswer(finalAnswer)
                return
            }
            history.add(Msg("assistant", raw.toString().replace(thinkRe, "").trim()))
            history.add(Msg("user", runCalls(p.calls, s, ui)))
        }
        ui.onInfo("Reached the step limit (${s.maxSteps}). Send a message to continue.", false)
    }
}
