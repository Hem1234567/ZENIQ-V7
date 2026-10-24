package com.astra.agent.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astra.agent.Settings
import com.astra.agent.code.CodeAgent
import com.astra.agent.code.Diff
import com.astra.agent.code.FileChange
import com.astra.agent.code.FileStorage
import com.astra.agent.code.Terminal
import com.astra.agent.code.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ZENIQ Code: projects, file explorer, editor with tabs, search and replace, AI coding agent with diff review,
// terminal and build output, checkpoints (git-style history) and ZIP export. Works offline with a local model.

private class CodeState(val ctx: Context) {
    val base: File = File(ctx.filesDir, "code").apply { mkdirs() }
    var projects by mutableStateOf(listProjects())
    var project by mutableStateOf<String?>(null)
    var ws by mutableStateOf<Workspace?>(null)
    var tab by mutableStateOf(0)
    var tick by mutableStateOf(0)
    val open = mutableStateListOf<String>()
    val texts = mutableStateMapOf<String, String>()
    val saved = mutableStateMapOf<String, String>()
    var active by mutableStateOf<String?>(null)
    var message by mutableStateOf("")
    var terminal by mutableStateOf("")
    var aiBusy by mutableStateOf(false)
    var aiText by mutableStateOf("")
    val aiChanges = mutableStateListOf<FileChange>()
    var preview by mutableStateOf<String?>(null)
    var previewRoot by mutableStateOf<String?>(null)

    fun listProjects(): List<String> = base.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

    fun create(name: String, kind: String) {
        val clean = name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "My Project" }
        val dir = File(base, clean)
        if (!dir.exists()) Workspace.createStarter(dir, kind)
        projects = listProjects()
        openProject(clean)
    }

    fun openProject(name: String) {
        project = name; ws = Workspace(File(base, name)); open.clear(); texts.clear(); saved.clear(); active = null
        aiText = ""; aiChanges.clear(); terminal = ""; Terminal.cwd = null; message = ""; tab = 0; tick++
    }

    fun closeProject() { saveAll(); project = null; ws = null; projects = listProjects() }

    fun deleteProject(name: String) { File(base, name).deleteRecursively(); projects = listProjects() }

    /** Saves the project as a ZIP (also in Documents) and opens the share sheet. [name] null = the open project. */
    suspend fun shareZip(name: String? = null) {
        val projectName = name ?: project ?: return
        val w = if (name == null) ws else Workspace(File(base, name))
        if (w == null) return
        try {
            if (name == null) saveAll()
            val bytes = withContext(Dispatchers.IO) { w.zip() }
            val saved = withContext(Dispatchers.IO) { FileStorage.save(ctx, projectName, "zip", "application/zip", bytes) }
            message = "ZIP saved: ${saved.name} (${saved.publicPath})"
            if (!FileStorage.share(ctx, File(saved.path), "application/zip")) message = "ZIP saved to ${saved.publicPath}, but no app could receive it."
        } catch (e: Exception) { message = "Could not create the ZIP: ${e.message ?: "unknown error"}" }
    }

    fun openFile(rel: String) {
        val w = ws ?: return
        try {
            val t = w.read(rel)
            texts[rel] = t; saved[rel] = t
            if (rel !in open) open.add(rel)
            active = rel; tab = 1; message = ""
        } catch (e: Exception) { message = e.message ?: "Cannot open this file." }
    }

    fun closeFile(rel: String) {
        save(rel); open.remove(rel); texts.remove(rel); saved.remove(rel)
        if (active == rel) active = open.lastOrNull()
    }

    fun dirty(rel: String) = texts[rel] != saved[rel]

    fun save(rel: String) {
        val w = ws ?: return
        val t = texts[rel] ?: return
        if (t == saved[rel]) return
        try { w.write(rel, t); saved[rel] = t; message = "Saved $rel" } catch (e: Exception) { message = "Could not save: ${e.message}" }
    }

    fun saveAll() { for (r in open.toList()) save(r) }

    suspend fun runAi(s: Settings, mode: String, instruction: String) {
        val w = ws ?: return
        aiBusy = true; aiText = ""; aiChanges.clear()
        try {
            saveAll()
            val r = withContext(Dispatchers.Default) { CodeAgent.run(s, w, mode, instruction, active, null) }
            aiText = r.summary
            aiChanges.addAll(r.changes)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Throwable) { aiText = "Couldn't finish: ${e.message ?: "unknown error"}" }
        finally { aiBusy = false }
    }

    fun accept(ch: FileChange) {
        val w = ws ?: return
        try {
            w.write(ch.path, ch.content)
            if (texts.containsKey(ch.path)) { texts[ch.path] = ch.content; saved[ch.path] = ch.content }
            message = "Applied ${ch.path}"; tick++
        } catch (e: Exception) { message = "Could not apply ${ch.path}: ${e.message}" }
        aiChanges.remove(ch)
    }

    fun reject(ch: FileChange) { aiChanges.remove(ch) }

    /** The page with its CSS and JavaScript files put inside, so it runs in one preview. */
    fun previewProject() {
        val w = ws ?: return
        saveAll()
        val rel = w.tree().firstOrNull { !it.dir && it.rel.endsWith("index.html") }?.rel ?: w.tree().firstOrNull { !it.dir && it.rel.endsWith(".html") }?.rel
        if (rel == null) { message = "No HTML file in this project to preview."; return }
        try {
            val dir = rel.substringBeforeLast('/', "")
            fun resolve(p: String) = if (dir.isEmpty() || p.startsWith("/")) p.trimStart('/') else "$dir/$p"
            var html = w.read(rel)
            html = Regex("<link[^>]*href=[\"']([^\"']+\\.css)[\"'][^>]*>", RegexOption.IGNORE_CASE).replace(html) { m ->
                try { "<style>\n" + w.read(resolve(m.groupValues[1])) + "\n</style>" } catch (_: Exception) { m.value } }
            html = Regex("<script([^>]*)src=[\"']([^\"']+)[\"']([^>]*)>\\s*</script>", RegexOption.IGNORE_CASE).replace(html) { m ->
                if (m.groupValues[2].startsWith("http")) m.value
                else try { "<script" + m.groupValues[1] + m.groupValues[3] + ">\n" + w.read(resolve(m.groupValues[2])) + "\n</script>" } catch (_: Exception) { m.value } }
            val f = File(ctx.cacheDir, "zeniq_code_preview.html"); f.writeText(html)
            preview = f.path
            previewRoot = if (dir.isEmpty()) w.root.absolutePath else File(w.root, dir).absolutePath
        } catch (e: Exception) { message = "Could not build the preview: ${e.message}" }
    }
}

