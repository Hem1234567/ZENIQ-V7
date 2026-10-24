package com.astra.agent.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astra.agent.llm.*

@Composable
fun ModelsScreen(vm: AstraViewModel) {
    val c         = LocalIos.current
    val installed by vm.installed.collectAsState()
    val dl        by vm.models.downloads.collectAsState()
    val loaded    by vm.loadedModel.collectAsState()
    val loading   by vm.loadingModel.collectAsState()
    var custom    by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {

        // ── Title ─────────────────────────────────────────────────────────
        item {
            Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ViewInAr, contentDescription = null, tint = c.accent, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(8.dp))
                Text("Models", fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
            }
        }

        // ── Your Phone ────────────────────────────────────────────────────
        item { SectionHeader("Your phone") }
        item {
            val rec = remember { vm.models.recommended() }
            val storageInfo by vm.storageInfo.collectAsState()
            Group {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Memory, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("%.1f GB RAM".format(vm.models.ramGb), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(16.dp))
                        Icon(Icons.Filled.Storage, contentDescription = null, tint = c.sub, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(storageInfo, fontSize = 13.sp, color = c.sub)
                    }
                    // Low disk warning
                    if (vm.models.freeDiskGb < 2.0) {
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.clip(RoundedCornerShape(10.dp)).background(c.red.copy(alpha = 0.10f))
                            .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, null, tint = c.red, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Low storage! Less than 2 GB free. Delete unused models before downloading more.",
                                fontSize = 12.sp, color = c.red, lineHeight = 17.sp)
                        }
                    }
                    Text("Best for chat: ${rec.name}", fontSize = 14.sp, color = c.sub, modifier = Modifier.padding(top = 6.dp))
                    val recV = remember { vm.models.recommendedVision() }
                    Text("Best for vision: ${recV.name}", fontSize = 14.sp, color = c.sub)
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Star, null, tint = c.accent, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Recommended: ${rec.name}", Modifier.weight(1f), fontSize = 14.sp, color = c.accent, fontWeight = FontWeight.SemiBold)
                        DlButton(vm, rec.url, dl[rec.url])
                    }
                    DlProgress(dl[rec.url])
                }
            }
        }

        // ── Installed ─────────────────────────────────────────────────────
        item { SectionHeader("Installed") }
        item {
            Group {
                if (installed.isEmpty()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Inbox, null, tint = c.sub, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("No models yet. Download one below.", color = c.sub, fontSize = 15.sp)
                    }
                }
                installed.forEachIndexed { i, f ->
                    if (i > 0) Hairline()
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (loaded == f.name) Icons.Filled.CheckCircle else Icons.Filled.Storage,
                            null, tint = if (loaded == f.name) c.green else c.sub, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name.removeSuffix(".gguf"), fontSize = 16.sp, maxLines = 1)
                            Text("${f.length() / 1_000_000} MB", fontSize = 13.sp, color = c.sub)
                        }
                        if (loaded == f.name) {
                            Text("Active", color = c.green, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        } else {
                            val isThisLoading = loading
                            PillButton(if (isThisLoading) "Loading…" else "Load", enabled = !isThisLoading) { vm.loadModel(f) }
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(32.dp).clip(CircleShape).background(c.red.copy(alpha = 0.12f))
                            .pressScale { vm.deleteModel(f) }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Delete, null, tint = c.red, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        // ── Chat / Text Models ────────────────────────────────────────────
        item { SectionHeader("Chat models (text)") }
        item {
            Group {
                vm.models.catalog.forEachIndexed { i, m ->
                    if (i > 0) Hairline()
                    ModelRow(vm, m, dl[m.url], dl)
                }
            }
        }

        // ── Vision Models ─────────────────────────────────────────────────
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                SectionHeaderInline("Vision models (see images)")
                Row(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(12.dp))
                    .background(c.accent.copy(alpha = 0.08f)).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Visibility, null, tint = c.accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Vision models let ZENIQ understand images. Download one and load it to use with image input.",
                        fontSize = 12.sp, color = c.accent, lineHeight = 17.sp)
                }
            }
        }
        item {
            Group {
                vm.models.visionCatalog.forEachIndexed { i, m ->
                    if (i > 0) Hairline()
                    ModelRow(vm, m, dl[m.url], dl)
                }
            }
        }

        // ── Voice section ─────────────────────────────────────────────────────
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                SectionHeaderInline("Voice (TTS)")
                Row(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(12.dp))
                    .background(c.green.copy(alpha = 0.08f)).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.RecordVoiceOver, null, tint = c.green, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Download Kokoro for high-quality neural voices. Android built-in works offline with no download.",
                        fontSize = 12.sp, color = c.green, lineHeight = 17.sp)
                }
            }
        }
        item { KokoroVoiceSection(vm) }
        item { Spacer(Modifier.height(8.dp)) }


        // ── Cloud models: GPT / paid chat, image, video, any-to-any ───────────────
        item { SectionHeader("Cloud models (OpenRouter)") }
        item { CloudModelsSection(vm) }
        item { Spacer(Modifier.height(8.dp)) }

        // ── Custom GGUF ────────────────────────────────────────────────────
        item { SectionHeader("Custom GGUF link") }
        item {
            Group {
                Box(Modifier.padding(16.dp)) {
                    BasicTextField(custom, { custom = it }, Modifier.fillMaxWidth(), singleLine = true,
                        textStyle = TextStyle(color = c.text, fontSize = 15.sp), cursorBrush = SolidColor(c.accent),
                        decorationBox = { inner ->
                            Box { if (custom.isEmpty()) Text("https://huggingface.co/…/model.gguf", color = c.sub, fontSize = 15.sp); inner() }
                        })
                }
                if (custom.endsWith(".gguf")) {
                    Hairline()
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Link, null, tint = c.sub, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(vm.models.fileFor(custom).name, Modifier.weight(1f), fontSize = 14.sp, maxLines = 1)
                        DlButton(vm, custom, dl[custom])
                    }
                    DlProgress(dl[custom], Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
    }
}

// ── KokoroVoiceSection ────────────────────────────────────────────────────────
@Composable
fun KokoroVoiceSection(vm: AstraViewModel) {
    val c             = LocalIos.current
    val selectedVoice by vm.kokoro.selectedVoice.collectAsState()
    val ttsEngine     by vm.voice.ttsEngine.collectAsState()
    val speed         by vm.kokoro.speed.collectAsState()

    Group {
        Column(Modifier.padding(16.dp)) {

            // ── Header row ────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.GraphicEq, null, tint = c.accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Voice Personas", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("10 voices · No download · Works instantly",
                        fontSize = 13.sp, color = c.sub)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, tint = c.green, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Ready", fontSize = 13.sp, color = c.green, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(12.dp))
            Hairline()
            Spacer(Modifier.height(10.dp))

            // ── Engine toggle ─────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Active engine:", fontSize = 13.sp, color = c.sub, modifier = Modifier.weight(1f))
                Row(Modifier.clip(RoundedCornerShape(20.dp)).background(c.fill).padding(2.dp)) {
                    listOf(TtsEngine.ANDROID to "Android", TtsEngine.KOKORO to "Kokoro").forEach { (eng, label) ->
                        val sel = ttsEngine == eng
                        Box(
                            Modifier.clip(RoundedCornerShape(18.dp))
                                .background(if (sel) c.accent else c.fill)
                                .clickable { vm.setTtsEngine(eng) }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(label, fontSize = 13.sp,
                                color = if (sel) c.bg else c.text,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
        }

        // ── Voice picker ──────────────────────────────────────────────────
        Hairline()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Pick a voice", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(KOKORO_VOICES) { voice ->
                    val selected = selectedVoice.id == voice.id
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) c.accent.copy(alpha = 0.15f) else c.fill)
                            .border(
                                width = if (selected) 1.5.dp else 0.dp,
                                color = if (selected) c.accent else c.fill,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { vm.selectKokoroVoice(voice); vm.setTtsEngine(TtsEngine.KOKORO) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(if (voice.gender == "Female") "\uD83D\uDC69" else "\uD83D\uDC68", fontSize = 22.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(voice.displayName, fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) c.accent else c.text)
                        Text(voice.accent, fontSize = 10.sp, color = c.sub)
                    }
                }
            }

            // ── Speed slider ──────────────────────────────────────────────
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Speed, null, tint = c.sub, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("Speed: ${"%,.1f".format(speed)}x", fontSize = 13.sp, color = c.sub,
                    modifier = Modifier.width(80.dp))
                Slider(
                    value = speed,
                    onValueChange = { vm.setKokoroSpeed(it) },
                    valueRange = 0.5f..2.0f,
                    steps = 5,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent)
                )
            }

            // ── Test button ───────────────────────────────────────────────
            Spacer(Modifier.height(6.dp))
            PillButton("Test voice") {
                vm.setTtsEngine(TtsEngine.KOKORO)
                vm.voice.speak(
                    "Hello! I am ${selectedVoice.displayName}, your ZENIQ AI assistant.",
                    vm.kokoro
                )
            }
        }
    }
}

