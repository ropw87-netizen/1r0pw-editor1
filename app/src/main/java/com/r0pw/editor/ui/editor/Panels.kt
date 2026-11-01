package com.r0pw.editor.ui.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r0pw.editor.engine.MediaImporter
import com.r0pw.editor.engine.Presets
import com.r0pw.editor.model.BlendMode
import com.r0pw.editor.model.FxCatalog
import com.r0pw.editor.model.Layer
import com.r0pw.editor.model.LayerType
import com.r0pw.editor.model.Props
import com.r0pw.editor.model.ShapeKind
import com.r0pw.editor.model.TextAnim
import com.r0pw.editor.model.Track
import com.r0pw.editor.plugins.PluginHost
import com.r0pw.editor.ui.*
import kotlin.math.abs

@Composable
fun ChipRow(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in items.indices) Chip(items[i], i == selected, { onSelect(i) })
    }
}

private fun fmt(v: Float): String = if (abs(v) >= 100f) "%.0f".format(v) else "%.1f".format(v)

@Composable
fun PropRow(ed: EditorState, layer: Layer, tr: Track, label: String, min: Float, max: Float) {
    val lt = ed.time - layer.start
    val v = tr.valueAt(lt)
    var dlg by remember { mutableStateOf(false) }
    val hi = if (max <= min) min + 1f else max
    Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(84.dp))
        Slider(
            value = v.coerceIn(min, hi),
            onValueChange = { ed.setValue(tr, layer, it, "pv" + System.identityHashCode(tr)) },
            valueRange = min..hi,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = C.accent, activeTrackColor = C.accent, inactiveTrackColor = C.line)
        )
        Text(fmt(v), color = C.text, fontSize = 11.sp, modifier = Modifier.width(46.dp).clickable { dlg = true })
        Text(
            "⏱", color = if (tr.animated) C.accent2 else C.dim, fontSize = 16.sp,
            modifier = Modifier.clickable { ed.toggleAnimated(tr, layer) }.padding(horizontal = 4.dp)
        )
        if (tr.animated) {
            val on = tr.keyNear(lt) != null
            Text(
                if (on) "◆" else "◇", color = C.accent2, fontSize = 16.sp,
                modifier = Modifier.clickable { ed.toggleKey(tr, layer) }.padding(horizontal = 4.dp)
            )
        }
    }
    if (dlg) NumberDialog(label, v, { dlg = false }) {
        ed.setValue(tr, layer, it, "")
        dlg = false
    }
}

@Composable
fun SwitchRow(label: String, on: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Chip(if (on) "ON" else "OFF", on, onToggle)
    }
}

// ====================================================================== layers tab

@Composable
fun LayersPanel(ed: EditorState) {
    ed.track()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Btn("+ Text", { ed.addText() })
            DropdownBtn("+ Shape", ShapeKind.values().map { it.label }, { ed.addShape(ShapeKind.values()[it]) })
            Btn("+ Null", { ed.addNull() })
            Btn("+ Camera", { ed.addCamera() })
            Btn("Split", { ed.splitSelected() })
        }
        SectionTitle("Layers (top = front)")
        if (ed.project.layers.isEmpty()) Text("No layers yet. Import media or add text/shapes.", color = C.dim, fontSize = 12.sp)
        for (l in ed.project.layers) {
            val sel = ed.selectedId == l.id
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (sel) C.elev else C.panel).clickable { ed.selectedId = l.id }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ColorDot(l.color, 14)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(l.name, color = C.text, fontSize = 13.sp, maxLines = 1)
                    val par = ed.project.layer(l.parentId)
                    Text(
                        l.type.name + (if (par != null) "  ↳ " + par.name else "") + "  %.1fs–%.1fs".format(l.start, l.end),
                        color = C.dim, fontSize = 10.sp
                    )
                }
                Text("▲", color = C.dim, modifier = Modifier.clickable { ed.moveLayer(l.id, -1) }.padding(6.dp))
                Text("▼", color = C.dim, modifier = Modifier.clickable { ed.moveLayer(l.id, 1) }.padding(6.dp))
                Text(if (l.locked) "🔒" else "🔓", modifier = Modifier.clickable { ed.edit { l.locked = !l.locked } }.padding(6.dp))
            }
        }
    }
}

// ====================================================================== media tab

@Composable
fun MediaPanel(ed: EditorState) {
    ed.track()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) ed.importUris(uris)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)) {
        Btn("Import video / image / audio", { picker.launch(arrayOf("video/*", "image/*", "audio/*")) }, accent = true)
        SectionTitle("Media library")
        if (ed.project.media.isEmpty()) Text("Nothing imported yet.", color = C.dim, fontSize = 12.sp)
        for (m in ed.project.media) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.name, color = C.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        m.type.name + (if (m.width > 0) "  ${m.width}×${m.height}" else "") +
                            (if (m.duration > 0f) "  %.1fs".format(m.duration) else ""),
                        color = C.dim, fontSize = 10.sp
                    )
                }
                Btn("Add", { ed.edit { ed.addLayerForMedia(m) } })
            }
        }
    }
}