private class HighlightTransform(val lang: String, val dark: Boolean, val on: Boolean) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(if (on) highlightForEditor(text.text, lang, dark) else text, OffsetMapping.Identity)
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, singleLine: Boolean = true, mono: Boolean = false) {
    val c = LocalIos.current
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(c.fill).padding(horizontal = 12.dp, vertical = 9.dp)) {
        if (value.isEmpty()) Text(hint, color = c.sub, fontSize = 14.sp)
        BasicTextField(value, onChange, singleLine = singleLine,
            textStyle = TextStyle(color = c.text, fontSize = 14.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default),
            cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun CodeScreen(vm: AstraViewModel) {
    val c = LocalIos.current
    val ctx = LocalContext.current
    val st = remember { CodeState(ctx) }
    val s by vm.settings.collectAsState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(c.bg)) {
        if (st.ws == null) ProjectList(st) else Workbench(st, s, scope)
    }
    st.preview?.let { p ->
        SitePreview(p, "Preview", onClose = { st.preview = null },
            onOpenExternal = { FileStorage.open(ctx, File(p), "text/html") },
            root = st.previewRoot ?: st.ws?.root?.absolutePath)
    }
}

@Composable
private fun ProjectList(st: CodeState) {
    val c = LocalIos.current
    var name by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<String?>(null) }
    val listScope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize().padding(bottom = 12.dp)) {
        item {
            Text("Code", Modifier.padding(start = 20.dp, top = 18.dp), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = c.text)
            Text("A coding workspace on your phone: files, editor, AI that reads your whole project, terminal and history.",
                Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp), fontSize = 14.sp, color = c.sub, lineHeight = 19.sp)
        }
        item { SectionHeader("New project") }
        item {
            Group {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(name, { name = it }, "Project name")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("Web (HTML/CSS/JS)") { st.create(name, "web"); name = "" }
                        PillButton("Python") { st.create(name, "python"); name = "" }
                        PillButton("Blank") { st.create(name, "blank"); name = "" }
                    }
                }
            }
        }
        item { SectionHeader("Your projects") }
        if (st.projects.isEmpty()) item { Text("No projects yet. Create one above.", Modifier.padding(horizontal = 32.dp, vertical = 8.dp), color = c.sub, fontSize = 14.sp) }
        items(st.projects) { p ->
            Group(Modifier.padding(bottom = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1)
                    PillButton("Open") { st.openProject(p) }
                    PillButton("Share ZIP", c.accent) { listScope.launch { st.shareZip(p) } }
                    if (confirm == p) PillButton("Sure?", c.red) { st.deleteProject(p); confirm = null }
                    else PillButton("Delete", c.red) { confirm = p }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Workbench(st: CodeState, s: Settings, scope: CoroutineScope) {
    val c = LocalIos.current
    val tabs = listOf("Files", "Editor", "AI", "Terminal", "History", "Claude Code")
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton("\u2039 Projects") { st.closeProject() }
        Text(st.project ?: "", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = c.text, maxLines = 1)
        PillButton("\u25B6 Run", c.green) { st.previewProject() }
        PillButton("Save & Share", c.accent) { scope.launch { st.shareZip() } }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tabs.forEachIndexed { i, t -> PillButton(t, if (st.tab == i) c.accent else c.sub) { st.tab = i } }
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        when (st.tab) {
            0 -> FilesTab(st)
            1 -> EditorTab(st)
            2 -> AiTab(st, s, scope)
            3 -> TerminalTab(st, scope)
            5 -> ClaudeTerminalTab(st.ws?.root, st.project)
            else -> HistoryTab(st, scope)
        }
    }
    if (st.message.isNotBlank()) Text(st.message, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = c.sub, fontSize = 12.sp, maxLines = 2)
}

@Composable
private fun FilesTab(st: CodeState) {
    val c = LocalIos.current
    val w = st.ws ?: return
    val nodes = remember(st.tick, st.ws) { w.tree() }
    var newName by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(newName, { newName = it }, "New file or folder, for example src/app.js")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("New file", enabled = newName.isNotBlank()) {
                        try { w.newFile(newName.trim()); st.tick++; st.openFile(newName.trim()); newName = "" } catch (e: Exception) { st.message = e.message ?: "Could not create the file." }
                    }
                    PillButton("New folder", enabled = newName.isNotBlank()) {
                        try { w.newDir(newName.trim()); st.tick++; newName = "" } catch (e: Exception) { st.message = e.message ?: "Could not create the folder." }
                    }
                }
            }
        }
        items(nodes) { n ->
            Row(Modifier.fillMaxWidth().clickable { if (!n.dir) st.openFile(n.rel) }.padding(start = (16 + n.depth * 18).dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (n.dir) "\uD83D\uDCC1" else "\uD83D\uDCC4", fontSize = 15.sp)
                Spacer(Modifier.width(8.dp))
                Text(n.name, Modifier.weight(1f), color = if (n.dir) c.accent else c.text, fontSize = 15.sp, maxLines = 1)
                if (!n.dir) Text("${(n.size + 512) / 1024} KB", color = c.sub, fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                if (confirm == n.rel) Text("Delete?", Modifier.clickable { try { w.delete(n.rel); st.open.remove(n.rel); st.texts.remove(n.rel); st.saved.remove(n.rel); if (st.active == n.rel) st.active = st.open.lastOrNull(); st.tick++ } catch (e: Exception) { st.message = e.message ?: "" }; confirm = null },
                    color = c.red, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                else Text("\u2715", Modifier.clickable { confirm = n.rel }, color = c.sub, fontSize = 14.sp)
            }
        }
        if (nodes.isEmpty()) item { Text("This project is empty. Create a file above or ask the AI to generate one.", Modifier.padding(20.dp), color = c.sub, fontSize = 14.sp) }
    }
}