// ── Android TTS built-in card ─────────────────────────────────────────────────
@Composable
fun AndroidTtsCard() {
    val c = LocalIos.current
    Group {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.RecordVoiceOver, null, tint = c.sub, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Android TTS (built-in)", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("No download · Always available", fontSize = 13.sp, color = c.sub)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, tint = c.green, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Ready", fontSize = 13.sp, color = c.green, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ── Single model row ──────────────────────────────────────────────────────────
@Composable
private fun ModelRow(vm: AstraViewModel, m: com.astra.agent.llm.CatalogModel,
                     st: DlState?, dl: Map<String, DlState>) {
    val c    = LocalIos.current
    val fit  = vm.models.fit(m)
    val (fitIcon, fitColor) = when (fit) {
        Fit.Great  -> Icons.Filled.CheckCircle to c.green
        Fit.Ok     -> Icons.Filled.Warning to c.orange
        Fit.TooBig -> Icons.Filled.Cancel to c.red
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(fitIcon, null, tint = fitColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.name, fontSize = 16.sp)
                    Spacer(Modifier.width(6.dp))
                    if (m.url == vm.models.recommended().url || m.url == vm.models.recommendedVision().url)
                        Icon(Icons.Filled.Star, null, tint = c.accent, modifier = Modifier.size(12.dp))
                    if (m.kind == ModelKind.VISION)
                        Box(Modifier.padding(start = 4.dp).clip(RoundedCornerShape(6.dp))
                            .background(c.accent.copy(0.12f)).padding(horizontal = 5.dp, vertical = 1.dp)) {
                            Text("VISION", fontSize = 9.sp, color = c.accent, fontWeight = FontWeight.Bold)
                        }
                }
                Text("${m.sizeLabel} · ${m.note}", fontSize = 13.sp, color = c.sub)
                val (fl, fc) = when (fit) {
                    Fit.Great  -> "Runs great on your phone" to c.green
                    Fit.Ok     -> "Runs, but tight on RAM" to c.orange
                    Fit.TooBig -> "Needs more RAM" to c.red
                }
                Text(fl, fontSize = 12.sp, color = fc, fontWeight = FontWeight.Medium)
            }
            DlButton(vm, m.url, st)
            val hasFile = vm.models.fileFor(m.url).exists()
            val hasPart = vm.models.partialSize(m.url) > 0
            if ((hasFile || hasPart) && st !is DlState.Progress) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(30.dp).clip(CircleShape).background(c.red.copy(alpha = 0.12f))
                    .pressScale { if (hasFile) vm.deleteModel(vm.models.fileFor(m.url)) else vm.deletePartial(m.url) },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Delete, null, tint = c.red, modifier = Modifier.size(14.dp))
                }
            }
        }
        DlProgress(st)
    }
}