// ====================================================================== properties tab

@Composable
fun PropsPanel(ed: EditorState) {
    ed.track()
    val l = ed.selected
    if (l == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Select a layer", color = C.dim, fontSize = 13.sp)
        }
        return
    }
    val ctx = LocalContext.current
    val project = ed.project
    var renameDlg by remember { mutableStateOf(false) }
    var textDlg by remember { mutableStateOf(false) }
    var colorTarget by remember { mutableStateOf(0) } // 1 text, 2 stroke, 3 shadow, 4 fill, 5 fill2, 6 shape stroke
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val path = MediaImporter.importFont(ctx, uri)
            if (path != null) ed.edit { l.text?.fontPath = path } else ed.message = "Invalid font file"
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Btn(l.name, { renameDlg = true })
            Btn("Duplicate", { ed.duplicateLayer(l.id) })
            Btn("Delete", { ed.deleteLayer(l.id) }, danger = true)
        }
        SectionTitle("Layer")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Blend", color = C.dim, fontSize = 12.sp)
            DropdownBtn(l.blend.label, BlendMode.values().map { it.label }, { ed.setBlend(l, BlendMode.values()[it]) })
        }
        val candidates = project.layers.filter { it.id != l.id && !project.isDescendantOrSelf(it, l) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Parent", color = C.dim, fontSize = 12.sp)
            DropdownBtn(
                project.layer(l.parentId)?.name ?: "None",
                listOf("None") + candidates.map { it.name },
                { i -> ed.setParent(l, if (i == 0) null else candidates[i - 1].id) }
            )
        }
        if (l.type != LayerType.AUDIO && l.type != LayerType.CAMERA) {
            SwitchRow("3D layer", l.threeD) {
                ed.edit {
                    l.threeD = !l.threeD
                    Props.ensure(l, project.width, project.height)
                }
            }
        }
        var fadeDlg by remember { mutableStateOf(0) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Btn("Fade in %.1fs".format(l.fadeIn), { fadeDlg = 1 })
            Btn("Fade out %.1fs".format(l.fadeOut), { fadeDlg = 2 })
            if (l.type == LayerType.VIDEO || l.type == LayerType.AUDIO) Btn("Speed %.2fx".format(l.speed), { fadeDlg = 3 })
        }
        if (fadeDlg != 0) {
            val cur = when (fadeDlg) { 1 -> l.fadeIn; 2 -> l.fadeOut; else -> l.speed }
            NumberDialog(if (fadeDlg == 3) "Speed" else "Fade seconds", cur, { fadeDlg = 0 }) { v ->
                ed.edit {
                    when (fadeDlg) {
                        1 -> l.fadeIn = v.coerceAtLeast(0f)
                        2 -> l.fadeOut = v.coerceAtLeast(0f)
                        else -> l.speed = v.coerceIn(0.1f, 8f)
                    }
                }
                fadeDlg = 0
            }
        }

        // ---- type specific
        val ts = l.text
        if (l.type == LayerType.TEXT && ts != null) {
            SectionTitle("Text")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Btn("Edit text", { textDlg = true }, accent = true)
                Btn("Import font", { fontPicker.launch(arrayOf("*/*")) })
                if (ts.fontPath != null) Btn("Reset font", { ed.edit { ts.fontPath = null } })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                Text("Fill", color = C.dim, fontSize = 12.sp); ColorDot(ts.color) { colorTarget = 1 }
                Text("Stroke", color = C.dim, fontSize = 12.sp); ColorDot(ts.strokeColor) { colorTarget = 2 }
                Text("Shadow", color = C.dim, fontSize = 12.sp); ColorDot(ts.shadowColor) { colorTarget = 3 }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp)) {
                Chip("Bold", ts.bold, { ed.edit { ts.bold = !ts.bold } })
                Chip("Italic", ts.italic, { ed.edit { ts.italic = !ts.italic } })
                Chip("Sans", ts.family == 0 && ts.fontPath == null, { ed.edit { ts.family = 0; ts.fontPath = null } })
                Chip("Serif", ts.family == 1 && ts.fontPath == null, { ed.edit { ts.family = 1; ts.fontPath = null } })
                Chip("Mono", ts.family == 2 && ts.fontPath == null, { ed.edit { ts.family = 2; ts.fontPath = null } })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                Chip("Left", ts.align == 0, { ed.edit { ts.align = 0 } })
                Chip("Center", ts.align == 1, { ed.edit { ts.align = 1 } })
                Chip("Right", ts.align == 2, { ed.edit { ts.align = 2 } })
            }
            Text("Text animation", color = C.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            ChipRow(TextAnim.values().map { it.label }, ts.anim.ordinal) { ed.edit { ts.anim = TextAnim.values()[it] } }
            if (textDlg) TextDialog("Text", ts.text, singleLine = false, onDismiss = { textDlg = false }) { v ->
                ed.edit { ts.text = v }
                textDlg = false
            }
        }
        val sp = l.shape
        if (l.type == LayerType.SHAPE && sp != null) {
            SectionTitle("Shape")
            ChipRow(ShapeKind.values().map { it.label }, sp.kind.ordinal) { ed.edit { sp.kind = ShapeKind.values()[it] } }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                Text("Fill", color = C.dim, fontSize = 12.sp); ColorDot(sp.fill) { colorTarget = 4 }
                Text("Gradient", color = C.dim, fontSize = 12.sp)
                Chip(if (sp.gradient) "ON" else "OFF", sp.gradient, { ed.edit { sp.gradient = !sp.gradient } })
                if (sp.gradient) ColorDot(sp.fill2) { colorTarget = 5 }
                Text("Stroke", color = C.dim, fontSize = 12.sp); ColorDot(sp.strokeColor) { colorTarget = 6 }
            }
            if (sp.kind == ShapeKind.STAR || sp.kind == ShapeKind.POLYGON) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Btn("Points: ${sp.points}", { ed.edit("pts") { sp.points = if (sp.points >= 12) 3 else sp.points + 1 } })
                    if (sp.kind == ShapeKind.STAR) Btn("Inner: %.2f".format(sp.inner), { ed.edit("inner") { sp.inner = if (sp.inner >= 0.9f) 0.2f else sp.inner + 0.1f } })
                }
            }
        }

        // ---- animated properties
        var lastGroup = ""
        for (d in Props.defs(l, project.width, project.height)) {
            if (d.group != lastGroup) {
                SectionTitle(d.group)
                lastGroup = d.group
            }
            PropRow(ed, l, l.track(d.key, d.def), d.label, d.min, d.max)
        }

        if (l.type != LayerType.AUDIO && l.type != LayerType.CAMERA && l.type != LayerType.NULL) {
            SectionTitle("Mask")
            val mk = l.mask
            SwitchRow("Enable mask", mk != null) { ed.toggleMask(l) }
            if (mk != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Rectangle", mk.kind == 0, { ed.edit { mk.kind = 0 } })
                    Chip("Ellipse", mk.kind == 1, { ed.edit { mk.kind = 1 } })
                    Chip("Invert", mk.inverted, { ed.edit { mk.inverted = !mk.inverted } })
                }
                for (d in Props.maskDefs()) PropRow(ed, l, l.track(d.key, d.def), d.label, d.min, d.max)
            }
        }
        Spacer(Modifier.height(30.dp))
    }

    if (renameDlg) TextDialog("Layer name", l.name, onDismiss = { renameDlg = false }) { v ->
        ed.edit { l.name = v.ifBlank { l.name } }
        renameDlg = false
    }
    if (colorTarget != 0) {
        val initial = when (colorTarget) {
            1 -> l.text?.color
            2 -> l.text?.strokeColor
            3 -> l.text?.shadowColor
            4 -> l.shape?.fill
            5 -> l.shape?.fill2
            else -> l.shape?.strokeColor
        } ?: 0xFFFFFFFF.toInt()
        ColorPickerDialog(initial, { colorTarget = 0 }) { c ->
            ed.edit {
                when (colorTarget) {
                    1 -> l.text?.color = c
                    2 -> l.text?.strokeColor = c
                    3 -> l.text?.shadowColor = c
                    4 -> l.shape?.fill = c
                    5 -> l.shape?.fill2 = c
                    else -> l.shape?.strokeColor = c
                }
            }
            colorTarget = 0
        }
    }
}