@Composable
private fun EditorTab(st: CodeState) {
    val c = LocalIos.current
    val w = st.ws ?: return
    var find by remember { mutableStateOf("") }
    var repl by remember { mutableStateOf("") }
    var showFind by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf(listOf<com.astra.agent.code.Hit>()) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (p in st.open.toList()) {
                PillButton((if (st.dirty(p)) "\u2022 " else "") + p.substringAfterLast('/'), if (st.active == p) c.accent else c.sub) { st.active = p }
            }
        }
        val a = st.active
        if (a == null) {
            Text("Open a file from the Files tab to edit it here.", Modifier.padding(20.dp), color = c.sub, fontSize = 14.sp)
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Save", c.green, enabled = st.dirty(a)) { st.save(a) }
                PillButton("Find", if (showFind) c.accent else c.sub) { showFind = !showFind }
                PillButton("Close file", c.red) { st.closeFile(a) }
            }
            if (showFind) {
                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field(find, { find = it }, "Find")
                    Field(repl, { repl = it }, "Replace with")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("Find in project", enabled = find.isNotEmpty()) { hits = w.search(find); st.message = "${hits.size} match(es)" }
                        PillButton("Replace in file", enabled = find.isNotEmpty()) {
                            val t = st.texts[a] ?: ""
                            val n = Regex(Regex.escape(find)).findAll(t).count()
                            st.texts[a] = t.replace(find, repl); st.message = "$n replaced in this file"
                        }
                        PillButton("Replace all", c.orange, enabled = find.isNotEmpty()) {
                            st.saveAll()
                            val n = w.replaceAll(find, repl)
                            for (p in st.open.toList()) try { val t = w.read(p); st.texts[p] = t; st.saved[p] = t } catch (_: Exception) {}
                            st.tick++; st.message = "$n replaced in the project"
                        }
                    }
                    for (h in hits.take(40)) {
                        Text("${h.path}:${h.line}  ${h.text}", Modifier.fillMaxWidth().clickable { st.openFile(h.path) }.padding(vertical = 3.dp),
                            color = c.sub, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                    }
                }
            }
            val t = st.texts[a] ?: ""
            val lang = a.substringAfterLast('.', "")
            val transform = remember(lang, c.dark, t.length <= 60000) { HighlightTransform(lang, c.dark, t.length <= 60000) }
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)) {
                BasicTextField(t, { st.texts[a] = it }, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 300.dp),
                    textStyle = TextStyle(color = c.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp),
                    visualTransformation = transform, cursorBrush = SolidColor(c.accent))
            }
        }
    }
}