@Composable
private fun DlButton(vm: AstraViewModel, url: String, st: DlState?) {
    val have = vm.models.fileFor(url).exists()
    val c    = LocalIos.current
    when {
        have -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, null, tint = c.green, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Ready", color = c.green, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        st is DlState.Progress -> PillButton("Pause", c.orange) { vm.cancelDownload(url) }
        else -> PillButton(if (vm.models.partialSize(url) > 0) "Resume" else "Get") { vm.download(url) }
    }
}

@Composable
private fun DlProgress(st: DlState?, modifier: Modifier = Modifier) {
    val c = LocalIos.current
    AnimatedVisibility(st != null) {
        Column(modifier.padding(top = 10.dp)) {
            if (st is DlState.Progress) {
                val target = if (st.total > 0) st.done.toFloat() / st.total else 0f
                val p by animateFloatAsState(target, tween(400, easing = LinearOutSlowInEasing), label = "p")
                LinearProgressIndicator(progress = { p },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = c.accent, trackColor = c.fill)
                Text("${st.done / 1_000_000} of ${st.total / 1_000_000} MB",
                    Modifier.padding(top = 4.dp), fontSize = 12.sp, color = c.sub)
            } else if (st is DlState.Failed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ErrorOutline, null, tint = c.red, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Failed: ${st.msg}", fontSize = 12.sp, color = c.red)
                }
            }
        }
    }
}

