package com.r0pw.editor.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r0pw.editor.ui.Btn
import com.r0pw.editor.ui.C
import com.r0pw.editor.ui.Chip
import com.r0pw.editor.ui.HLine
import com.r0pw.editor.ui.NumberDialog
import com.r0pw.editor.ui.SectionTitle
import com.r0pw.editor.ui.TextDialog
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.max
import kotlin.math.min

private val TABS = listOf("Layers", "Props", "Effects", "Graph", "Media", "Presets")

private fun tc(t: Float): String {
    val m = (t / 60f).toInt()
    val s = t - m * 60f
    return "%02d:%05.2f".format(m, s)
}

@Composable
fun EditorScreen(ed: EditorState, onBack: () -> Unit) {
    ed.track()
    var showExport by remember { mutableStateOf(false) }
    var showProject by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        snapshotFlow { listOf<Any?>(ed.rev, ed.time, ed.playing, ed.selectedId) }
            .collectLatest { ed.renderFrame() }
    }
    LaunchedEffect(ed.playing) {
        if (ed.playing) ed.playLoop()
    }
    LaunchedEffect(ed.message) {
        if (ed.message.isNotBlank()) {
            kotlinx.coroutines.delay(4500)
            ed.message = ""
        }
    }
    DisposableEffect(Unit) { onDispose { ed.release() } }
    BackHandler { ed.closeAndSave(onBack) }

    val screenH = LocalConfiguration.current.screenHeightDp
    Box(Modifier.fillMaxSize().background(C.bg)) {
        Column(Modifier.fillMaxSize()) {
            // ---------------- top bar
            Row(
                Modifier.fillMaxWidth().height(46.dp).background(C.panel).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Btn("←", { ed.closeAndSave(onBack) })
                Text(ed.project.name, color = C.text, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Btn("↶", { ed.undo() }, enabled = ed.canUndo)
                Btn("↷", { ed.redo() }, enabled = ed.canRedo)
                Btn("⚙", { showProject = true })
                Btn("Export", { showExport = true }, accent = true)
            }
            // ---------------- preview
            PreviewBox(ed, Modifier.fillMaxWidth().height((screenH * 0.30f).dp).background(Color(0xFF050507)))
            // ---------------- transport
            Row(
                Modifier.fillMaxWidth().height(42.dp).background(C.panel).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Btn("⏮", { ed.playing = false; ed.seek(0f) })
                Btn("◀", { ed.stepFrames(-1) })
                Btn(if (ed.playing) "⏸" else "▶", { ed.togglePlay() }, accent = true)
                Btn("▶|", { ed.stepFrames(1) })
                Text("${tc(ed.time)} / ${tc(ed.project.duration)}", color = C.text, fontSize = 12.sp)
                Btn("−", { ed.pxPerSec = max(12f, ed.pxPerSec / 1.4f) })
                Btn("+", { ed.pxPerSec = min(400f, ed.pxPerSec * 1.4f) })
                Chip("Loop", ed.settings.loopPlayback, { ed.settings.loopPlayback = !ed.settings.loopPlayback; ed.touch() })
            }
            HLine()
            // ---------------- timeline
            TimelinePanel(ed, Modifier.fillMaxWidth().height(150.dp))
            HLine()
            // ---------------- tabs
            Row(
                Modifier.fillMaxWidth().height(40.dp).background(C.panel).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (i in TABS.indices) Chip(TABS[i], ed.tab == i, { ed.tab = i })
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(C.panel)) {
                when (ed.tab) {
                    0 -> LayersPanel(ed)
                    1 -> PropsPanel(ed)
                    2 -> EffectsPanel(ed)
                    3 -> GraphPanel(ed)
                    4 -> MediaPanel(ed)
                    else -> PresetsPanel(ed)
                }
            }
        }

        // ---------------- message bar
        if (ed.message.isNotBlank()) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(16.dp).background(C.elev).clickable { ed.message = "" }.padding(14.dp)
            ) { Text(ed.message, color = C.text, fontSize = 13.sp) }
        }

        // ---------------- export progress overlay
        if (ed.exporting) {
            Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable { }, contentAlignment = Alignment.Center) {
                Column(Modifier.background(C.panel).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Exporting…  ${(ed.exportProgress * 100).toInt()}%", color = C.text, fontSize = 16.sp)
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.width(220.dp).height(8.dp).background(C.line)) {
                        Box(Modifier.width((220 * ed.exportProgress).dp).height(8.dp).background(C.accent))
                    }
                    Spacer(Modifier.height(14.dp))
                    Btn("Cancel", { ed.cancelExport() }, danger = true)
                }
            }
        }
    }

    if (showExport) ExportDialog(ed) { showExport = false }
    if (showProject) ProjectDialog(ed) { showProject = false }
}