@Composable
private fun AiTab(st: CodeState, s: Settings, scope: CoroutineScope) {
    val c = LocalIos.current
    val w = st.ws ?: return
    var instr by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("Edit") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in CodeAgent.modes) PillButton(m, if (mode == m) c.accent else c.sub) { mode = m }
        }
        Field(instr, { instr = it }, when (mode) {
            "Generate" -> "What should I create? For example: a to-do app with a dark theme"
            "Explain" -> "What should I explain? For example: how does script.js work?"
            "Debug" -> "Describe the problem or paste the error"
            "Refactor" -> "What should I clean up?"
            else -> "What should I change? For example: make the button blue and add a counter"
        }, singleLine = false, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 90.dp))
        Text("The AI reads the project tree, the open file and the most relevant files. You review every change before it is applied.", color = c.sub, fontSize = 12.sp, lineHeight = 16.sp)
        PillButton(if (st.aiBusy) "Working\u2026" else "Ask ZENIQ Code", enabled = !st.aiBusy && instr.isNotBlank()) { scope.launch { st.runAi(s, mode, instr) } }
        if (st.aiBusy) CircularProgressIndicator(Modifier.size(24.dp))
        if (st.aiText.isNotBlank()) Text(st.aiText, color = c.text, fontSize = 14.sp, lineHeight = 20.sp)
        if (st.aiChanges.size > 1) PillButton("Accept all ${st.aiChanges.size} changes", c.green) { for (ch in st.aiChanges.toList()) st.accept(ch) }
        for (ch in st.aiChanges.toList()) {
            val diff = remember(ch) {
                val old = try { w.read(ch.path) } catch (_: Exception) { "" }
                Diff.compact(Diff.lines(old, ch.content))
            }
            val added = diff.count { it.kind == '+' }; val removed = diff.count { it.kind == '-' }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(ch.path, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = c.text, fontSize = 14.sp, maxLines = 1)
                    Text("+$added", color = c.green, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("-$removed", color = c.red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    for (d in diff.take(300)) {
                        val bg = when (d.kind) { '+' -> c.green.copy(alpha = 0.18f); '-' -> c.red.copy(alpha = 0.18f); else -> Color.Transparent }
                        Text((if (d.kind == '.') "" else d.kind.toString() + " ") + d.text, Modifier.fillMaxWidth().background(bg).padding(horizontal = 4.dp),
                            color = c.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Accept", c.green) { st.accept(ch) }
                    PillButton("Reject", c.red) { st.reject(ch) }
                }
            }
        }
    }
}