@Composable
private fun SectionHeaderInline(title: String) {
    Text(title, Modifier.padding(top = 20.dp, bottom = 6.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
}


// ── Cloud models ──────────────────────────────────────────────────────────────
@Composable
private fun CloudModelsSection(vm: AstraViewModel) {
    val c = LocalIos.current
    val models by vm.cloudModels.collectAsState()
    val status by vm.cloudStatus.collectAsState()
    val st by vm.settings.collectAsState()
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("GPT & chat", "Image", "Video", "Any-to-any")
    val kinds = listOf(CloudModel.Kind.CHAT, CloudModel.Kind.IMAGE, CloudModel.Kind.VIDEO, CloudModel.Kind.ANY)
    LaunchedEffect(Unit) { vm.refreshCloudModels() }
    Group {
        Column(Modifier.padding(16.dp)) {
            Text("Paid and free cloud models. They need internet and your OpenRouter key (Settings). " +
                "Say \"generate an image of a red apple\" or \"make a short video clip of ...\" in the chat and the selected model is used. " +
                "Pollinations makes images for free with no key.",
                fontSize = 12.sp, color = c.sub, lineHeight = 17.sp)
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tabs.size) { i -> PillButton(tabs[i], if (tab == i) c.accent else c.sub) { tab = i } }
            }
            if (status.isNotBlank()) Text(status, Modifier.padding(top = 8.dp), fontSize = 12.sp, color = c.sub)
        }
        val list = models.filter { it.kind == kinds[tab] }
        list.take(40).forEach { m ->
            Hairline()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text((if (m.note.isBlank()) "" else m.note + " \u2022 ") + m.id, fontSize = 11.sp, color = c.sub, maxLines = 1)
                }
                val active = when (kinds[tab]) {
                    CloudModel.Kind.CHAT -> st.fastMode && st.openRouterModel == m.id
                    CloudModel.Kind.IMAGE, CloudModel.Kind.ANY -> st.imageModel == m.id
                    CloudModel.Kind.VIDEO -> st.videoModel == m.id
                }
                if (active) {
                    Icon(Icons.Filled.CheckCircle, null, tint = c.green, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("In use", color = c.green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    PillButton("Use") {
                        when (kinds[tab]) {
                            CloudModel.Kind.CHAT -> vm.useCloudChatModel(m.id)
                            CloudModel.Kind.IMAGE, CloudModel.Kind.ANY -> vm.setImageModel(m.id)
                            CloudModel.Kind.VIDEO -> vm.setVideoModel(m.id)
                        }
                    }
                }
            }
        }
        if (list.isEmpty()) {
            Hairline()
            Text("No models in this group yet. Pull to refresh by reopening this page.", Modifier.padding(16.dp), fontSize = 13.sp, color = c.sub)
        }
    }
}