// ====================================================================== effects tab

@Composable
fun EffectsPanel(ed: EditorState) {
    ed.track()
    val l = ed.selected
    if (l == null || l.type == LayerType.AUDIO || l.type == LayerType.CAMERA) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Select a visual layer to add effects", color = C.dim, fontSize = 13.sp)
        }
        return
    }
    val ctx = LocalContext.current
    var pluginDlg by remember { mutableStateOf(false) }
    val defs = FxCatalog.all()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            DropdownBtn("+ Add effect", defs.map { it.category + " · " + it.name }, { ed.addEffect(l, defs[it].type) })
            Btn("Save stack as plugin", { pluginDlg = true }, enabled = l.effects.isNotEmpty())
        }
        if (l.effects.isEmpty()) Text("No effects. Stack as many as you like — order matters.", color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        for (e in l.effects.toList()) {
            val def = FxCatalog.find(e.type)
            SectionTitle(def?.name ?: e.type)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(if (e.enabled) "ON" else "OFF", e.enabled, { ed.edit { e.enabled = !e.enabled } })
                Btn("▲", { ed.moveEffect(l, e.id, -1) })
                Btn("▼", { ed.moveEffect(l, e.id, 1) })
                Btn("✕", { ed.removeEffect(l, e.id) }, danger = true)
            }
            if (def != null) for (p in def.params) {
                PropRow(ed, l, e.tracks.getOrPut(p.key) { Track(p.def) }, p.label, p.min, p.max)
            }
        }
        Spacer(Modifier.height(30.dp))
    }
    if (pluginDlg) TextDialog("Plugin name", "My Look", onDismiss = { pluginDlg = false }) { name ->
        val json = PluginHost.fromStack(name, l.effects)
        val err = PluginHost.install(ctx, json)
        ed.message = err ?: "Plugin saved: find it in Add effect › Plugins"
        pluginDlg = false
    }
}