@Composable
private fun TerminalTab(st: CodeState, scope: CoroutineScope) {
    val c = LocalIos.current
    val w = st.ws ?: return
    var cmd by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun run(command: String) {
        if (command.isBlank() || busy) return
        busy = true
        scope.launch {
            st.saveAll()
            val out = withContext(Dispatchers.IO) {
                if (command.trim() == "git" || command.trim().startsWith("git ")) com.astra.agent.code.GitOps.run(command, w.root)
                else Terminal.run(command, w.root, timeoutSec = 120)
            }
            val shown = Terminal.cwd?.let { try { it.relativeTo(w.root).path.ifEmpty { "." } } catch (_: Exception) { "." } } ?: "."
            st.terminal = (st.terminal + shown + " $ " + command + "\n" + out + "\n\n").takeLast(30_000)
            st.tick++; busy = false
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("ls -la") { run("ls -la") }
            PillButton("pwd") { run("pwd") }
            PillButton("git init") { run("git init") }
            PillButton("git status") { run("git status") }
            PillButton("git commit") { run("git commit -a -m \"ZENIQ commit\"") }
            PillButton("git log") { run("git log") }
            PillButton("Run tests", c.green) { run("if [ -f package.json ]; then npm test; elif ls *test*.py >/dev/null 2>&1; then python3 -m pytest || python3 -m unittest; elif [ -f gradlew ]; then sh gradlew test; else echo 'No test runner found in this project (package.json, *test*.py or gradlew).'; fi") }
            PillButton("Build log", c.orange) { run("if [ -f gradlew ]; then sh gradlew build; elif [ -f package.json ]; then npm run build; elif [ -f Makefile ]; then make; else echo 'No build file found (gradlew, package.json or Makefile).'; fi") }
            PillButton("Stop", c.red, enabled = busy) { Terminal.stop() }
            PillButton("Clear", c.red) { st.terminal = "" }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card).padding(10.dp).verticalScroll(rememberScrollState(), reverseScrolling = true)) {
            Text(if (st.terminal.isEmpty()) "Runs shell commands inside the project folder (Android's built-in tools: ls, cat, grep, sed, wc ...). Output and errors appear here." else st.terminal,
                color = if (st.terminal.isEmpty()) c.sub else c.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(cmd, { cmd = it }, "Command", Modifier.weight(1f), mono = true)
            PillButton(if (busy) "\u2026" else "Run", c.green, enabled = !busy && cmd.isNotBlank()) { run(cmd); cmd = "" }
        }
    }
}

@Composable
private fun HistoryTab(st: CodeState, scope: CoroutineScope) {
    val c = LocalIos.current
    val ctx = LocalContext.current
    val w = st.ws ?: return
    var msg by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<String?>(null) }
    val cps = remember(st.tick, st.ws) { w.checkpoints() }
    val changes = remember(st.tick, st.ws) { w.changes(cps.firstOrNull()?.id) }
    LazyColumn(Modifier.fillMaxSize().padding(bottom = 12.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Checkpoints work like git commits: save a version, see what changed, go back any time.", color = c.sub, fontSize = 12.sp, lineHeight = 16.sp)
                Field(msg, { msg = it }, "What did you change? (message)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Commit checkpoint", c.green) { st.saveAll(); w.commit(msg); msg = ""; st.tick++; st.message = "Checkpoint saved" }
                    PillButton("Export ZIP") {
                        scope.launch {
                            st.saveAll()
                            val bytes = withContext(Dispatchers.IO) { w.zip() }
                            val saved = withContext(Dispatchers.IO) { FileStorage.save(ctx, st.project ?: "project", "zip", "application/zip", bytes) }
                            st.message = "ZIP saved to ${saved.publicPath}"
                            FileStorage.share(ctx, File(saved.path), "application/zip")
                        }
                    }
                }
            }
        }
        item { SectionHeader("Changes since the last checkpoint") }
        if (changes.isEmpty()) item { Text("No changes.", Modifier.padding(horizontal = 32.dp, vertical = 6.dp), color = c.sub, fontSize = 14.sp) }
        items(changes) { ch ->
            val col = when (ch.firstOrNull()) { '+' -> c.green; '-' -> c.red; else -> c.orange }
            Text(ch, Modifier.padding(horizontal = 32.dp, vertical = 3.dp), color = col, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
        item { SectionHeader("Checkpoints") }
        if (cps.isEmpty()) item { Text("No checkpoints yet.", Modifier.padding(horizontal = 32.dp, vertical = 6.dp), color = c.sub, fontSize = 14.sp) }
        items(cps) { cp ->
            Group(Modifier.padding(bottom = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(cp.message, fontWeight = FontWeight.SemiBold, color = c.text, fontSize = 15.sp, maxLines = 1)
                        Text(java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(cp.time)) + " \u2022 ${cp.files} files", color = c.sub, fontSize = 12.sp)
                    }
                    if (confirm == cp.id) PillButton("Sure?", c.red) {
                        try { w.restore(cp.id); st.open.clear(); st.texts.clear(); st.saved.clear(); st.active = null; st.tick++; st.message = "Restored \"${cp.message}\"" } catch (e: Exception) { st.message = e.message ?: "" }
                        confirm = null
                    } else PillButton("Restore", c.orange) { confirm = cp.id }
                }
            }
        }
    }
}