@Composable
private fun PreviewBox(ed: EditorState, modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val aspect = ed.project.width.toFloat() / ed.project.height
        var w = maxWidth
        var h = w / aspect
        if (h > maxHeight) {
            h = maxHeight
            w = h * aspect
        }
        Box(
            Modifier.size(w, h).background(Color.Black)
                .pointerInput(ed.project.width, ed.selectedId) {
                    detectTransformGestures { _, pan, zoom, rotation ->
                        val f = ed.project.width.toFloat() / size.width
                        ed.dragSelected(pan.x * f, pan.y * f, zoom, rotation)
                    }
                }
        ) {
            val img = ed.preview
            if (img != null) Image(img, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
    }
}

@Composable
private fun ExportDialog(ed: EditorState, onClose: () -> Unit) {
    var shortSide by remember { mutableStateOf(1080) }
    var fps by remember { mutableStateOf(ed.project.fps) }
    var audio by remember { mutableStateOf(true) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        containerColor = C.panel,
        title = { Text("Export MP4", color = C.text) },
        text = {
            Column {
                SectionTitle("Resolution (short side)")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (s in listOf(720, 1080, 2160)) Chip(if (s == 2160) "4K" else "${s}p", shortSide == s, { shortSide = s })
                }
                SectionTitle("Frame rate")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (f in listOf(24, 30, 60)) Chip("$f", fps == f, { fps = f })
                }
                SectionTitle("Audio")
                Chip(if (audio) "Include audio" else "No audio", audio, { audio = !audio })
                val size = com.r0pw.editor.engine.Exporter.sizeFor(ed.project, shortSide)
                Text(
                    "${size.first}×${size.second} @ $fps fps · saved to Movies/1r0pw",
                    color = C.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp)
                )
                if (shortSide == 2160) Text("4K needs a device with a 4K H.264 encoder; if it fails try 1080p.", color = C.dim, fontSize = 11.sp)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onClose(); ed.startExport(shortSide, fps, audio) }) { Text("Start") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("Cancel") } }
    )
}

@Composable
private fun ProjectDialog(ed: EditorState, onClose: () -> Unit) {
    var nameDlg by remember { mutableStateOf(false) }
    var durDlg by remember { mutableStateOf(false) }
    val sizes = listOf(
        "1920×1080" to Pair(1920, 1080), "1080×1920" to Pair(1080, 1920), "1080×1080" to Pair(1080, 1080),
        "1280×720" to Pair(1280, 720), "3840×2160" to Pair(3840, 2160)
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        containerColor = C.panel,
        title = { Text("Project settings", color = C.text) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Btn("Name: ${ed.project.name}", { nameDlg = true })
                    Btn("Length: %.1fs".format(ed.project.duration), { durDlg = true })
                }
                SectionTitle("Canvas size")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (s in sizes) Chip(s.first, ed.project.width == s.second.first && ed.project.height == s.second.second, {
                        ed.edit { ed.project.width = s.second.first; ed.project.height = s.second.second }
                    })
                }
                SectionTitle("Frame rate")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (f in listOf(24, 30, 60)) Chip("$f", ed.project.fps == f, { ed.edit { ed.project.fps = f } })
                }
                Text("Changing the canvas size does not move existing layers.", color = C.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("Done") } }
    )
    if (nameDlg) TextDialog("Project name", ed.project.name, onDismiss = { nameDlg = false }) { v ->
        ed.edit { ed.project.name = v.ifBlank { ed.project.name } }
        nameDlg = false
    }
    if (durDlg) NumberDialog("Length (seconds)", ed.project.duration, { durDlg = false }) { v ->
        ed.edit { ed.project.duration = v.coerceIn(1f, 600f) }
        durDlg = false
    }
}
