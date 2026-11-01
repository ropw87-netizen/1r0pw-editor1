package com.r0pw.editor.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.r0pw.editor.Brand
import com.r0pw.editor.data.AppSettings
import com.r0pw.editor.plugins.PluginHost
import com.r0pw.editor.plugins.PluginInfo

@Composable
fun SettingsScreen(settings: AppSettings, onBack: () -> Unit) {
    var q by remember { mutableIntStateOf(settings.previewQuality) }
    var gizmos by remember { mutableStateOf(settings.showGizmos) }
    var snap by remember { mutableStateOf(settings.snapToFrames) }
    var loop by remember { mutableStateOf(settings.loopPlayback) }
    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("←", onBack)
            Text("Settings", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        SectionTitle("Preview quality")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Low 480p", "Medium 720p", "High 1080p").forEachIndexed { i, s ->
                Chip(s, q == i, { q = i; settings.previewQuality = i })
            }
        }
        Text("Lower quality = smoother preview on weaker phones. Export is always full quality.", color = C.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        SectionTitle("Editor")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show transform gizmos", color = C.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Chip(if (gizmos) "ON" else "OFF", gizmos, { gizmos = !gizmos; settings.showGizmos = gizmos })
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Snap to frames", color = C.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Chip(if (snap) "ON" else "OFF", snap, { snap = !snap; settings.snapToFrames = snap })
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Loop playback", color = C.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Chip(if (loop) "ON" else "OFF", loop, { loop = !loop; settings.loopPlayback = loop })
        }

        SectionTitle("About")
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.panel).padding(16.dp)) {
            Text(Brand.NAME, color = C.text, fontSize = 34.sp, fontWeight = FontWeight.Black)
            Text("Studio · version ${Brand.VERSION}", color = C.accent, fontSize = 12.sp)
            Text(Brand.TAGLINE, color = C.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(12.dp))
            Text("Developer", color = C.dim, fontSize = 11.sp)
            Text(Brand.NAME, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            SocialRow()
        }
        Text(
            "Professional mobile motion graphics: layers, keyframes, graph editor, parenting, cameras, masks, effects, plugins and MP4 export — built on Android.",
            color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp)
        )
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
fun PluginScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var plugins by remember { mutableStateOf(PluginHost.loadAll(ctx)) }
    var info by remember { mutableStateOf("") }
    var exportFor by remember { mutableStateOf<PluginInfo?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val text = try {
                ctx.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } ?: ""
            } catch (e: Exception) {
                ""
            }
            val err = PluginHost.install(ctx, text)
            info = err ?: "Plugin installed"
            plugins = PluginHost.loadAll(ctx)
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val p = exportFor
        if (uri != null && p != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(p.file.readBytes()) }
                info = "Plugin exported"
            } catch (e: Exception) {
                info = "Export failed"
            }
        }
    }

    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("←", onBack)
            Text("Plugin manager", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Plugins are safe, declarative JSON packs: each plugin effect is a stack of built-in effects whose parameters can use math on its own sliders. No code is executed.",
            color = C.dim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Install from file", { importer.launch(arrayOf("*/*")) }, accent = true)
            Btn("Install sample", {
                info = PluginHost.install(ctx, PluginHost.SAMPLE) ?: "Sample plugin installed"
                plugins = PluginHost.loadAll(ctx)
            })
        }
        if (info.isNotBlank()) Text(info, color = C.accent2, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        SectionTitle("Installed")
        if (plugins.isEmpty()) Text("No plugins installed.", color = C.dim, fontSize = 12.sp)
        for (p in plugins) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(C.panel).padding(12.dp)) {
                Text(p.name, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text("v${p.version} · ${p.author.ifBlank { "unknown" }} · ${p.effects.size} effect(s)", color = C.dim, fontSize = 11.sp)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(if (p.enabled) "Enabled" else "Disabled", p.enabled, {
                        PluginHost.setEnabled(ctx, p.id, !p.enabled)
                        plugins = PluginHost.loadAll(ctx)
                    })
                    Btn("Export", { exportFor = p; exporter.launch(p.id + ".json") })
                    Btn("Remove", { PluginHost.remove(ctx, p); plugins = PluginHost.loadAll(ctx) }, danger = true)
                }
            }
        }
        SectionTitle("Plugin format (create your own)")
        Text(
            PluginHost.SAMPLE, color = C.dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.panel).padding(10.dp)
        )
        Text(
            "Tip: in the editor, build an effect stack on a layer, then use “Save stack as plugin” in the Effects tab.",
            color = C.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)
        )
        Spacer(Modifier.height(30.dp))
    }
}