// ====================================================================== presets tab

@Composable
fun PresetsPanel(ed: EditorState) {
    ed.track()
    val ctx = LocalContext.current
    val l = ed.selected
    var transitionOut by remember { mutableStateOf(false) }
    var saveDlg by remember { mutableStateOf(false) }
    var userList by remember { mutableStateOf(ed.presets.list()) }
    var exportName by remember { mutableStateOf("") }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val raw = ed.presets.rawJson(exportName)
        if (uri != null && raw != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(raw.toByteArray()) }
                ed.message = "Preset exported"
            } catch (e: Exception) {
                ed.message = "Export failed"
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                val text = ctx.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } ?: ""
                val ok = ed.presets.importRaw("imported_" + System.currentTimeMillis() % 100000, text)
                ed.message = if (ok) "Preset imported" else "Not a valid preset file"
                userList = ed.presets.list()
            } catch (e: Exception) {
                ed.message = "Import failed"
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)) {
        if (l == null) Text("Select a layer to apply presets to it.", color = C.dim, fontSize = 12.sp)
        SectionTitle("Animation (starts at playhead)")
        ChipRow(Presets.animNames, -1) { i -> if (l != null) ed.edit { Presets.applyAnim(Presets.animNames[i], l, ed.time - l.start) } }
        SectionTitle("Transitions")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("In (layer start)", !transitionOut, { transitionOut = false })
            Chip("Out (layer end)", transitionOut, { transitionOut = true })
        }
        Spacer(Modifier.height(6.dp))
        ChipRow(Presets.transitionNames, -1) { i -> if (l != null) ed.edit { Presets.applyTransition(Presets.transitionNames[i], l, transitionOut) } }
        SectionTitle("Camera movement (select a camera layer)")
        ChipRow(Presets.cameraNames, -1) { i -> if (l != null) ed.edit { Presets.applyCamera(Presets.cameraNames[i], l) } }
        SectionTitle("Text styles (select a text layer)")
        ChipRow(Presets.textNames, -1) { i -> if (l != null) ed.edit { Presets.applyText(Presets.textNames[i], l) } }
        SectionTitle("Templates")
        ChipRow(Presets.templateNames, -1) { i ->
            ed.edit {
                val made = Presets.applyTemplate(Presets.templateNames[i], ed.project, ed.time)
                for (m in made.asReversed()) ed.project.layers.add(0, m)
                ed.selectedId = made.firstOrNull()?.id
            }
        }
        SectionTitle("My presets")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Btn("Save selected layer", { if (l != null) saveDlg = true }, enabled = l != null)
            Btn("Import file", { importer.launch(arrayOf("*/*")) })
        }
        for (name in userList) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = C.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Btn("Apply", {
                    val p = ed.presets.load(name)
                    if (p != null && l != null) ed.edit { Presets.applyLook(p, l) }
                })
                Spacer(Modifier.width(4.dp))
                Btn("Export", { exportName = name; exporter.launch("$name.json") })
                Spacer(Modifier.width(4.dp))
                Btn("✕", { ed.presets.delete(name); userList = ed.presets.list() }, danger = true)
            }
        }
        Spacer(Modifier.height(30.dp))
    }
    if (saveDlg && l != null) TextDialog("Preset name", l.name, onDismiss = { saveDlg = false }) { name ->
        ed.presets.save(name, l)
        userList = ed.presets.list()
        saveDlg = false
        ed.message = "Preset saved"
    }
}
